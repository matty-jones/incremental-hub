# Workstation backup and restore

The live database stays at `HUB_DB_PATH`. The default is `server/db/saves.db`. Backups are written to `HUB_BACKUP_DIR`. The default is `server/backups`. A backup is a separate SQLite file made with the online backup API. It is not a copy of the live WAL files.

## Take a backup

From `server/`, with the project virtualenv:

```
HUB_DB_PATH=/absolute/path/saves.db \
HUB_BACKUP_DIR=/absolute/path/backups \
.venv/bin/python -m app.backup
```

The command prints the new file, named `hub-YYYYMMDDTHHMMSSZ.db`. It checks `PRAGMA integrity_check` and recomputes the SHA-256 of every `save_snapshots` payload before it publishes the file. A failed check deletes the partial file and records the error in `backup-status.json`. The live database is left in place.

`GET /health` reports the last success, the last failure, and the backup filename. It does not include save payloads.

Keep the latest file for each populated UTC day over 30 days, and the latest file for each populated ISO week over 12 weeks. Files that do not match `hub-YYYYMMDDTHHMMSSZ.db` are not deleted. `saves.db` is not deleted.

## Schedule

`server/incremental-hub-backup.service` and `server/incremental-hub-backup.timer` are templates. Substitute `@ROOT@` the same way as `incremental-hub.service`. The timer is daily and persistent. Installing or starting that timer is an operator step. This repository change does not enable it and does not restart the running server.

The main `incremental-hub.service` unit is unchanged. It still reads `HUB_DB_PATH` and `HUB_APK_DIR`.

## Restore

1. Stop the workstation service so nothing is writing the live database.
2. Choose a `hub-*.db` file. Open it with SQLite and run `PRAGMA integrity_check`. The result must be `ok`.
3. Compare `sha256` in `save_snapshots` with a SHA-256 of the `payload` text for the rows you expect to recover. The hash is over the UTF-8 bytes of the payload string.
4. Move `saves.db`, `saves.db-wal`, and `saves.db-shm` aside. Do not copy a backup onto those live files in place.
5. Copy the chosen backup to the `HUB_DB_PATH` location as a new `saves.db`.
6. Start the service. `GET /health` should report `status: ok`. `GET /saves` should show the pointer that was current in that backup. Older payloads remain in `save_snapshots`.
7. Confirm one known snapshot hash before telling a phone to sync.

Opening a restored file runs the forward schema check. It does not delete snapshot rows. A phone that has a newer accepted save uploads it after reconnect. It does not replace that local save with the restored pointer when the local save is further along.
