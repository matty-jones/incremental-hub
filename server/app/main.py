from __future__ import annotations

import json
from contextlib import closing
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

from .db import apk_dir, connect, row_to_dict

app = FastAPI(title="Incremental Hub")


class UpdateInfo(BaseModel):
    version_code: int
    version_name: str
    download_url: str


class SaveBody(BaseModel):
    snapshot_payload: str = Field(min_length=1)
    captured_at_millis: int = Field(ge=0)
    profile_name: str
    session_identity: str
    last_played_at_millis: int = Field(ge=0)
    resume_token: str | None = None
    recovery_state: str | None = None


def _save_content(game_id: str, body: SaveBody) -> dict:
    return {
        "game_id": game_id,
        "snapshot_payload": body.snapshot_payload,
        "captured_at_millis": body.captured_at_millis,
        "profile_name": body.profile_name,
        "session_identity": body.session_identity,
        "last_played_at_millis": body.last_played_at_millis,
        "resume_token": body.resume_token,
        "recovery_state": body.recovery_state,
    }


@app.get("/app/update", response_model=UpdateInfo)
def get_update_info() -> UpdateInfo:
    path = apk_dir() / "version.json"
    if not path.is_file():
        raise HTTPException(status_code=404, detail="no release published")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        return UpdateInfo.model_validate(payload)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        raise HTTPException(status_code=500, detail="version metadata is unreadable") from exc


@app.get("/app/download/{filename}")
def download_apk(filename: str) -> FileResponse:
    directory = apk_dir().resolve()
    if filename != Path(filename).name or filename in {".", ".."}:
        raise HTTPException(status_code=400, detail="invalid filename")
    path = (directory / filename).resolve()
    if directory not in path.parents:
        raise HTTPException(status_code=400, detail="invalid filename")
    if not path.is_file():
        raise HTTPException(status_code=404, detail="APK not found")
    return FileResponse(
        path=path,
        media_type="application/vnd.android.package-archive",
        filename=filename,
    )


@app.get("/saves")
def list_saves() -> list[dict]:
    with closing(connect()) as connection:
        rows = connection.execute(
            "SELECT * FROM saves ORDER BY game_id"
        ).fetchall()
    return [row_to_dict(row) for row in rows]


@app.put("/saves/{game_id}")
def put_save(game_id: str, body: SaveBody) -> JSONResponse:
    if not game_id or "/" in game_id or game_id in {".", ".."}:
        raise HTTPException(status_code=400, detail="invalid game id")
    content = _save_content(game_id, body)
    with closing(connect()) as connection:
        existing = connection.execute(
            "SELECT * FROM saves WHERE game_id = ?",
            (game_id,),
        ).fetchone()
        if existing is not None and body.captured_at_millis < existing["captured_at_millis"]:
            return JSONResponse(status_code=409, content=row_to_dict(existing))
        connection.execute(
            """
            INSERT INTO saves (
                game_id, snapshot_payload, captured_at_millis, profile_name,
                session_identity, last_played_at_millis, resume_token, recovery_state
            ) VALUES (
                :game_id, :snapshot_payload, :captured_at_millis, :profile_name,
                :session_identity, :last_played_at_millis, :resume_token, :recovery_state
            )
            ON CONFLICT(game_id) DO UPDATE SET
                snapshot_payload = excluded.snapshot_payload,
                captured_at_millis = excluded.captured_at_millis,
                profile_name = excluded.profile_name,
                session_identity = excluded.session_identity,
                last_played_at_millis = excluded.last_played_at_millis,
                resume_token = excluded.resume_token,
                recovery_state = excluded.recovery_state
            """,
            content,
        )
        connection.commit()
    return JSONResponse(status_code=200, content=content)
