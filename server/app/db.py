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
    connection = sqlite3.connect(path, timeout=5)
    connection.row_factory = sqlite3.Row
    connection.isolation_level = None
    from .history import ensure_schema

    ensure_schema(connection)
    return connection


def row_to_dict(row: sqlite3.Row) -> dict:
    return {key: row[key] for key in row.keys()}
