from __future__ import annotations

import json
import subprocess
from contextlib import closing
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from pydantic import BaseModel, Field

from .db import apk_dir, connect, row_to_dict
from .history import (
    append_snapshot,
    immediate,
    list_snapshots,
    propose_pointer,
    public_pointer,
    public_snapshot,
    valid_token,
    apply_legacy_put,
)

app = FastAPI(title="Incremental Hub")


class UpdateInfo(BaseModel):
    version_code: int
    version_name: str
    download_url: str
    commit_message: str | None = None


class SaveBody(BaseModel):
    snapshot_payload: str = Field(min_length=1)
    captured_at_millis: int = Field(ge=0)
    profile_name: str
    session_identity: str
    last_played_at_millis: int = Field(ge=0)
    resume_token: str | None = None
    recovery_state: str | None = None


class SnapshotBody(BaseModel):
    snapshot_id: str = Field(min_length=1, max_length=200)
    lineage_id: str = Field(min_length=1, max_length=200)
    snapshot_payload: str = Field(min_length=1)
    captured_at_millis: int = Field(ge=0)
    idempotency_key: str = Field(min_length=1, max_length=200)
    source: str = Field(default="phone", min_length=1, max_length=64)
    integrity: str = Field(default="accepted", min_length=1, max_length=32)
    progression_json: str | None = None
    profile_name: str = ""
    session_identity: str = ""
    last_played_at_millis: int = Field(default=0, ge=0)
    resume_token: str | None = None
    recovery_state: str | None = None


class PointerBody(BaseModel):
    expected_version: int = Field(ge=0)
    snapshot_id: str = Field(min_length=1, max_length=200)
    lineage_id: str = Field(min_length=1, max_length=200)


def _require_game_id(game_id: str) -> None:
    if not valid_token(game_id):
        raise HTTPException(status_code=400, detail="invalid game id")


def _require_token(value: str, detail: str) -> None:
    if not valid_token(value):
        raise HTTPException(status_code=400, detail=detail)


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


def _commit_message() -> str | None:
    root = Path(__file__).resolve().parents[2]
    try:
        result = subprocess.run(
            ["git", "log", "-1", "--pretty=%B"],
            cwd=root,
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    message = result.stdout.strip()
    return message or None


def _read_update_info() -> UpdateInfo:
    path = apk_dir() / "version.json"
    if not path.is_file():
        raise HTTPException(status_code=404, detail="no release published")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        info = UpdateInfo.model_validate(payload)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        raise HTTPException(status_code=500, detail="version metadata is unreadable") from exc
    message = (info.commit_message or "").strip() or _commit_message()
    return info.model_copy(update={"commit_message": message})


def _apk_path(filename: str) -> Path:
    directory = apk_dir().resolve()
    if filename != Path(filename).name or filename in {".", ".."}:
        raise HTTPException(status_code=400, detail="invalid filename")
    path = (directory / filename).resolve()
    if directory not in path.parents:
        raise HTTPException(status_code=400, detail="invalid filename")
    if not path.is_file():
        raise HTTPException(status_code=404, detail="APK not found")
    return path


@app.get("/", response_class=HTMLResponse)
@app.get("/app", response_class=HTMLResponse)
def install_page() -> HTMLResponse:
    info = _read_update_info()
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Incremental Hub {info.version_name}</title>
  <style>
    :root {{ color-scheme: light; }}
    body {{
      margin: 0;
      min-height: 100vh;
      display: grid;
      place-items: center;
      font-family: system-ui, sans-serif;
      background: #0f172a;
      color: #e2e8f0;
    }}
    main {{
      width: min(28rem, calc(100% - 2rem));
      padding: 1.5rem;
      border-radius: 1rem;
      background: #1e293b;
    }}
    h1 {{ margin: 0 0 0.5rem; font-size: 1.5rem; }}
    p {{ margin: 0 0 1.25rem; line-height: 1.4; color: #94a3b8; }}
    a {{
      display: block;
      text-align: center;
      text-decoration: none;
      color: #0f172a;
      background: #38bdf8;
      font-weight: 700;
      padding: 0.9rem 1rem;
      border-radius: 0.75rem;
    }}
  </style>
</head>
<body>
  <main>
    <h1>Incremental Hub {info.version_name}</h1>
    <p>Version code {info.version_code}. Tap below to download the APK, then open the file to install.</p>
    <a href="/app/download/latest.apk">Download APK</a>
  </main>
</body>
</html>
"""
    return HTMLResponse(content=html)


@app.get("/app/update", response_model=UpdateInfo)
def get_update_info() -> UpdateInfo:
    return _read_update_info()


@app.api_route("/app/download/{filename}", methods=["GET", "HEAD"])
def download_apk(filename: str) -> FileResponse:
    path = _apk_path(filename)
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
    _require_game_id(game_id)
    with closing(connect()) as connection:
        with immediate(connection):
            status, content = apply_legacy_put(connection, game_id, _save_content(game_id, body))
    return JSONResponse(status_code=status, content=content)


@app.post("/saves/{game_id}/snapshots")
def post_snapshot(game_id: str, body: SnapshotBody) -> JSONResponse:
    _require_game_id(game_id)
    _require_token(body.snapshot_id, "invalid snapshot id")
    _require_token(body.lineage_id, "invalid lineage id")
    _require_token(body.idempotency_key, "invalid idempotency key")
    with closing(connect()) as connection:
        with immediate(connection):
            try:
                result = append_snapshot(
                    connection,
                    game_id=game_id,
                    snapshot_id=body.snapshot_id,
                    lineage_id=body.lineage_id,
                    payload=body.snapshot_payload,
                    captured_at=body.captured_at_millis,
                    source=body.source,
                    integrity=body.integrity,
                    idempotency_key=body.idempotency_key,
                    progression_json=body.progression_json,
                    profile_name=body.profile_name,
                    session_identity=body.session_identity,
                    last_played_at=body.last_played_at_millis,
                    resume_token=body.resume_token,
                    recovery_state=body.recovery_state,
                )
            except ValueError as exc:
                raise HTTPException(status_code=400, detail="invalid snapshot") from exc
    status = 409 if result["conflict"] else 200
    return JSONResponse(
        status_code=status,
        content={
            "created": result["created"],
            "conflict": result["conflict"],
            "snapshot": result["snapshot"],
        },
    )


@app.post("/saves/{game_id}/pointer")
def post_pointer(game_id: str, body: PointerBody) -> JSONResponse:
    _require_game_id(game_id)
    _require_token(body.snapshot_id, "invalid snapshot id")
    _require_token(body.lineage_id, "invalid lineage id")
    with closing(connect()) as connection:
        with immediate(connection):
            result = propose_pointer(
                connection,
                game_id=game_id,
                expected_version=body.expected_version,
                snapshot_id=body.snapshot_id,
                lineage_id=body.lineage_id,
            )
    status = 200 if result["ok"] else 409
    return JSONResponse(
        status_code=status,
        content={"conflict": result["conflict"], "pointer": result["pointer"]},
    )


@app.get("/saves/{game_id}/snapshots")
def get_snapshots(game_id: str) -> list[dict]:
    _require_game_id(game_id)
    with closing(connect()) as connection:
        rows = list_snapshots(connection, game_id)
    return [public_snapshot(row) for row in rows]


@app.get("/saves/{game_id}/pointer")
def get_pointer(game_id: str) -> dict:
    _require_game_id(game_id)
    with closing(connect()) as connection:
        pointer = public_pointer(connection, game_id)
    if pointer is None:
        raise HTTPException(status_code=404, detail="no pointer")
    return pointer
