import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.backup import NAME_RE, BackupError, backup_before_migration, create_backup, migrate_with_backups, verify_backup
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
    assert "backup migrate" not in service
    assert "HUB_DB_PATH=@ROOT@/server/db/saves.db" in service
    assert "HUB_BACKUP_DIR=@ROOT@/server/backups" in service
    assert "cp " not in service
    assert "OnCalendar=daily" in timer
    assert NAME_RE.match("hub-20261030T120000Z.db")


def _legacy_writer(path: Path, rows: list[tuple[str, str]]) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA wal_autocheckpoint=0")
    connection.execute(
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
    connection.executemany(
        "INSERT INTO saves VALUES (?, ?, 10, 'hub', 'session', 10, NULL, NULL)",
        rows,
    )
    connection.commit()
    return connection


def _tables(path: Path) -> set[str]:
    connection = sqlite3.connect(path)
    try:
        return {
            row[0]
            for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")
        }
    finally:
        connection.close()


def _saved_payload(path: Path, game_id: str) -> str:
    connection = sqlite3.connect(path)
    try:
        return connection.execute(
            "SELECT snapshot_payload FROM saves WHERE game_id = ?",
            (game_id,),
        ).fetchone()[0]
    finally:
        connection.close()


def test_pre_migration_backup_survives_wal_and_restores_independently(tmp_path, monkeypatch):
    payload = "feature-9-\u0000-\u00ff-\U0001f4a0"
    other = "trimps-\u0000-kept"
    database = tmp_path / "saves.db"
    monkeypatch.setenv("HUB_DB_PATH", str(database))
    monkeypatch.setenv("HUB_BACKUP_DIR", str(tmp_path / "backups"))
    writer = _legacy_writer(database, [("shark-incremental", payload), ("trimps", other)])
    wal = Path(str(database) + "-wal")
    assert wal.is_file() and wal.stat().st_size > 0
    pre = backup_before_migration(datetime(2026, 10, 30, 12, tzinfo=timezone.utc))
    writer.close()
    assert "save_snapshots" not in _tables(database)
    assert _saved_payload(database, "shark-incremental") == payload
    assert verify_backup(pre) == 2
    assert "save_snapshots" not in _tables(pre)
    pre_bytes = pre.read_bytes()

    migrated_pre, post = migrate_with_backups(datetime(2026, 10, 30, 13, tzinfo=timezone.utc))
    assert pre.read_bytes() == pre_bytes
    assert migrated_pre.is_file() and post.is_file()
    assert pre.name.startswith("pre-migrate-")
    assert post.name.startswith("hub-")
    assert _saved_payload(pre, "shark-incremental") == payload
    assert _saved_payload(migrated_pre, "shark-incremental") == payload

    restored_pre = tmp_path / "restored-pre.db"
    restored_pre.write_bytes(pre.read_bytes())
    assert sqlite3.connect(restored_pre).execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    assert _saved_payload(restored_pre, "shark-incremental") == payload
    assert _saved_payload(restored_pre, "trimps") == other
    assert payload_sha256(_saved_payload(restored_pre, "shark-incremental")) == payload_sha256(payload)

    restored_post = tmp_path / "restored-post.db"
    restored_post.write_bytes(post.read_bytes())
    assert verify_backup(restored_post) == 2
    monkeypatch.setenv("HUB_DB_PATH", str(restored_post))
    opened = TestClient(app)
    listed = opened.get("/saves")
    assert listed.status_code == 200
    by_game = {row["game_id"]: row["snapshot_payload"] for row in listed.json()}
    assert by_game["shark-incremental"] == payload
    assert by_game["trimps"] == other
    raw = sqlite3.connect(restored_post)
    raw.row_factory = sqlite3.Row
    stored = raw.execute(
        "SELECT payload, sha256 FROM save_snapshots WHERE game_id = 'shark-incremental'"
    ).fetchone()
    raw.close()
    assert stored["payload"] == payload
    assert stored["sha256"] == payload_sha256(payload)
    assert pre.read_bytes() == pre_bytes
    assert list((tmp_path / "backups").glob("pre-migrate-*.db"))


def test_interrupted_migration_keeps_the_pre_backup(tmp_path, monkeypatch):
    payload = "feature-9-before-migration"
    database = tmp_path / "saves.db"
    monkeypatch.setenv("HUB_DB_PATH", str(database))
    monkeypatch.setenv("HUB_BACKUP_DIR", str(tmp_path / "backups"))
    writer = _legacy_writer(database, [("shark-incremental", payload)])
    writer.close()

    def boom(_connection):
        raise RuntimeError("migration interrupted")

    monkeypatch.setattr("app.history._migrate_legacy_rows", boom)
    with pytest.raises(RuntimeError, match="migration interrupted"):
        migrate_with_backups(datetime(2026, 10, 30, 12, tzinfo=timezone.utc))
    backups = list((tmp_path / "backups").glob("pre-migrate-*.db"))
    assert len(backups) == 1
    assert list((tmp_path / "backups").glob("hub-*.db")) == []
    assert list((tmp_path / "backups").glob(".partial-*.db")) == []
    assert "save_snapshots" not in _tables(database)
    assert _saved_payload(database, "shark-incremental") == payload
    assert _saved_payload(backups[0], "shark-incremental") == payload


def test_post_migration_backup_failure_keeps_the_original_copy(tmp_path, monkeypatch):
    payload = "feature-9-post-backup"
    database = tmp_path / "saves.db"
    monkeypatch.setenv("HUB_DB_PATH", str(database))
    monkeypatch.setenv("HUB_BACKUP_DIR", str(tmp_path / "backups"))
    writer = _legacy_writer(database, [("shark-incremental", payload)])
    writer.close()

    def boom(*_args, **_kwargs):
        raise BackupError("post backup failed")

    monkeypatch.setattr("app.backup.create_backup", boom)
    with pytest.raises(BackupError, match="post backup failed"):
        migrate_with_backups(datetime(2026, 10, 30, 12, tzinfo=timezone.utc))
    backups = list((tmp_path / "backups").glob("pre-migrate-*.db"))
    assert len(backups) == 1
    assert list((tmp_path / "backups").glob("hub-*.db")) == []
    assert _saved_payload(backups[0], "shark-incremental") == payload
    assert "save_snapshots" not in _tables(backups[0])
    assert "save_snapshots" in _tables(database)
    assert _saved_payload(database, "shark-incremental") == payload


def test_pre_migration_copy_failure_does_not_migrate(tmp_path, monkeypatch):
    payload = "feature-9-copy-failed"
    database = tmp_path / "saves.db"
    monkeypatch.setenv("HUB_DB_PATH", str(database))
    monkeypatch.setenv("HUB_BACKUP_DIR", str(tmp_path / "backups"))
    writer = _legacy_writer(database, [("shark-incremental", payload)])
    writer.close()

    def boom(*_args, **_kwargs):
        raise BackupError("copy failed")

    monkeypatch.setattr("app.backup._copy_online", boom)
    with pytest.raises(BackupError, match="copy failed"):
        migrate_with_backups(datetime(2026, 10, 30, 12, tzinfo=timezone.utc))
    assert list((tmp_path / "backups").glob("pre-migrate-*.db")) == []
    assert list((tmp_path / "backups").glob("hub-*.db")) == []
    assert "save_snapshots" not in _tables(database)
    assert _saved_payload(database, "shark-incremental") == payload
