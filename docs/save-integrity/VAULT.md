# Save vault

The vault lists saved history for one game. It opens from the launcher card, from the in-game menu, and from the recovery screen. Opening it does not reload the WebView. The WebView identity stays the game id and the session generation, so a fold or the vault overlay does not create a second game session.

History is paged. The timeline reads snapshot metadata from Room and, one page at a time, from the workstation index. Workstation calls run on the IO dispatcher. Payloads stay unloaded until a row is opened. Phone rows are ordered by capture sequence, then id. A clock change does not reorder them. Workstation-only rows are a separate section ordered by the time the workstation received them. If the workstation cannot be reached, those rows stay listed as unavailable and local history stays readable.

Shark rows show feature, prestige, core, humanoid, and singularity. Currencies are not turned into floating-point numbers.

## Protection

User protection is the `snapshots.user_protected` column, added by HubDatabase v9 `MIGRATION_8_9`. The migration is `ALTER TABLE snapshots ADD COLUMN user_protected INTEGER NOT NULL DEFAULT 0`. It does not rewrite payloads. Retention still keeps the ten latest distinct automatic saves, 48 hours of hourly saves, 30 days of daily saves, and 12 ISO weeks. A user-protected row is kept in addition to those tiers. Pins, migrated baselines, high-water anchors, diagnostic backups, quarantine, restore checkpoints, and workstation rows stay protected by their existing reasons. Removing user protection does not unpin a system row.

An outgoing pointer with no other kept copy is stored as an unpinned `retained-pointer`. That row is kept until another kept snapshot has the same bytes. A duplicate retained-pointer can still age out with the tiers. Rows that were already pinned, including older retained-pointers, stay pinned.

Protecting a snapshot that exists only on the workstation downloads that one payload and checks its SHA-256 before any Room transaction. The transaction only writes the local row.

## Restore

A restore names a snapshot id and lineage. Before a running WebView is replaced, the current game is captured, validated, and pinned. The historical save is then classified against that newly committed pointer and high-water snapshot. A failed or unordered capture stops the restore and leaves the WebView in place. A peek taken at launch is not the baseline. The payload hash is checked, then the game adapter classifies it. `compare` and `LIVE_AHEAD` are not used. The snapshot is loaded in a disposable profile. Replacing the live profile takes a second confirmation.

When the milestones match, currencies and upgrades are not ordered. The restore can update the local pointer. It does not move the high-water snapshot and it does not propose the workstation pointer.

When the snapshot is behind the protected pointer, Restore on a new lineage is the only path. That writes a new lineage, pins the previous high-water row, and keeps the original snapshot bytes. The workstation pointer is not moved. Confirming without that branch writes nothing. A failure inside the branch transaction leaves the previous pointer in place.

While a restore is staged, ordinary capture and workstation pointer changes wait. The capture that pins the live game is part of the restore itself.

## Portable archive

Export game, Export everything, and Export this snapshot write an `.ihub` ZIP through the system document picker. Import reads one through the open-document picker. The archive is labelled unencrypted. It contains a versioned manifest, snapshot metadata, lineage ids, SHA-256 hashes, and the exact payload bytes. It does not contain credentials, cookies, WebView profiles, or the live session.

A game or everything export is written only when every selected snapshot is in the file. Export everything reads the workstation game list. A game that exists only on the workstation is included. If that list or a selected payload cannot be read, the export stops and names the omission. It does not write an archive marked complete.

The archive is written and read as a file. Size limits are applied before a body is allocated. Import checks paths, hashes, ids, format version, and size limits before it inserts anything. The limits are 512 MiB compressed, 1 GiB expanded, 10,000 entries, and 8 MiB for one save payload. The same snapshot id, game, lineage, and payload bytes are skipped, including a migrated row whose stored hash is blank. The same id with different bytes, or the same id for a different game or lineage, rejects the whole import. New rows are historical snapshots. The live WebView, the Room pointer, and the workstation pointer stay as they were. Restoring an imported snapshot uses the restore steps above. A blank local slot can take an imported snapshot without rewriting its bytes. A slot that already has a save is left unchanged.

A complete export records the id, the payload hash, and the stored hash for each selected row, then drops that payload. One payload is loaded again while it is written and checked against that selection. A missing or changed row refuses the archive. Each export holds its own reference, so one export finishing does not release a row another export still has selected.

Format 2 names each snapshot metadata file from the SHA-256 of the snapshot id. The `snapshotId` field and the manifest keep the original id, including a migrated id that contains colons. Room and the workstation are not rewritten. Format 1 archives, whose metadata file is named with the snapshot id itself, still import. A metadata path that does not match its declared id is rejected.

From the launcher, a game with no live save can use **Use this save** on a local snapshot. That adopts the exact bytes, lineage, snapshot id, and high-water id. It does not rewrite the snapshot. A game that already has a save does not show that action, and the write is refused if a save is already stored. Restoring over a running game still uses the in-game restore steps.

Export, import, and zip checks run off the main thread. One payload is held while it is written. Zip validation holds the manifest and one other metadata file, then drops that file. Size limits are unchanged: 512 MiB compressed, 1 GiB expanded, 10,000 entries, and 8 MiB for one save.

Android Auto Backup and device transfer include display preferences only. The Room database, restore journals, and WebView profile directories are excluded, so a transfer cannot restore a native pointer without its profile or a profile without its pointer. The `.ihub` archive remains the portable recovery copy.

Deletion and bulk cleanup are not in this vault. Those stay for a later safety check.
