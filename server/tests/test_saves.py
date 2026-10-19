import json

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.main import app, download_apk


def _body(captured_at: int, payload: str = "save-bytes") -> dict:
    return {
        "snapshot_payload": payload,
        "captured_at_millis": captured_at,
        "profile_name": "hub-game-shark",
        "session_identity": "session-1",
        "last_played_at_millis": captured_at,
        "resume_token": None,
        "recovery_state": None,
    }


def test_newer_push_stored_older_rejected(tmp_path, monkeypatch):
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    client = TestClient(app)

    stored = client.put("/saves/shark-incremental", json=_body(10, "first"))
    assert stored.status_code == 200
    assert stored.json()["snapshot_payload"] == "first"

    rejected = client.put("/saves/shark-incremental", json=_body(5, "older"))
    assert rejected.status_code == 409
    assert rejected.json()["snapshot_payload"] == "first"
    assert rejected.json()["captured_at_millis"] == 10

    replaced = client.put("/saves/shark-incremental", json=_body(20, "newer"))
    assert replaced.status_code == 200
    assert replaced.json()["snapshot_payload"] == "newer"

    listed = client.get("/saves")
    assert listed.status_code == 200
    assert listed.json() == [replaced.json()]


def test_equal_timestamp_replaces_payload(tmp_path, monkeypatch):
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    client = TestClient(app)

    client.put("/saves/fe000000", json=_body(10, "first"))
    again = client.put("/saves/fe000000", json=_body(10, "same-time"))
    assert again.status_code == 200
    assert again.json()["snapshot_payload"] == "same-time"


def test_update_metadata_and_apk_download(tmp_path, monkeypatch):
    apk_dir = tmp_path / "apks"
    apk_dir.mkdir()
    (apk_dir / "version.json").write_text(
        json.dumps(
            {
                "version_code": 2,
                "version_name": "0.1.1",
                "download_url": "http://192.168.86.3:8006/app/download/latest.apk",
            }
        ),
        encoding="utf-8",
    )
    (apk_dir / "latest.apk").write_bytes(b"apk-bytes")
    monkeypatch.setenv("HUB_APK_DIR", str(apk_dir))
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    client = TestClient(app)

    info = client.get("/app/update")
    assert info.status_code == 200
    assert info.json()["version_code"] == 2

    downloaded = client.get("/app/download/latest.apk")
    assert downloaded.status_code == 200
    assert downloaded.content == b"apk-bytes"

    head = client.head("/app/download/latest.apk")
    assert head.status_code == 200
    assert head.headers["content-length"] == "9"

    page = client.get("/app")
    assert page.status_code == 200
    assert "Download APK" in page.text
    assert 'href="/app/download/latest.apk"' in page.text

    missing = client.get("/app/download/missing.apk")
    assert missing.status_code == 404

    with pytest.raises(HTTPException) as escaped:
        download_apk("../version.json")
    assert escaped.value.status_code == 400
