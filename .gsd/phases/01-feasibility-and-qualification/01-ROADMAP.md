# M001: Feasibility and Qualification

**Vision:** Prove the three non-negotiable technical foundations — WebView profile isolation, native-origin bootstrap ordering, and candidate-game save compatibility — on the real Pixel Fold device. Produce a minimal app that demonstrates each, a capability report, and a candidate game qualification report with explicit blockers.

## Slices

- [x] **S01: WebView Profile Capability Spike** `risk:high` `depends:[]`
  > After this: A minimal app that detects MULTI_PROFILE, DOCUMENT_START_SCRIPT, and WEB_MESSAGE_LISTENER feature support on the real Pixel Fold and reports results in a capability screen.

- [x] **S02: Native-Origin Bootstrap Spike** `risk:high` `depends:[S01]`
  > After this: A native HTML bootstrap document served from app assets loads at a qualified storage origin, reads existing localStorage, and captures a pre-launch snapshot before the remote game code executes.

- [x] **S03: Candidate Game Qualification** `risk:high` `depends:[S01,S02]`
  > After this: FE000000 (or Shark Incremental) is inspected: actual localStorage keys, serialisation format, save cadence, readiness signal, and import path are identified. A capture→export→fresh-profile import→verify round-trip is proven.

- [x] **S04: Integrated Play-Capture-Restore Spike** `risk:medium` `depends:[S01,S02,S03]`
  > After this: A minimal app that proves the full vertical slice: play FE000000 (or Shark) → capture snapshot → destroy WebView/process → fresh-profile restore → verify game progress is preserved.

## Boundary Map

Not provided.
<!-- gsd:state-version=106:0 -->
