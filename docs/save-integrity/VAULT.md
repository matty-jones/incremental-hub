# Save vault

The vault lists saved history for one game. It opens from the launcher card, from the in-game menu, and from the recovery screen. Opening it does not reload the WebView. The WebView identity stays the game id and the session generation, so a fold or the vault overlay does not create a second game session.

History is paged. The timeline reads snapshot metadata from Room and, one page at a time, from the workstation index. Payloads stay unloaded until a row is opened. Phone rows are ordered by capture sequence, then id. A clock change does not reorder them. Workstation-only rows are a separate section ordered by the time the workstation received them. If the workstation cannot be reached, those rows stay listed as unavailable and local history stays readable.

Shark rows show feature, prestige, core, humanoid, and singularity. Currencies are not turned into floating-point numbers.

## Protection

User protection is the `snapshots.user_protected` column, added by HubDatabase v9 `MIGRATION_8_9`. The migration is `ALTER TABLE snapshots ADD COLUMN user_protected INTEGER NOT NULL DEFAULT 0`. It does not rewrite payloads. Retention still keeps the ten latest distinct automatic saves, 48 hours of hourly saves, 30 days of daily saves, and 12 ISO weeks. A user-protected row is kept in addition to those tiers. Pins, migrated baselines, high-water anchors, diagnostic backups, quarantine, restore checkpoints, and workstation rows stay protected by their existing reasons. Removing user protection does not unpin a system row.

Protecting a snapshot that exists only on the workstation downloads that one payload, checks its SHA-256, and stores it locally before the flag is set.

## Restore

A restore names a snapshot id and lineage. The payload hash is checked, then the game adapter classifies it. `compare` and `LIVE_AHEAD` are not used. The snapshot is loaded in a disposable profile. Replacing the live profile takes a second confirmation.

When the milestones match, currencies and upgrades are not ordered. The restore can update the local pointer. It does not move the high-water snapshot and it does not propose the workstation pointer.

When the snapshot is behind the protected pointer, Restore on a new lineage is the only path. That writes a new lineage, pins the previous high-water row, and keeps the original snapshot bytes. The workstation pointer is not moved. Confirming without that branch writes nothing. A failure inside the branch transaction leaves the previous pointer in place.

While a restore is staged, capture and workstation pointer changes wait.

## Portable archive

Export game, Export everything, and Export this snapshot write an `.ihub` ZIP through the system document picker. Import reads one through the open-document picker. The archive is labelled unencrypted. It contains a versioned manifest, snapshot metadata, lineage ids, SHA-256 hashes, and the exact payload bytes. It does not contain credentials, cookies, WebView profiles, or the live session.

A game or everything export is written only when every selected snapshot is in the file. If a workstation-only payload cannot be downloaded, the export stops and names the omission. It does not write an archive marked complete.

Import checks paths, hashes, ids, format version, and size limits before it inserts anything. The limits are 512 MiB compressed, 1 GiB expanded, 10,000 entries, and 8 MiB for one save payload. The same id and the same bytes are skipped. The same id with different bytes rejects the whole import. New rows are historical snapshots. The live WebView, the Room pointer, and the workstation pointer stay as they were. Restoring an imported snapshot uses the restore steps above.

Deletion and bulk cleanup are not in this vault. Those stay for a later safety check.
