"""Immutable workstation snapshot history and a separate active pointer.

The legacy ``saves`` row stays as the mutable mirror that old clients read.
Migration copies each existing payload into ``save_snapshots`` and does not
update or delete that row. A later pointer move updates the mirror only after
the immutable copy exists.
"""

from __future__ import annotations

import hashlib
import sqlite3
import time

INTEGRITY_ACCEPTED = "accepted"
INTEGRITY_ARCHIVED = "archived"
INTEGRITY_QUARANTINE = "quarantine"
INTEGRITIES = {INTEGRITY_ACCEPTED, INTEGRITY_ARCHIVED, INTEGRITY_QUARANTINE}

LEGACY_LINEAGE = "legacy"
INDEX_PAGE_MAX = 500


def now_millis() -> int:
    return time.time_ns() // 1_000_000


def payload_sha256(payload: str) -> str:
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def valid_token(value: str, *, max_len: int = 200) -> bool:
    if not value or len(value) > max_len:
        return False
    if value in {".", ".."} or "/" in value:
        return False
    return all(ord(char) >= 32 for char in value)


def ensure_schema(connection: sqlite3.Connection) -> None:
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA busy_timeout=5000")
    connection.execute("PRAGMA foreign_keys=ON")
    connection.execute("BEGIN IMMEDIATE")
    try:
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
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS save_snapshots (
                snapshot_id TEXT PRIMARY KEY,
                game_id TEXT NOT NULL,
                lineage_id TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                payload TEXT NOT NULL,
                captured_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                progression_json TEXT,
                integrity TEXT NOT NULL,
                profile_name TEXT NOT NULL DEFAULT '',
                session_identity TEXT NOT NULL DEFAULT '',
                last_played_at_millis INTEGER NOT NULL DEFAULT 0,
                resume_token TEXT,
                recovery_state TEXT,
                UNIQUE (game_id, lineage_id, sha256)
            )
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS snapshot_keys (
                idempotency_key TEXT PRIMARY KEY,
                snapshot_id TEXT NOT NULL,
                payload_sha256 TEXT NOT NULL
            )
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS current_pointers (
                game_id TEXT PRIMARY KEY,
                lineage_id TEXT NOT NULL,
                snapshot_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """
        )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_save_snapshots_game
            ON save_snapshots (game_id, lineage_id, captured_at)
            """
        )
        connection.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_save_snapshots_received
            ON save_snapshots (game_id, received_at, snapshot_id)
            """
        )
        _migrate_legacy_rows(connection)
        connection.commit()
    except Exception:
        connection.rollback()
        raise


def _migrate_legacy_rows(connection: sqlite3.Connection) -> None:
    rows = connection.execute("SELECT * FROM saves").fetchall()
    for row in rows:
        payload = row["snapshot_payload"]
        sha = payload_sha256(payload)
        snapshot_id = f"migrated:{row['game_id']}:{sha}"
        received = int(row["captured_at_millis"])
        connection.execute(
            """
            INSERT OR IGNORE INTO save_snapshots (
                snapshot_id, game_id, lineage_id, sha256, payload, captured_at,
                received_at, source, progression_json, integrity, profile_name,
                session_identity, last_played_at_millis, resume_token, recovery_state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'legacy-migration', NULL, ?, ?, ?, ?, ?, ?)
            """,
            (
                snapshot_id,
                row["game_id"],
                LEGACY_LINEAGE,
                sha,
                payload,
                received,
                received,
                INTEGRITY_ACCEPTED,
                row["profile_name"],
                row["session_identity"],
                int(row["last_played_at_millis"]),
                row["resume_token"],
                row["recovery_state"],
            ),
        )
        stored = connection.execute(
            """
            SELECT snapshot_id FROM save_snapshots
            WHERE game_id = ? AND lineage_id = ? AND sha256 = ? AND payload = ?
            """,
            (row["game_id"], LEGACY_LINEAGE, sha, payload),
        ).fetchone()
        if stored is None:
            raise RuntimeError("legacy migration did not keep the payload")
        connection.execute(
            """
            INSERT INTO current_pointers (game_id, lineage_id, snapshot_id, version, updated_at)
            SELECT ?, ?, ?, 1, ?
            WHERE NOT EXISTS (SELECT 1 FROM current_pointers WHERE game_id = ?)
            """,
            (row["game_id"], LEGACY_LINEAGE, stored["snapshot_id"], received, row["game_id"]),
        )


def snapshot_by_id(connection: sqlite3.Connection, snapshot_id: str) -> sqlite3.Row | None:
    return connection.execute(
        "SELECT * FROM save_snapshots WHERE snapshot_id = ?",
        (snapshot_id,),
    ).fetchone()


def pointer_row(connection: sqlite3.Connection, game_id: str) -> sqlite3.Row | None:
    return connection.execute(
        "SELECT * FROM current_pointers WHERE game_id = ?",
        (game_id,),
    ).fetchone()


def save_row(connection: sqlite3.Connection, game_id: str) -> sqlite3.Row | None:
    return connection.execute(
        "SELECT * FROM saves WHERE game_id = ?",
        (game_id,),
    ).fetchone()


def list_snapshots(connection: sqlite3.Connection, game_id: str) -> list[sqlite3.Row]:
    return connection.execute(
        """
        SELECT * FROM save_snapshots
        WHERE game_id = ?
        ORDER BY received_at ASC, snapshot_id ASC
        """,
        (game_id,),
    ).fetchall()


def list_snapshot_index(
    connection: sqlite3.Connection,
    game_id: str,
    after_received_at: int,
    after_snapshot_id: str,
    limit: int,
) -> list[sqlite3.Row]:
    page = max(1, min(int(limit), INDEX_PAGE_MAX))
    return connection.execute(
        """
        SELECT snapshot_id, game_id, lineage_id, sha256, captured_at, received_at,
               source, integrity
        FROM save_snapshots
        WHERE game_id = ?
          AND (
                received_at > ?
                OR (received_at = ? AND snapshot_id > ?)
              )
        ORDER BY received_at ASC, snapshot_id ASC
        LIMIT ?
        """,
        (game_id, after_received_at, after_received_at, after_snapshot_id, page),
    ).fetchall()


def public_snapshot_metadata(row: sqlite3.Row) -> dict:
    return {
        "snapshot_id": row["snapshot_id"],
        "game_id": row["game_id"],
        "lineage_id": row["lineage_id"],
        "sha256": row["sha256"],
        "captured_at_millis": row["captured_at"],
        "received_at_millis": row["received_at"],
        "source": row["source"],
        "integrity": row["integrity"],
    }


def public_snapshot(row: sqlite3.Row) -> dict:
    return {
        "snapshot_id": row["snapshot_id"],
        "game_id": row["game_id"],
        "lineage_id": row["lineage_id"],
        "sha256": row["sha256"],
        "snapshot_payload": row["payload"],
        "captured_at_millis": row["captured_at"],
        "received_at_millis": row["received_at"],
        "source": row["source"],
        "progression_json": row["progression_json"],
        "integrity": row["integrity"],
        "profile_name": row["profile_name"],
        "session_identity": row["session_identity"],
        "last_played_at_millis": row["last_played_at_millis"],
        "resume_token": row["resume_token"],
        "recovery_state": row["recovery_state"],
    }


def public_pointer(connection: sqlite3.Connection, game_id: str) -> dict | None:
    pointer = pointer_row(connection, game_id)
    if pointer is None:
        return None
    snapshot = snapshot_by_id(connection, pointer["snapshot_id"])
    if snapshot is None:
        return None
    body = public_snapshot(snapshot)
    body["version"] = pointer["version"]
    body["updated_at_millis"] = pointer["updated_at"]
    return body


def public_save(row: sqlite3.Row) -> dict:
    return {
        "game_id": row["game_id"],
        "snapshot_payload": row["snapshot_payload"],
        "captured_at_millis": row["captured_at_millis"],
        "profile_name": row["profile_name"],
        "session_identity": row["session_identity"],
        "last_played_at_millis": row["last_played_at_millis"],
        "resume_token": row["resume_token"],
        "recovery_state": row["recovery_state"],
    }


def append_snapshot(
    connection: sqlite3.Connection,
    *,
    game_id: str,
    snapshot_id: str,
    lineage_id: str,
    payload: str,
    captured_at: int,
    source: str,
    integrity: str,
    idempotency_key: str,
    progression_json: str | None,
    profile_name: str,
    session_identity: str,
    last_played_at: int,
    resume_token: str | None,
    recovery_state: str | None,
    received_at: int | None = None,
) -> dict:
    if integrity not in INTEGRITIES:
        raise ValueError("integrity")
    sha = payload_sha256(payload)
    received = now_millis() if received_at is None else received_at
    existing_key = connection.execute(
        "SELECT * FROM snapshot_keys WHERE idempotency_key = ?",
        (idempotency_key,),
    ).fetchone()
    if existing_key is not None:
        if existing_key["payload_sha256"] != sha:
            snapshot = snapshot_by_id(connection, existing_key["snapshot_id"])
            return {
                "created": False,
                "conflict": "idempotency-key",
                "snapshot": None if snapshot is None else public_snapshot(snapshot),
            }
        snapshot = snapshot_by_id(connection, existing_key["snapshot_id"])
        if snapshot is None:
            raise RuntimeError("idempotency key has no snapshot")
        _promote_archived(connection, snapshot, integrity)
        refreshed = snapshot_by_id(connection, snapshot["snapshot_id"])
        return {"created": False, "conflict": None, "snapshot": public_snapshot(refreshed)}

    existing = connection.execute(
        """
        SELECT * FROM save_snapshots
        WHERE game_id = ? AND lineage_id = ? AND sha256 = ?
        """,
        (game_id, lineage_id, sha),
    ).fetchone()
    if existing is not None:
        if existing["payload"] != payload:
            return {
                "created": False,
                "conflict": "payload-hash",
                "snapshot": public_snapshot(existing),
            }
        _bind_key(connection, idempotency_key, existing["snapshot_id"], sha)
        _promote_archived(connection, existing, integrity)
        refreshed = snapshot_by_id(connection, existing["snapshot_id"])
        return {"created": False, "conflict": None, "snapshot": public_snapshot(refreshed)}

    connection.execute(
        """
        INSERT INTO save_snapshots (
            snapshot_id, game_id, lineage_id, sha256, payload, captured_at, received_at,
            source, progression_json, integrity, profile_name, session_identity,
            last_played_at_millis, resume_token, recovery_state
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            snapshot_id,
            game_id,
            lineage_id,
            sha,
            payload,
            captured_at,
            received,
            source,
            progression_json,
            integrity,
            profile_name,
            session_identity,
            last_played_at,
            resume_token,
            recovery_state,
        ),
    )
    _bind_key(connection, idempotency_key, snapshot_id, sha)
    stored = snapshot_by_id(connection, snapshot_id)
    return {"created": True, "conflict": None, "snapshot": public_snapshot(stored)}


def _bind_key(
    connection: sqlite3.Connection,
    idempotency_key: str,
    snapshot_id: str,
    sha: str,
) -> None:
    connection.execute(
        """
        INSERT OR IGNORE INTO snapshot_keys (idempotency_key, snapshot_id, payload_sha256)
        VALUES (?, ?, ?)
        """,
        (idempotency_key, snapshot_id, sha),
    )


def _promote_archived(connection: sqlite3.Connection, snapshot: sqlite3.Row, integrity: str) -> None:
    if integrity != INTEGRITY_ACCEPTED or snapshot["integrity"] != INTEGRITY_ARCHIVED:
        return
    connection.execute(
        """
        UPDATE save_snapshots
        SET integrity = ?
        WHERE snapshot_id = ? AND integrity = ? AND payload = ?
        """,
        (INTEGRITY_ACCEPTED, snapshot["snapshot_id"], INTEGRITY_ARCHIVED, snapshot["payload"]),
    )


def propose_pointer(
    connection: sqlite3.Connection,
    *,
    game_id: str,
    expected_version: int,
    snapshot_id: str,
    lineage_id: str,
    updated_at: int | None = None,
) -> dict:
    snapshot = snapshot_by_id(connection, snapshot_id)
    pointer = pointer_row(connection, game_id)
    if snapshot is None or snapshot["game_id"] != game_id:
        return {"ok": False, "conflict": "missing-snapshot", "pointer": _pointer_body(connection, pointer)}
    if snapshot["lineage_id"] != lineage_id:
        return {"ok": False, "conflict": "lineage", "pointer": _pointer_body(connection, pointer)}
    if snapshot["integrity"] != INTEGRITY_ACCEPTED:
        return {"ok": False, "conflict": "integrity", "pointer": _pointer_body(connection, pointer)}
    if pointer is None:
        if expected_version != 0:
            return {"ok": False, "conflict": "version", "pointer": None}
        version = 1
    else:
        if pointer["lineage_id"] != lineage_id:
            return {"ok": False, "conflict": "lineage", "pointer": _pointer_body(connection, pointer)}
        if pointer["version"] != expected_version:
            return {"ok": False, "conflict": "version", "pointer": _pointer_body(connection, pointer)}
        version = pointer["version"] + 1
    when = now_millis() if updated_at is None else updated_at
    connection.execute(
        """
        INSERT INTO current_pointers (game_id, lineage_id, snapshot_id, version, updated_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(game_id) DO UPDATE SET
            lineage_id = excluded.lineage_id,
            snapshot_id = excluded.snapshot_id,
            version = excluded.version,
            updated_at = excluded.updated_at
        """,
        (game_id, lineage_id, snapshot_id, version, when),
    )
    _mirror_save(connection, snapshot)
    moved = pointer_row(connection, game_id)
    return {"ok": True, "conflict": None, "pointer": _pointer_body(connection, moved)}


def _pointer_body(connection: sqlite3.Connection, pointer: sqlite3.Row | None) -> dict | None:
    if pointer is None:
        return None
    return public_pointer(connection, pointer["game_id"])


def _mirror_save(connection: sqlite3.Connection, snapshot: sqlite3.Row) -> None:
    connection.execute(
        """
        INSERT INTO saves (
            game_id, snapshot_payload, captured_at_millis, profile_name,
            session_identity, last_played_at_millis, resume_token, recovery_state
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(game_id) DO UPDATE SET
            snapshot_payload = excluded.snapshot_payload,
            captured_at_millis = excluded.captured_at_millis,
            profile_name = excluded.profile_name,
            session_identity = excluded.session_identity,
            last_played_at_millis = excluded.last_played_at_millis,
            resume_token = excluded.resume_token,
            recovery_state = excluded.recovery_state
        """,
        (
            snapshot["game_id"],
            snapshot["payload"],
            snapshot["captured_at"],
            snapshot["profile_name"],
            snapshot["session_identity"],
            snapshot["last_played_at_millis"],
            snapshot["resume_token"],
            snapshot["recovery_state"],
        ),
    )


def apply_legacy_put(connection: sqlite3.Connection, game_id: str, fields: dict) -> tuple[int, dict]:
    payload = fields["snapshot_payload"]
    sha = payload_sha256(payload)
    captured_at = int(fields["captured_at_millis"])
    pointer = pointer_row(connection, game_id)
    existing = save_row(connection, game_id)
    establishing = pointer is None and existing is None
    appended = append_snapshot(
        connection,
        game_id=game_id,
        snapshot_id=f"legacy:{game_id}:{sha}",
        lineage_id=LEGACY_LINEAGE,
        payload=payload,
        captured_at=captured_at,
        source="legacy-put",
        integrity=INTEGRITY_ACCEPTED if establishing else INTEGRITY_ARCHIVED,
        idempotency_key=f"legacy:{game_id}:{LEGACY_LINEAGE}:{sha}",
        progression_json=None,
        profile_name=fields["profile_name"],
        session_identity=fields["session_identity"],
        last_played_at=int(fields["last_played_at_millis"]),
        resume_token=fields.get("resume_token"),
        recovery_state=fields.get("recovery_state"),
        received_at=now_millis(),
    )
    if appended["conflict"] == "payload-hash":
        active = public_save(existing) if existing is not None else public_pointer(connection, game_id)
        return 409, active or {}
    if establishing:
        snapshot = snapshot_by_id(connection, appended["snapshot"]["snapshot_id"])
        propose_pointer(
            connection,
            game_id=game_id,
            expected_version=0,
            snapshot_id=snapshot["snapshot_id"],
            lineage_id=LEGACY_LINEAGE,
        )
        stored = save_row(connection, game_id)
        return 200, public_save(stored)
    active = existing if existing is not None else save_row(connection, game_id)
    if active is None:
        raise RuntimeError("legacy save has a pointer and no mirror row")
    body = public_save(active)
    if captured_at < int(active["captured_at_millis"]):
        return 409, body
    body["pointer_advanced"] = False
    body["history_snapshot_id"] = appended["snapshot"]["snapshot_id"]
    return 200, body


class immediate:
    """Context manager around one immediate write transaction."""

    def __init__(self, connection: sqlite3.Connection):
        self.connection = connection

    def __enter__(self) -> sqlite3.Connection:
        self.connection.execute("BEGIN IMMEDIATE")
        return self.connection

    def __exit__(self, exc_type, exc, tb) -> bool:
        if exc_type is None:
            self.connection.commit()
        else:
            self.connection.rollback()
        return False
