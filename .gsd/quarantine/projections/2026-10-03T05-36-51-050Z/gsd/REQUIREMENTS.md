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

## Validated

## Deferred

## Out of Scope

## Traceability

| ID | Class | Status | Primary owner | Supporting | Proof |
| --- | --- | --- | --- | --- | --- |
| R001 | primary-user-loop | active | M002/S01 | M002/S02, M003/S04 | unmapped |
| R002 | core-capability | active | M001/S01 | M002/S03, M003/S01 | unmapped |

## Coverage Summary

- Active requirements: 2
- Mapped to slices: 2
- Validated: 0
- Unmapped active requirements: 0
