# S3 deployment

Server first, phone second. Do not run `./release`, restart the live service, or replace `server/db/saves.db` as part of reviewing these commits.

## What is already true in git

- S3a stores immutable `save_snapshots` and a versioned `current_pointers` row. The `saves` table remains the legacy mirror.
- The first open of an old database copies each `saves` payload into history and creates a pointer only when that game does not already have one. It does not update or delete the original payload bytes.
- An installed phone's `PUT /saves/{game_id}` still works. After a pointer exists, that PUT is archived. A newer `captured_at_millis` does not move the pointer. `GET /saves` keeps returning the active row.
- S3b adds a Room v7 outbox on the phone. That build is not installed yet.
- S3c adds `python -m app.backup`, `GET /health`, and a systemd timer template. The timer is not installed.

## Order

1. Take a backup with the new tool while the current service is stopped, so the copy and the migration are not racing the old process.
   - Stop `incremental-hub`.
   - Run `python -m app.backup` from `server/` with the existing `HUB_DB_PATH`. The first open migrates. Confirm the command prints a `hub-*.db` path and `PRAGMA integrity_check` is `ok`.
   - Compare one known `saves.snapshot_payload` with the matching `save_snapshots.payload` before starting the server again.
2. Start the new server code. Leave `HUB_DB_PATH` and `HUB_APK_DIR` as they are. The main unit file does not need a new `ExecStart`.
3. Watch one legacy phone session. A save should still appear in `GET /saves` as the pre-migration pointer. A newer upload from that phone should add an `archived` history row and leave the pointer version unchanged. The phone keeps its own Room copy.
4. Install the phone build that contains the outbox only after the server above is the one answering. The phone uploads accepted snapshots and proposes a pointer with `expected_version`. A conflict leaves both the phone and the history intact.
5. After the first manual backup succeeds, install `incremental-hub-backup.timer` if you want the daily run. Substitute `@ROOT@` first. See `docs/save-integrity/WORKSTATION_BACKUP.md` for restore.

## What stays unresolved until that rollout

- Until the new phone build is installed, the workstation pointer does not follow a legacy upload. The bytes are in history. They are not the active pointer.
- A confirmed new lineage on the phone is still not uploaded. It cannot replace the previous game's workstation pointer.
- `retained-pointer` rows that were pinned before the time-tier change stay pinned. Nothing in S3 unpins or deletes them.
- No device check has been run. Vault is still not the recovery UI.
