# Requirements

This file is the explicit capability and coverage contract for the project.

## Active

### R001 — The launcher shows recently-played games first. Each card displays game name, Resume (primary action), last played time, latest successful snapshot time, protection status label (e.g. "Captured 4 minutes ago", "Restore tested", "Capture failed", "External copy 2 days ago"), and a short resume note when available. Secondary actions include vault, display settings, and diagnostics. Unsupported games appear as candidates with an explanation but MUST NOT display a protection status.
- Class: primary-user-loop
- Status: active
- Description: The launcher shows recently-played games first. Each card displays game name, Resume (primary action), last played time, latest successful snapshot time, protection status label (e.g. "Captured 4 minutes ago", "Restore tested", "Capture failed", "External copy 2 days ago"), and a short resume note when available. Secondary actions include vault, display settings, and diagnostics. Unsupported games appear as candidates with an explanation but MUST NOT display a protection status.
- Why it matters: Resume is the primary action per spec. The user taps one card to start playing; protection labels must be honest and observable, not a single green tick.
- Source: spec
- Primary owning slice: M002/S01
- Supporting slices: M002/S02, M003/S04

### R002 — Each game runs in its own AndroidX WebView with a named, profile-scoped browsing context. Profile binding occurs before any script installation or navigation. Same-origin games remain isolated. No shared-profile fallback is permitted. Feature support for MULTI_PROFILE, DOCUMENT_START_SCRIPT, and WEB_MESSAGE_LISTENER is detected at runtime; if unavailable, the app shows remediation guidance but keeps vault browsing/export accessible.
- Class: core-capability
- Status: active
- Description: Each game runs in its own AndroidX WebView with a named, profile-scoped browsing context. Profile binding occurs before any script installation or navigation. Same-origin games remain isolated. No shared-profile fallback is permitted. Feature support for MULTI_PROFILE, DOCUMENT_START_SCRIPT, and WEB_MESSAGE_LISTENER is detected at runtime; if unavailable, the app shows remediation guidance but keeps vault browsing/export accessible.
- Why it matters: Profile isolation is the foundation of safe save capture and restore. Without it, two games at the same origin would share localStorage and corrupt each other.
- Source: spec
- Primary owning slice: M001/S01
- Supporting slices: M002/S03, M003/S01

### R003 — A versioned JSON game definition is validated at build time and load time. Fields include schemaVersion, gameId, displayName, sourceUrl, launchUrl, navigationRules, storageContext, adapterId, adapterVersion, saveCoverage, displayDefaults, versionProbe, and compatibility. Changes to game identity, authoritative origin, or storage context require an explicit migration, never a silent definition update.
- Class: core-capability
- Status: active
- Description: A versioned JSON game definition is validated at build time and load time. Fields include schemaVersion, gameId, displayName, sourceUrl, launchUrl, navigationRules, storageContext, adapterId, adapterVersion, saveCoverage, displayDefaults, versionProbe, and compatibility. Changes to game identity, authoritative origin, or storage context require an explicit migration, never a silent definition update.
- Why it matters: Game definitions are the declarative contract between the Hub and each game. Validation prevents runtime mismatches between adapter expectations and actual game behavior.
- Source: spec
- Primary owning slice: M002/S01
- Supporting slices: M001/S03, M003/S02

### R004 — The adapter bridge uses an origin-restricted WebMessageListener with a short document-start bootstrap. Every message includes protocolVersion, sessionId, navigationEpoch, requestId, type, adapterVersion, and payload. Native code verifies source origin, frame mode, active WebView, instance, navigation epoch, outstanding request, size limit, and schema. Limits: 8 MiB uncompressed capture payload, 256 KiB transport chunks, 10-second foreground capture deadline, 2-second best-effort background request budget.
- Class: core-capability
- Status: active
- Description: The adapter bridge uses an origin-restricted WebMessageListener with a short document-start bootstrap. Every message includes protocolVersion, sessionId, navigationEpoch, requestId, type, adapterVersion, and payload. Native code verifies source origin, frame mode, active WebView, instance, navigation epoch, outstanding request, size limit, and schema. Limits: 8 MiB uncompressed capture payload, 256 KiB transport chunks, 10-second foreground capture deadline, 2-second best-effort background request budget.
- Why it matters: The bridge is the only communication channel between game JavaScript and native Vault. Origin restriction and message correlation prevent cross-game interference and stale-request attacks.
- Source: spec
- Primary owning slice: M001/S02
- Supporting slices: M002/S03, M003/S02

### R005 — Captures occur every 60 seconds during active play, on manual request, on checkpoint, before reload/restore/switch, on normal exit, and best-effort on background. Each capture produces an immutable snapshot envelope (formatVersion, snapshotId, instanceId, gameId, parentSnapshotId, captureSequence, capturedAt, reason, origin, definitionVersion, adapter, coverage, freshness, payload with encoding/sha256/uncompressedBytes, validation) written as a compressed blob to app-private storage. Atomic publication requires the blob to be durable before the metadata row is published in a Room transaction.
- Class: core-capability
- Status: active
- Description: Captures occur every 60 seconds during active play, on manual request, on checkpoint, before reload/restore/switch, on normal exit, and best-effort on background. Each capture produces an immutable snapshot envelope (formatVersion, snapshotId, instanceId, gameId, parentSnapshotId, captureSequence, capturedAt, reason, origin, definitionVersion, adapter, coverage, freshness, payload with encoding/sha256/uncompressedBytes, validation) written as a compressed blob to app-private storage. Atomic publication requires the blob to be durable before the metadata row is published in a Room transaction.
- Why it matters: Immutable vault records are the authoritative durable layer. Atomic publication prevents orphaned metadata rows with missing blobs.
- Source: spec
- Primary owning slice: M003/S01
- Supporting slices: M003/S02, M004/S01

### R006 — The play surface occupies available space with correct cutout, hinge, and keyboard handling. A small movable accessible menu handle provides Resume, Snapshot now, Checkpoint, Vault, Reload, Display, and Return to hub. Android Back opens the native menu (not silent navigation). The menu MUST NOT recreate or reload the WebView. No edge swipe is the only way to access controls; native controls have 48 dp touch targets. No pull-to-refresh.
- Class: primary-user-loop
- Status: active
- Description: The play surface occupies available space with correct cutout, hinge, and keyboard handling. A small movable accessible menu handle provides Resume, Snapshot now, Checkpoint, Vault, Reload, Display, and Return to hub. Android Back opens the native menu (not silent navigation). The menu MUST NOT recreate or reload the WebView. No edge swipe is the only way to access controls; native controls have 48 dp touch targets. No pull-to-refresh.
- Why it matters: The foldable form factor requires adaptive layout. Native controls must not conflict with Android navigation or game input.
- Source: spec
- Primary owning slice: M002/S02
- Supporting slices: M002/S03, M004/S01

### R007 — Navigation rules: top-level navigation only within validated game definition. External HTTP(S) links open system browser only after user gesture or native confirmation. Block automatic external redirects. Deny file:, content:, javascript:, intent:, and unapproved custom schemes. Support required blob-based game downloads only through explicit bounded adapter/native export flow. HTTPS-only games; cancel certificate errors; no mixed content; disable file access and universal file-URL access. Deny camera, microphone, geolocation. Keep Safe Browsing enabled. Production WebView debugging off.
- Class: constraint
- Status: active
- Description: Navigation rules: top-level navigation only within validated game definition. External HTTP(S) links open system browser only after user gesture or native confirmation. Block automatic external redirects. Deny file:, content:, javascript:, intent:, and unapproved custom schemes. Support required blob-based game downloads only through explicit bounded adapter/native export flow. HTTPS-only games; cancel certificate errors; no mixed content; disable file access and universal file-URL access. Deny camera, microphone, geolocation. Keep Safe Browsing enabled. Production WebView debugging off.
- Why it matters: Navigation versus network access is a critical security boundary. WebView scripts, frames, and workers have separate network behaviour not covered by navigation policies.
- Source: spec
- Primary owning slice: M002/S03
- Supporting slices: M001/S02, M003/S04

### R008 — Cold start protection: before launching game code, inspect authoritative saved keys using a native-supplied bootstrap document at the qualified storage origin. The bootstrap contains no game scripts or network dependencies. A populated existing profile gets a protected pre-launch snapshot. Empty or unparseable storage with previous history enters recovery UI. The bootstrap's native-served URL is authorised separately from remote game navigation.
- Class: core-capability
- Status: active
- Description: Cold start protection: before launching game code, inspect authoritative saved keys using a native-supplied bootstrap document at the qualified storage origin. The bootstrap contains no game scripts or network dependencies. A populated existing profile gets a protected pre-launch snapshot. Empty or unparseable storage with previous history enters recovery UI. The bootstrap's native-served URL is authorised separately from remote game navigation.
- Why it matters: Prevents upstream migration or overwrite from destroying existing progress on cold launch.
- Source: spec
- Primary owning slice: M001/S02
- Supporting slices: M002/S03, M004/S01

### R009 — Validation levels are separate fields, not one universal verified boolean: (1) Integrity — stored bytes exist and match checksum, (2) Structure — adapter recognises payload and required fields/keys, (3) Plausibility — comparisons identify suspicious resets or incompatible versions, (4) Restore evidence — fixture-tested adapter compatibility or actual restore verification. Checksums prove byte integrity, not playability.
- Class: failure-visibility
- Status: active
- Description: Validation levels are separate fields, not one universal verified boolean: (1) Integrity — stored bytes exist and match checksum, (2) Structure — adapter recognises payload and required fields/keys, (3) Plausibility — comparisons identify suspicious resets or incompatible versions, (4) Restore evidence — fixture-tested adapter compatibility or actual restore verification. Checksums prove byte integrity, not playability.
- Why it matters: Separate validation fields prevent false confidence. A snapshot can be intact but structurally invalid or implausibly regressed.
- Source: spec
- Primary owning slice: M003/S02
- Supporting slices: M004/S01

### R010 — Generic localStorage v1 adapter represents storage as an array of exact string key/value pairs. Preserves all UTF-16 code units through reversible JSON escaping. Rejects duplicate keys on import. Captures configured authoritative keys in one synchronous JavaScript task. Single-page localStorage access avoids interleaving. Does not decode/re-encode opaque saves unless the adapter explicitly owns that transformation.
- Class: core-capability
- Status: active
- Description: Generic localStorage v1 adapter represents storage as an array of exact string key/value pairs. Preserves all UTF-16 code units through reversible JSON escaping. Rejects duplicate keys on import. Captures configured authoritative keys in one synchronous JavaScript task. Single-page localStorage access avoids interleaving. Does not decode/re-encode opaque saves unless the adapter explicitly owns that transformation.
- Why it matters: Lossless key/value preservation is the baseline adapter. Game-specific adapters may add force-persist or heap capture.
- Source: spec
- Primary owning slice: M001/S03
- Supporting slices: M003/S02, M004/S04

## Validated

## Deferred

## Out of Scope

## Traceability

| ID | Class | Status | Primary owner | Supporting | Proof |
| --- | --- | --- | --- | --- | --- |
| R001 | primary-user-loop | active | M002/S01 | M002/S02, M003/S04 | unmapped |
| R002 | core-capability | active | M001/S01 | M002/S03, M003/S01 | unmapped |
| R003 | core-capability | active | M002/S01 | M001/S03, M003/S02 | unmapped |
| R004 | core-capability | active | M001/S02 | M002/S03, M003/S02 | unmapped |
| R005 | core-capability | active | M003/S01 | M003/S02, M004/S01 | unmapped |
| R006 | primary-user-loop | active | M002/S02 | M002/S03, M004/S01 | unmapped |
| R007 | constraint | active | M002/S03 | M001/S02, M003/S04 | unmapped |
| R008 | core-capability | active | M001/S02 | M002/S03, M004/S01 | unmapped |
| R009 | failure-visibility | active | M003/S02 | M004/S01 | unmapped |
| R010 | core-capability | active | M001/S03 | M003/S02, M004/S04 | unmapped |

## Coverage Summary

- Active requirements: 10
- Mapped to slices: 10
- Validated: 0
- Unmapped active requirements: 0
