# GSD State

**Active Milestone:** M001: Feasibility and Qualification
**Active Slice:** S01: WebView Profile Capability Spike
**Phase:** planning
**Requirements Status:** 12 active · 0 validated · 0 deferred · 1 out of scope

## Milestone Registry
- 🔄 **M001:** Feasibility and Qualification
- ⬜ **M002:** M002
- ⬜ **M003:** Durable Vault
- ⬜ **M004:** Safe Recovery & Portability
- ⬜ **M005:** Mobile Fit
- ⬜ **M006:** Human Continuity
- ⬜ **M007:** Compatibility Expansion
- ⬜ **M008:** Preservation & Automation
- ⬜ **M009:** Continuity Assistant

## Recent Decisions
- D001 (M0 device testing on Pixel Fold): Profile capability gate determines the supported integration path -> Detect MULTI_PROFILE, DOCUMENT_START_SCRIPT, WEB_MESSAGE_LISTENER at runtime via WebViewFeature.isFeatureSupported(). If all three are available, use the full profile-isolated integration path. If any are missing, show remediation UI and keep vault browsing/export accessible without profile isolation. Never silently fall back to a shared profile.
- D002 (M0 bootstrap spike testing): Bootstrap document reaches native-supplied content without executing upstream service worker code -> Use loadDataWithBaseURL() with a native HTTPS base origin, or WebViewAssetLoader, to serve a minimal HTML bootstrap document. The bootstrap contains no game scripts or network dependencies. Before navigation to the remote game URL, the bootstrap reads existing localStorage and captures a pre-launch snapshot. The bootstrap URL is authorised separately from remote game navigation. Service worker interception is tested and documented.
- D003 (M04 archive import/export implementation): Archive format v1 uses .ihub ZIP with manifest and immutable blobs -> Extension: .ihub. Container: ZIP with manifest.json (format version, archive ID, creation time, exporting app version, member paths/lengths/hashes, included instance/snapshot IDs, exclusions), game/instance records, snapshot envelopes, and immutable payload blobs. Adapters remain bundled/trusted application code. Imported definitions are inert until matched to an installed adapter. No auto-execute scripts. Unique destination filenames. Streamed import with bounded validation (512 MiB compressed, 1 GiB expanded, 10,000 entries, 8 MiB payload). Failed import leaves existing vault unchanged.
- D004 (M02 session controller implementation): Lifecycle ownership: SessionController owns WebView and profile lifecycle -> SessionController owns the active WebView instance, profile binding, navigation epoch, and capture/restore state machine. The WebView is kept outside transient composable branches; recomposition reuses its identity. No Activity-bound WebView in a long-lived ViewModel. On process death, recovery is through durable state (Room + vault), not WebView state. Renderer crash detaches and destroys the failed WebView, invalidates pending bridge requests, and shows recovery UI.
- D005 (Project structure decisions throughout M0–M3): Modular monolith with cohesive packages, split Gradle modules only when justified -> Start with one Android module and cohesive packages (ui, games, session, bridge, adapters, vault, archive, continuity, diagnostics). Split Gradle modules only when build or testing needs justify it (e.g., when pure validation/retention/archive rules need device-free testing). No plugin framework or service mesh inside the phone. Constructor DI with a small composition root.

## Blockers
- None

## Next Action
Slice S01 has no DB tasks. Plan slice tasks before execution.
