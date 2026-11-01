# Recovery runbook

Use this after a failed restore, a bad archive, or a workstation database that needs to be replaced. The `.ihub` archive is the phone recovery copy. A verified `hub-*.db` file is the workstation recovery copy. Do not restore either one onto the production Pixel Fold save or the live `server/db/saves.db` until the checks below pass on a disposable copy.

## Phone: clean device from an `.ihub` archive

1. Install Incremental Hub on the clean device. Do not copy the old WebView profile or the Room database onto it. Android backup and device transfer do not carry those files.
2. Open the vault and choose Import. Select the `.ihub` file through the system document picker.
3. Import adds historical snapshots only. It does not change the live game, the Room pointer, or the workstation pointer. If the import reports a conflict, different bytes, or an interruption, stop. The existing vault is unchanged.
4. Confirm the imported row. The snapshot id, game, lineage, and payload bytes must match the archive. A migrated row may have had a blank stored hash; the archive hash is the SHA-256 of the payload bytes.
5. On a device with no live save for that game, open the snapshot and press **Use this save**. The Room pointer becomes those exact bytes, on the same lineage, and the high-water id is that snapshot. The snapshot row is not rewritten. The action is not shown when a live save already exists.
6. If the device already has a live save, the restore does not replace it. A save that is behind the protected pointer needs an explicit new-lineage confirmation. That confirmation pins the previous high-water snapshot and leaves workstation history in place. It does not move the workstation pointer.
7. Force-stop the app and open it again. The pointer, lineage, and snapshot bytes must be the same. An unfinished restore journal stops the game until the journal reconciles or recovery is shown. A journal that already committed keeps that pointer even when the storage read-back does not match.

## Workstation: verified SQLite backup

1. Stop the workstation service so nothing is writing the live database.
2. Choose a `hub-*.db` file from `HUB_BACKUP_DIR`. Leave `pre-migrate-*.db` files where they are. They are not daily backups.
3. Copy that file to a disposable directory. Do not copy it onto `saves.db` yet.
4. Open the copy and run `PRAGMA integrity_check`. The result must be `ok`.
5. For each row in `save_snapshots`, SHA-256 the UTF-8 `payload` and compare it with `sha256`.
6. Read `saves` and confirm the active pointer for each game you expect to recover. Older payloads remain in `save_snapshots`.
7. Move the live `saves.db`, `saves.db-wal`, and `saves.db-shm` aside. Copy the checked backup to `HUB_DB_PATH` as a new `saves.db`.
8. Start the service. `GET /health` must report `status: ok`. `GET /saves` must show the pointer from that backup.
9. Confirm one known snapshot hash before telling a phone to sync. A phone with a newer accepted save uploads it after reconnect. It does not replace that local save with the restored pointer.

`python -m app.backup` does not migrate. `python -m app.backup migrate` is a separate operator step and is not part of ordinary restore.
