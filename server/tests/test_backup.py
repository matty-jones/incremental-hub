import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi.testclient import TestClient

from app.backup import NAME_RE, create_backup, verify_backup
from app.history import payload_sha256
from app.main import app


def _client(tmp_path, monkeypatch) -> TestClient:
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    monkeypatch.setenv("HUB_BACKUP_DIR", str(tmp_path / "backups"))
    return TestClient(app)


def _body(payload: str) -> dict:
    return {
        "snapshot_payload": payload,
        "captured_at_millis": 40,
        "profile_name": "hub-game-shark",
        "session_identity": "session-1",
        "last_played_at_millis": 40,
        "resume_token": None,
        "recovery_state": None,
    }


def test_backup_restores_with_integrity_and_snapshot_hashes(tmp_path, monkeypatch):
    client = _client(tmp_path, monkeypatch)
    payload = "feature-9-" + ("Z" * 4000) + "\nkept"
    assert client.put("/saves/shark-incremental", json=_body(payload)).status_code == 200
    source = Path(tmp_path / "saves.db")
    before = sqlite3.connect(source).execute(
        "SELECT snapshot_payload FROM saves WHERE game_id = 'shark-incremental'"
    ).fetchone()[0]
    copied = create_backup(datetime(2026, 10, 30, 12, tzinfo=timezone.utc))
    after = sqlite3.connect(source).execute(
        "SELECT snapshot_payload FROM saves WHERE game_id = 'shark-incremental'"
    ).fetchone()[0]
    assert after == before == payload
    assert verify_backup(copied) == 1
    assert copied.parent == tmp_path / "backups"
    assert source.resolve() != copied.resolve()

    restored = tmp_path / "restored.db"
    restored.write_bytes(copied.read_bytes())
    monkeypatch.setenv("HUB_DB_PATH", str(restored))
    opened = TestClient(app)
    listed = opened.get("/saves")
    assert listed.status_code == 200
    assert listed.json()[0]["snapshot_payload"] == payload
    health = opened.get("/health")
    assert health.status_code == 200
    assert health.json()["status"] == "ok"
    assert health.json()["backup"]["last_backup"] == copied.name
    assert health.json()["snapshot_count"] == 1

    raw = sqlite3.connect(restored)
    raw.row_factory = sqlite3.Row
    integrity = raw.execute("PRAGMA integrity_check").fetchone()[0]
    row = raw.execute("SELECT payload, sha256 FROM save_snapshots").fetchone()
    raw.close()
    assert integrity == "ok"
    assert row["payload"] == payload
    assert row["sha256"] == payload_sha256(payload)


def test_retention_keeps_thirty_daily_and_twelve_weekly(tmp_path, monkeypatch):
    _client(tmp_path, monkeypatch)
    client = TestClient(app)
    assert client.put("/saves/shark-incremental", json=_body("kept")).status_code == 200
    now = datetime(2026, 10, 30, 18, tzinfo=timezone.utc)
    earlier_today = now - timedelta(hours=2)
    kept_days = [
        now,
        now - timedelta(days=10),
        now - timedelta(days=29),
        now - timedelta(days=40),
    ]
    dropped_days = [
        earlier_today,
        now - timedelta(days=100),
        now - timedelta(days=200),
    ]
    for stamp in sorted(kept_days + dropped_days):
        create_backup(stamp)
    names = {path.name for path in (tmp_path / "backups").glob("hub-*.db")}
    for stamp in kept_days:
        assert f"hub-{stamp.strftime('%Y%m%dT%H%M%SZ')}.db" in names
    for stamp in dropped_days:
        assert f"hub-{stamp.strftime('%Y%m%dT%H%M%SZ')}.db" not in names
    assert (tmp_path / "saves.db").is_file()
    assert not any(path.name.startswith("saves") for path in (tmp_path / "backups").iterdir())


def test_failed_verification_does_not_publish_a_backup(tmp_path, monkeypatch):
    client = _client(tmp_path, monkeypatch)
    assert client.put("/saves/shark-incremental", json=_body("kept")).status_code == 200
    monkeypatch.setattr("app.backup.verify_backup", lambda path: (_ for _ in ()).throw(RuntimeError("bad pages")))
    try:
        create_backup(datetime(2026, 10, 30, 12, tzinfo=timezone.utc))
        raised = False
    except RuntimeError as exc:
        raised = "bad pages" in str(exc)
    assert raised
    assert list((tmp_path / "backups").glob("hub-*.db")) == []
    assert list((tmp_path / "backups").glob(".partial-*.db")) == []
    health = client.get("/health")
    assert health.json()["backup"]["last_success_at"] is None
    assert "bad pages" in health.json()["backup"]["last_error"]
    assert client.get("/saves").json()[0]["snapshot_payload"] == "kept"


def test_backup_unit_uses_the_online_tool_and_the_live_path(tmp_path):
    root = Path(__file__).resolve().parents[1]
    service = (root / "incremental-hub-backup.service").read_text(encoding="utf-8")
    timer = (root / "incremental-hub-backup.timer").read_text(encoding="utf-8")
    assert "python -m app.backup" in service
    assert "HUB_DB_PATH=@ROOT@/server/db/saves.db" in service
    assert "HUB_BACKUP_DIR=@ROOT@/server/backups" in service
    assert "cp " not in service
    assert "OnCalendar=daily" in timer
    assert NAME_RE.match("hub-20261030T120000Z.db")
