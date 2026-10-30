"""Consistent copies of the workstation database.

The copy is made with SQLite's online backup API, then checked with
``PRAGMA integrity_check`` and a hash of every stored snapshot payload.
Retention keeps the latest file in each populated UTC day for 30 days and
each populated ISO week for 12 weeks. The live ``HUB_DB_PATH`` file is not
deleted or replaced.
"""

from __future__ import annotations

import json
import os
import re
import sqlite3
import sys
from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .db import SERVER_ROOT, connect, db_path
from .history import payload_sha256

DAILY_KEEP = 30
WEEKLY_KEEP = 12
NAME_RE = re.compile(r"^hub-(\d{8}T\d{6}Z)\.db$")


class BackupError(RuntimeError):
    pass


def backup_dir() -> Path:
    return Path(os.environ.get("HUB_BACKUP_DIR", SERVER_ROOT / "backups"))


def status_path() -> Path:
    return backup_dir() / "backup-status.json"


def create_backup(now: datetime | None = None) -> Path:
    moment = _as_utc(now or datetime.now(timezone.utc))
    source = db_path()
    directory = backup_dir()
    directory.mkdir(parents=True, exist_ok=True)
    _clear_partials(directory)
    if not source.is_file():
        with closing(connect()):
            pass
    stamp = moment.strftime("%Y%m%dT%H%M%SZ")
    final = directory / f"hub-{stamp}.db"
    partial = directory / f".partial-{stamp}.db"
    try:
        _copy_online(source, partial)
        verified = verify_backup(partial)
        os.replace(partial, final)
        prune_backups(directory, moment)
        _write_status(
            {
                "last_success_at": moment.isoformat(),
                "last_failure_at": _previous("last_failure_at"),
                "last_error": None,
                "last_backup": final.name,
                "verified_snapshots": verified,
                "retention": {"daily": DAILY_KEEP, "weekly": WEEKLY_KEEP},
            }
        )
    except Exception as exc:
        partial.unlink(missing_ok=True)
        _write_status(
            {
                "last_success_at": _previous("last_success_at"),
                "last_failure_at": moment.isoformat(),
                "last_error": str(exc),
                "last_backup": _previous("last_backup"),
                "verified_snapshots": _previous("verified_snapshots"),
                "retention": {"daily": DAILY_KEEP, "weekly": WEEKLY_KEEP},
            }
        )
        raise
    return final


def verify_backup(path: Path) -> int:
    connection = sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise BackupError(f"integrity_check returned {integrity}")
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).fetchall()
        }
        if "save_snapshots" not in tables:
            return 0
        rows = connection.execute(
            "SELECT snapshot_id, sha256, payload FROM save_snapshots"
        ).fetchall()
        for row in rows:
            digest = payload_sha256(row["payload"])
            if digest != row["sha256"]:
                raise BackupError(f"snapshot {row['snapshot_id']} hash does not match its payload")
        return len(rows)
    finally:
        connection.close()


def prune_backups(directory: Path, now: datetime) -> list[Path]:
    moment = _as_utc(now)
    files = []
    for path in directory.iterdir():
        parsed = _stamp(path)
        if parsed is None:
            continue
        files.append((parsed, path))
    kept = _keep(files, moment)
    removed = []
    for _, path in files:
        if path in kept:
            continue
        if path.parent.resolve() != directory.resolve():
            continue
        path.unlink()
        removed.append(path)
    return removed


def read_status() -> dict:
    path = status_path()
    empty = {
        "last_success_at": None,
        "last_failure_at": None,
        "last_error": None,
        "last_backup": None,
        "verified_snapshots": None,
        "retention": {"daily": DAILY_KEEP, "weekly": WEEKLY_KEEP},
    }
    if not path.is_file():
        return empty
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        empty["last_error"] = "backup status is unreadable"
        return empty
    empty.update({key: loaded.get(key) for key in empty if key != "retention"})
    empty["retention"] = {"daily": DAILY_KEEP, "weekly": WEEKLY_KEEP}
    return empty


def _copy_online(source: Path, destination: Path) -> None:
    if destination.exists():
        destination.unlink()
    src = sqlite3.connect(source)
    dst = sqlite3.connect(destination)
    try:
        with dst:
            src.backup(dst)
    finally:
        src.close()
        dst.close()


def _keep(files: list[tuple[datetime, Path]], now: datetime) -> set[Path]:
    daily_floor = now.date() - timedelta(days=DAILY_KEEP - 1)
    week_floor = _week_start(now) - timedelta(weeks=WEEKLY_KEEP - 1)
    daily: dict = {}
    weekly: dict = {}
    for stamp, path in files:
        if stamp.date() >= daily_floor:
            current = daily.get(stamp.date())
            if current is None or stamp > current[0]:
                daily[stamp.date()] = (stamp, path)
        if _week_start(stamp) >= week_floor:
            key = stamp.isocalendar()[:2]
            current = weekly.get(key)
            if current is None or stamp > current[0]:
                weekly[key] = (stamp, path)
    kept = {item[1] for item in daily.values()}
    kept.update(item[1] for item in weekly.values())
    return kept


def _week_start(moment: datetime) -> datetime.date:
    moment = _as_utc(moment)
    return moment.date() - timedelta(days=moment.weekday())


def _stamp(path: Path) -> datetime | None:
    match = NAME_RE.match(path.name)
    if match is None or not path.is_file():
        return None
    return datetime.strptime(match.group(1), "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)


def _as_utc(moment: datetime) -> datetime:
    if moment.tzinfo is None:
        return moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(timezone.utc)


def _clear_partials(directory: Path) -> None:
    for path in directory.glob(".partial-*.db"):
        if path.is_file() and path.parent.resolve() == directory.resolve():
            path.unlink()


def _previous(key: str):
    return read_status().get(key)


def _write_status(payload: dict) -> None:
    directory = backup_dir()
    directory.mkdir(parents=True, exist_ok=True)
    target = status_path()
    temporary = directory / ".backup-status.json.tmp"
    temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, target)


def main() -> int:
    try:
        print(create_backup())
    except Exception as exc:
        print(exc, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
