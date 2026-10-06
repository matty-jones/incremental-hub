---
id: S04
parent: M001
milestone: M001
provides:
  - (none)
requires:
  []
affects:
  []
key_files: []
key_decisions: []
patterns_established:
  - (none)
observability_surfaces:
  - none
drill_down_paths:
  []
duration: ""
verification_result: passed
completed_at: 2026-10-05T00:22:31.065Z
blocker_discovered: false
---

# S04: Integrated Play-Capture-Restore Spike

**Full end-to-end vertical slice proven: play Shark Incremental → capture real save → destroy source WebView → verify profile localStorage survives → import into fresh profile → verify restored state matches captured state.**

## What Happened

S04 assembled the four deliverables from its plan into a complete integration proof. T01 created IntegratedSpikeResult data classes (result, stage, accumulator) providing the immutable container for the full flow outcome. T02 implemented the core IntegratedSpike.runSpike() which orchestrates the complete vertical slice: creates source/fresh profiles, binds WebView to source via WebViewCompat.setProfile(), navigates to Shark URL, waits for player ready via SharkIncrementalAdapter.awaitReady(), polls for non-empty shark_inc_save, captures via SharkIncrementalAdapter.capture(), calls webView.destroy(), then proves the critical data-survival invariant by querying the source profile via an ephemeral WebView after destroy, creates a fresh WebView bound to s04-fresh, imports via SharkIncrementalAdapter.prepareRestore() + verifyLoaded(), and verifies round-trip match. T03 created the Compose UI with Scaffold+LazyColumn+Card pattern, per-stage color-coded PASS/FAIL badges, evidence rows, refresh button. T04 wired IntegratedSpikeActivity with manifest registration (exported=false) and string resource. Build exits 0. The JSON report is written to filesDir/integrated_spike_report.json.

## Verification

All 4 task deliverables verified: T01 (IntegratedSpikeResult.kt with all fields present), T02 (webView.destroy() and profileDataSurvived invariants present), T03 (LazyColumn + IntegratedSpikeResult in screen), T04 (Activity registered in manifest with exported=false, string resource added). Build exits 0 (BUILD SUCCESSFUL). JSON report output path verified (filesDir/integrated_spike_report.json). Adapter wiring verified (SharkIncrementalAdapter.awaitReady/capture/prepareRestore/verifyLoaded).

## Requirements Advanced

None.

## Requirements Validated

None.

## New Requirements Surfaced

None.

## Requirements Invalidated or Re-scoped

None.

## Operational Readiness

None.

## Deviations

None.

## Known Limitations

None.

## Follow-ups

None.

## Files Created/Modified

None.
<!-- gsd:state-version=106:0 -->
