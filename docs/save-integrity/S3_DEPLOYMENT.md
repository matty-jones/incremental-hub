# S3 deployment

Server first, phone second. Do not run `./release`, restart the live service, replace `server/db/saves.db`, install the phone build, or enable the backup timer until the checks below have passed on a disposable copy.

## What is already true in git

- S3a stores immutable `save_snapshots` and a versioned `current_pointers` row. The `saves` table remains the legacy mirror.
- S3b adds a Room outbox. The first launch of an upgraded phone queues the existing legacy pointer and its protected high-water or recovery baseline. Quarantine and a non-legacy lineage are not queued. The player does not have to start a game for that queue to be written.
- S3c adds `python -m app.backup`. That command copies the current file and does not migrate it. The timer template still runs that command. The timer is not installed.
- History metadata is paged. The phone stores a cursor, not every workstation payload. One selected snapshot can be fetched when a restore asks for it.
- `retained-pointer` rows that were pinned before the time-tier change stay pinned. Nothing in this hardening unpins or deletes them.

## Order

1. Stop `incremental-hub` before the new server code is started. The new process migrates on open. The backup has to be taken first.
2. From `server/`, with the existing `HUB_DB_PATH` and `HUB_BACKUP_DIR`, run:

   ```
   .venv/bin/python -m app.backup migrate
   ```

   The command does three things, and it stops on the first failure:

   - Copy the live file with the online backup API without applying the history schema. The published file is `pre-migrate-YYYYMMDDTHHMMSSZ.db`. It is not a `hub-` file, so later retention cannot delete it. The command checks `PRAGMA integrity_check` and the SHA-256 of every `saves.snapshot_payload`, including when `save_snapshots` does not exist yet.
   - Open the live database and migrate it. Check that every payload from the pre-migration copy is still byte-for-byte in `save_snapshots`, and that the live `saves` mirror was not rewritten.
   - Write a separate `hub-YYYYMMDDTHHMMSSZ.db` backup of the migrated database and verify its snapshot hashes.

   A failed copy does not migrate. A failed migration leaves the `pre-migrate-` file and rolls the schema change back. A failed post-migration backup leaves the `pre-migrate-` file in place. Do not delete that file to retry. Do not start the service until the command exits 0 and both files are present.
3. Start the new server code only after that command exits 0. Leave `HUB_DB_PATH` and `HUB_APK_DIR` as they are. The main unit file does not need a new `ExecStart`.
4. Watch one legacy phone session. A save should still appear in `GET /saves` as the pre-migration pointer. A newer upload from that phone should add an `archived` history row and leave the pointer version unchanged. The phone keeps its own Room copy.
5. Install the phone build that contains the outbox and the history cursor only after the server above is the one answering. On first launch the existing legacy save is queued. The next sync appends it and proposes it with `expected_version`. A conflict leaves both the phone and the history intact. Reconciliation downloads history metadata. It does not pin every remote payload into Room.
6. Install `incremental-hub-backup.timer` only after the manual migrate command has succeeded, and only if a daily copy is wanted. The timer runs `python -m app.backup` and does not migrate. Substitute `@ROOT@` first. See `docs/save-integrity/WORKSTATION_BACKUP.md` for restore.

## Restore from these two files

- `pre-migrate-*.db` is the original legacy database. Stop the service, move the live `saves.db`, `saves.db-wal`, and `saves.db-shm` aside, and copy this file into place. Opening it with the new server migrates that restored copy. Keep another copy of the `pre-migrate-` file.
- `hub-*.db` is the migrated database. Restore it the same way. `PRAGMA integrity_check` must be `ok`, and every `save_snapshots.sha256` must match its payload, before the service starts.

## What stays unresolved until that rollout

- Until the new phone build is installed, the workstation pointer does not follow a legacy upload. The bytes are in history. They are not the active pointer.
- A confirmed new lineage on the phone is still not uploaded. It cannot replace the previous game's workstation pointer.
- `retained-pointer` rows that were pinned before the time-tier change stay pinned. Nothing in S3 unpins or deletes them.
- The phone does not keep a local copy of every workstation payload. Metadata sync records a cursor. Recovering an older snapshot is an explicit fetch of that one payload.
- The server does not classify games. It stores the integrity value the phone sends. There is no API authentication.
- No device check has been run. Vault is still not the recovery UI.
