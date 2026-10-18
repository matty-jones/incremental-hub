from __future__ import annotations

import os
import sqlite3
from pathlib import Path

SERVER_ROOT = Path(__file__).resolve().parent.parent


def db_path() -> Path:
    return Path(os.environ.get("HUB_DB_PATH", SERVER_ROOT / "db" / "saves.db"))


def apk_dir() -> Path:
    return Path(os.environ.get("HUB_APK_DIR", SERVER_ROOT / "apks"))


def connect() -> sqlite3.Connection:
    path = db_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path)
    connection.row_factory = sqlite3.Row
    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS saves (
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
    connection.commit()
    return connection


def row_to_dict(row: sqlite3.Row) -> dict:
    return {key: row[key] for key in row.keys()}
