import json
import random
import sqlite3
import threading
from contextlib import closing

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.db import connect
from app.history import append_snapshot, immediate, payload_sha256
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

    archived = client.put("/saves/shark-incremental", json=_body(20, "newer"))
    assert archived.status_code == 200
    assert archived.json()["snapshot_payload"] == "first"
    assert archived.json()["pointer_advanced"] is False

    listed = client.get("/saves")
    assert listed.status_code == 200
    assert listed.json() == [stored.json()]
    history = client.get("/saves/shark-incremental/snapshots").json()
    assert {row["snapshot_payload"] for row in history} == {"first", "older", "newer"}
    pointer = client.get("/saves/shark-incremental/pointer").json()
    assert pointer["version"] == 1
    assert pointer["snapshot_payload"] == "first"


def test_equal_timestamp_keeps_the_active_payload(tmp_path, monkeypatch):
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    client = TestClient(app)

    client.put("/saves/fe000000", json=_body(10, "first"))
    again = client.put("/saves/fe000000", json=_body(10, "same-time"))
    assert again.status_code == 200
    assert again.json()["snapshot_payload"] == "first"
    assert client.get("/saves").json()[0]["snapshot_payload"] == "first"
    history = client.get("/saves/fe000000/snapshots").json()
    assert {row["snapshot_payload"] for row in history} == {"first", "same-time"}


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

    noted = apk_dir / "version.json"
    noted.write_text(
        json.dumps(
            {
                "version_code": 3,
                "version_name": "0.1.2",
                "download_url": "http://192.168.86.3:8006/app/download/latest.apk",
                "commit_message": "Explain the build.\n\nIt keeps a second line.",
            }
        ),
        encoding="utf-8",
    )
    published = client.get("/app/update")
    assert published.status_code == 200
    assert published.json()["commit_message"] == "Explain the build.\n\nIt keeps a second line."

    with pytest.raises(HTTPException) as escaped:
        download_apk("../version.json")
    assert escaped.value.status_code == 400


def test_update_commit_message_falls_back_to_git(tmp_path, monkeypatch):
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
    monkeypatch.setenv("HUB_APK_DIR", str(apk_dir))
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    monkeypatch.setattr("app.main._commit_message", lambda: "Latest subject\n\nWhy this build exists.")
    client = TestClient(app)

    info = client.get("/app/update")
    assert info.status_code == 200
    assert info.json()["commit_message"] == "Latest subject\n\nWhy this build exists."


def _snapshot(index: int, payload: str | None = None, key: str | None = None) -> dict:
    body = payload if payload is not None else f"save-{index:04d}"
    return {
        "snapshot_id": f"snap-{index}" if key is None else f"snap-{key}",
        "lineage_id": "legacy",
        "snapshot_payload": body,
        "captured_at_millis": (1000 - index) * 1000,
        "idempotency_key": key or f"event-{index}",
        "source": "phone",
        "integrity": "accepted",
        "profile_name": "hub-game-shark",
        "session_identity": "session-1",
        "last_played_at_millis": index,
    }


def test_legacy_database_migrates_without_changing_a_payload_byte(tmp_path, monkeypatch):
    path = tmp_path / "saves.db"
    monkeypatch.setenv("HUB_DB_PATH", str(path))
    payloads = {
        "shark-incremental": "feature-9-" + ("Z" * 9000) + "\nline\t\"quoted\"\u2603",
        "trimps": "trimps-\u00ff-save",
        "fe000000": "fe:" + json.dumps({"a": [1, 2, 3], "b": "kept"}),
    }
    raw = sqlite3.connect(path)
    raw.execute(
        """
        CREATE TABLE saves (
            game_id TEXT PRIMARY KEY,
            snapshot_payload TEXT NOT NULL,
            captured_at_millis INTEGER NOT NULL,
            profile_name TEXT NOT NULL,
            session_identity TEXT NOT NULL,
            last_played_at_millis INTEGER NOT NULL,
            resume_token TEXT,
            recovery_state TEXT
        )
        """
    )
    for game_id, payload in payloads.items():
        raw.execute(
            """
            INSERT INTO saves (
                game_id, snapshot_payload, captured_at_millis, profile_name,
                session_identity, last_played_at_millis, resume_token, recovery_state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (game_id, payload, 40, f"hub-{game_id}", "session", 40, None, None),
        )
    raw.commit()
    raw.close()
    before = {game_id: payload.encode("utf-8") for game_id, payload in payloads.items()}

    client = TestClient(app)
    listed = client.get("/saves")
    assert listed.status_code == 200
    seen = {row["game_id"]: row["snapshot_payload"].encode("utf-8") for row in listed.json()}
    assert seen == before

    with closing(connect()) as connection:
        for game_id, payload in payloads.items():
            mirror = connection.execute(
                "SELECT snapshot_payload FROM saves WHERE game_id = ?",
                (game_id,),
            ).fetchone()
            stored = connection.execute(
                "SELECT payload, sha256, integrity FROM save_snapshots WHERE game_id = ?",
                (game_id,),
            ).fetchone()
            assert mirror["snapshot_payload"].encode("utf-8") == before[game_id]
            assert stored["payload"].encode("utf-8") == before[game_id]
            assert stored["sha256"] == payload_sha256(payload)
            assert stored["integrity"] == "accepted"
            pointer = connection.execute(
                "SELECT version, snapshot_id FROM current_pointers WHERE game_id = ?",
                (game_id,),
            ).fetchone()
            assert pointer["version"] == 1
    with closing(connect()) as again:
        second = again.execute("SELECT COUNT(*) AS n FROM save_snapshots").fetchone()
        assert second["n"] == 3


def test_retrying_append_creates_one_record(tmp_path, monkeypatch):
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    client = TestClient(app)
    body = _snapshot(1, payload="only-once", key="retry-key")
    first = None
    for _ in range(100):
        response = client.post("/saves/shark-incremental/snapshots", json=body)
        assert response.status_code == 200
        first = first or response.json()
        assert response.json()["snapshot"]["snapshot_id"] == first["snapshot"]["snapshot_id"]
        assert response.json()["snapshot"]["sha256"] == first["snapshot"]["sha256"]
    assert first["created"] is True
    history = client.get("/saves/shark-incremental/snapshots").json()
    assert len(history) == 1
    assert history[0]["snapshot_payload"] == "only-once"


def test_thousand_snapshots_survive_duplicates_delay_and_reordering(tmp_path, monkeypatch):
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    client = TestClient(app)
    order = list(range(1000))
    random.Random(30).shuffle(order)
    lost = set()
    for index in order:
        response = client.post("/saves/shark-incremental/snapshots", json=_snapshot(index))
        assert response.status_code == 200
        assert response.json()["created"] is True
        if index % 17 == 0:
            lost.add(index)
            dropped = client.post("/saves/shark-incremental/snapshots", json=_snapshot(index))
            assert dropped.status_code == 200
            assert dropped.json()["created"] is False
            assert dropped.json()["snapshot"]["snapshot_id"] == response.json()["snapshot"]["snapshot_id"]
    for index in range(0, 1000, 40):
        duplicate = _snapshot(index)
        duplicate["snapshot_id"] = f"alias-{index}"
        duplicate["idempotency_key"] = f"alias-{index}"
        duplicate["captured_at_millis"] = 1
        aliased = client.post("/saves/shark-incremental/snapshots", json=duplicate)
        assert aliased.status_code == 200
        assert aliased.json()["created"] is False
        assert aliased.json()["snapshot"]["snapshot_payload"] == f"save-{index:04d}"
    history = client.get("/saves/shark-incremental/snapshots").json()
    assert len(history) == 1000
    assert {row["snapshot_payload"] for row in history} == {f"save-{index:04d}" for index in range(1000)}
    assert lost
    first = client.post(
        "/saves/shark-incremental/pointer",
        json={"expected_version": 0, "snapshot_id": "snap-10", "lineage_id": "legacy"},
    )
    assert first.status_code == 200
    assert first.json()["pointer"]["version"] == 1
    conflict = client.post(
        "/saves/shark-incremental/pointer",
        json={"expected_version": 0, "snapshot_id": "snap-11", "lineage_id": "legacy"},
    )
    assert conflict.status_code == 409
    assert conflict.json()["conflict"] == "version"
    assert len(client.get("/saves/shark-incremental/snapshots").json()) == 1000
    assert client.get("/saves").json()[0]["snapshot_payload"] == "save-0010"


def test_legacy_client_cannot_replace_protected_history(tmp_path, monkeypatch):
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    client = TestClient(app)
    original = "feature-9-protected"
    assert client.put("/saves/shark-incremental", json=_body(10, original)).status_code == 200
    regressed = client.put("/saves/shark-incremental", json=_body(99_000, "feature-0-regressed"))
    assert regressed.status_code == 200
    assert regressed.json()["snapshot_payload"] == original
    listed = client.get("/saves").json()
    assert listed[0]["snapshot_payload"] == original
    assert listed[0]["captured_at_millis"] == 10
    history = client.get("/saves/shark-incremental/snapshots").json()
    by_payload = {row["snapshot_payload"]: row for row in history}
    assert by_payload[original]["integrity"] == "accepted"
    assert by_payload["feature-0-regressed"]["integrity"] == "archived"
    pointer = client.get("/saves/shark-incremental/pointer").json()
    assert pointer["snapshot_payload"] == original
    assert pointer["version"] == 1
    refused = client.post(
        "/saves/shark-incremental/pointer",
        json={
            "expected_version": 1,
            "snapshot_id": by_payload["feature-0-regressed"]["snapshot_id"],
            "lineage_id": "legacy",
        },
    )
    assert refused.status_code == 409
    assert refused.json()["conflict"] == "integrity"
    assert client.get("/saves").json()[0]["snapshot_payload"] == original


def test_conflicting_lineage_and_key_do_not_erase_history(tmp_path, monkeypatch):
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    client = TestClient(app)
    stored = client.post("/saves/shark-incremental/snapshots", json=_snapshot(3, payload="kept", key="same-key"))
    assert stored.status_code == 200
    kept_id = stored.json()["snapshot"]["snapshot_id"]
    clash = _snapshot(3, payload="other-bytes", key="same-key")
    clash["snapshot_id"] = kept_id
    rejected = client.post("/saves/shark-incremental/snapshots", json=clash)
    assert rejected.status_code == 409
    assert rejected.json()["conflict"] == "idempotency-key"
    other_line = _snapshot(4, payload="branch")
    other_line["lineage_id"] = "lineage-2"
    assert client.post("/saves/shark-incremental/snapshots", json=other_line).status_code == 200
    activated = client.post(
        "/saves/shark-incremental/pointer",
        json={"expected_version": 0, "snapshot_id": kept_id, "lineage_id": "legacy"},
    )
    assert activated.status_code == 200
    switched = client.post(
        "/saves/shark-incremental/pointer",
        json={"expected_version": 1, "snapshot_id": other_line["snapshot_id"], "lineage_id": "lineage-2"},
    )
    assert switched.status_code == 409
    assert switched.json()["conflict"] == "lineage"
    assert client.get("/saves/shark-incremental/pointer").json()["snapshot_payload"] == "kept"
    payloads = {row["snapshot_payload"] for row in client.get("/saves/shark-incremental/snapshots").json()}
    assert payloads == {"kept", "branch"}


def test_concurrent_appends_keep_every_payload(tmp_path, monkeypatch):
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    with closing(connect()) as connection:
        pass
    errors = []

    def worker(worker_id: int) -> None:
        try:
            for index in range(25):
                payload = f"w{worker_id}-{index}"
                with closing(connect()) as connection:
                    with immediate(connection):
                        result = append_snapshot(
                            connection,
                            game_id="shark-incremental",
                            snapshot_id=f"w{worker_id}-{index}",
                            lineage_id="legacy",
                            payload=payload,
                            captured_at=index,
                            source="phone",
                            integrity="accepted",
                            idempotency_key=f"w{worker_id}-{index}",
                            progression_json=None,
                            profile_name="hub",
                            session_identity="session",
                            last_played_at=index,
                            resume_token=None,
                            recovery_state=None,
                        )
                assert result["conflict"] is None
                with closing(connect()) as connection:
                    with immediate(connection):
                        again = append_snapshot(
                            connection,
                            game_id="shark-incremental",
                            snapshot_id=f"w{worker_id}-{index}",
                            lineage_id="legacy",
                            payload=payload,
                            captured_at=index,
                            source="phone",
                            integrity="accepted",
                            idempotency_key=f"w{worker_id}-{index}",
                            progression_json=None,
                            profile_name="hub",
                            session_identity="session",
                            last_played_at=index,
                            resume_token=None,
                            recovery_state=None,
                        )
                assert again["created"] is False
        except Exception as exc:  # noqa: BLE001 - the test records worker failures
            errors.append(exc)

    threads = [threading.Thread(target=worker, args=(worker_id,)) for worker_id in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert errors == []
    with closing(connect()) as connection:
        rows = connection.execute("SELECT payload FROM save_snapshots").fetchall()
    assert len(rows) == 200
    assert {row["payload"] for row in rows} == {f"w{worker}-{index}" for worker in range(8) for index in range(25)}
