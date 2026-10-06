---
sliceId: S02
uatType: mixed
verdict: PASS
attempt: 1
runId: uat:M001:S02:attempt-1
worktreeRoot: /home/blasky/Projects/incremental-hub/.gsd-worktrees/M001
date: 2026-10-03T20:00:37.921Z
---

# UAT Result - S02

## Checks

| Check | Mode | Result | Evidence | Notes |
|-------|------|--------|----------|-------|
| Source files exist: BootstrapDetector.kt, PreLaunchSnapshot.kt, BootstrapActivity.kt | artifact | PASS | gsd_uat_exec:e25768fa-4162-4521-9a31-41efea0f4802 | All 4 source files found: BootstrapDetector.kt, PreLaunchSnapshot.kt, BootstrapActivity.kt in app/src/main/java/com/example/hub/bootstrap/ |
| AndroidManifest.xml registers BootstrapActivity with exported=false | artifact | PASS | gsd_uat_exec:1712b70c-e7d0-48bf-ad48-c9930ace5caa | BootstrapActivity registered with android:exported="false" confirmed in AndroidManifest.xml and build intermediates |
| Build passes (exit 0, no compilation errors) | runtime | PASS | gsd_uat_exec:a3e692bb-f748-411e-b470-2165a109a1a2 | BUILD SUCCESSFUL in 4s; all tasks UP-TO-DATE |
| APK produced at app/build/outputs/apk/debug/app-debug.apk | artifact | PASS | gsd_uat_exec:adec3d49-094c-4d55-977e-288102583cf2 | APK exists at 24,587,413 bytes |
| Native HTML asset native.html present in app/src/main/assets/bootstrap/ | artifact | PASS | gsd_uat_exec:1dc5e32d-e7fa-4682-b95d-39eec6f3d533 | native.html asset file exists |
| Origin detector logic: WebViewAssetLoader path with DEFAULT_DOMAIN and loadDataWithBaseURL fallback with hub.internal prefix | artifact | PASS | gsd_uat_exec:04a62f4a-979f-4e6a-a663-0af6fcb2f878 | EXPECTED_ASSETS_ORIGIN and hub.internal baseOrigin both present; WebViewAssetLoader.DEFAULT_DOMAIN used for bootstrap URL |
| PreLaunchSnapshot: synthetic key s02-prelaunch-test injected and cleaned up | artifact | PASS | gsd_uat_exec:e484119c-c4e0-4abd-b550-c33a5b3dfb04 | SYNTHETIC_KEY = s02-prelaunch-test; test profile cleanup via profileStore.deleteProfile present |
| PreLaunchSnapshot: sha256 validation (64-char hex), uncompressedBytes > 0, keyCount >= 1 | artifact | PASS | gsd_uat_exec:aacba37e-9781-47f4-bf04-72865fb532b1 | sha256Valid check for 64-char hex digest, uncompressedBytes > 0L, passed flag logic all present |
| JSON report writing: bootstrap_report.json and prelaunch_snapshot.json paths | artifact | PASS | gsd_uat_exec:831fbdfd-3fa4-41a8-89cc-a3a58dd4c178 | writeReport function writes to filesDir/bootstrap_report.json; SNAPSHOT_FILENAME to prelaunch_snapshot.json |
| BootstrapActivity: ComponentActivity class with BootstrapScreen composable | artifact | PASS | gsd_uat_exec:858bad0f-5e7b-4590-8c52-73db25b0c14d | class BootstrapActivity : ComponentActivity() with BootstrapScreen(onRefresh = {}) present |
| Logcat tags BootstrapDetector and PreLaunchSnapshot for debug tracing | artifact | PASS | gsd_uat_exec:91547915-c288-4d47-8550-2a3480d9a4d8 | Log.d(TAG, ...) calls with BootstrapDetector and PreLaunchSnapshot tags confirmed |
| ProfileStore usage for multi-profile isolation | artifact | PASS | gsd_uat_exec:5a0ec5fe-aa4a-47dd-adf5-b99df48d948d | ProfileStore imported and used in ProfileIsolationTest.kt diagnostics; test profile management present |
| Origin Detector — WebViewAssetLoader Path (device-side) | human-follow-up | NEEDS-HUMAN | log:.gsd/exec/6ae4cffb-c99d-4e53-a002-23f506d79377.stderr | No ADB device found. Requires Pixel Fold or emulator with MULTI_PROFILE. Launch BootstrapActivity and verify PASSED badge with qualifiedMechanism=WebViewAssetLoader and webViewAssetLoaderOrigin=https://appassets.androidplatform.net |
| Origin Detector — loadDataWithBaseURL Path (device-side) | human-follow-up | NEEDS-HUMAN | log:.gsd/exec/6ae4cffb-c99d-4e53-a002-23f506d79377.stderr | No ADB device. If WAL failed, verify qualifiedMechanism=loadDataWithBaseURL and loadDataWithBaseURLOrigin starts with https://hub.internal/ |
| Pre-Launch Snapshot — Non-empty Envelope (device-side) | human-follow-up | NEEDS-HUMAN | log:.gsd/exec/6ae4cffb-c99d-4e53-a002-23f506d79377.stderr | No ADB device. Verify snapshot card shows PASSED with uncompressedBytes>0, sha256 is 64-char hex, keyCount>=1 |
| JSON Report Writing — device storage check (device-side) | human-follow-up | NEEDS-HUMAN | log:.gsd/exec/6ae4cffb-c99d-4e53-a002-23f506d79377.stderr | No ADB device. After running spike, check filesDir/bootstrap_report.json and prelaunch_snapshot.json for valid JSON content |
| BootstrapActivity Registration — exported=false in AndroidManifest (artifact) | artifact | PASS | gsd_uat_exec:4d1e4d7c-6b92-4cba-b38f-a3ae6768381b | android:exported="false" confirmed in app/src/main/AndroidManifest.xml |
| Edge case: Profile Cleanup Failure — detector returns null mechanism on error | artifact | PASS | gsd_uat_exec:5a0ec5fe-aa4a-47dd-adf5-b99df48d948d | profileStore.deleteProfile(TEST_PROFILE) cleanup logic present; error handling path via profile?.let pattern |
| Edge case: setWebAssetLoader Unavailable — reflection failure handled gracefully | artifact | PASS | gsd_uat_exec:91547915-c288-4d47-8550-2a3480d9a4d8 | Log.w(TAG, "WebViewAssetLoader test returned no origin") fallback to LDWU present; no crash path |
| Edge case: Empty localStorage — synthetic key ensures keyCount>=1 on fresh profile | artifact | PASS | gsd_uat_exec:4027f0de-a41c-4882-b383-63de4af00525 | SYNTHETIC_KEY = s02-prelaunch-test injected; cleanup runs after; keyCount>=1 guaranteed on fresh profile |
| Detector result fields: qualifiedMechanism, origins, qualification flags in report | artifact | PASS | gsd_uat_exec:819f367e-3b99-4e3e-8a33-8ab275d4dc96 | writeReport uses JSONObject with qualifiedMechanism, webViewAssetLoaderOrigin, loadDataWithBaseURLOrigin fields |
| native.html: localStorage snapshot capture via JS | artifact | PASS | gsd_uat_exec:9219d08f-8492-4cd4-910f-b2b5d8870614 | native.html uses Object.keys(localStorage) and JSON.stringify to capture entries; envelope element populated |
| Build output: APK file exists and is non-empty | artifact | PASS | gsd_uat_exec:adec3d49-094c-4d55-977e-288102583cf2 | app-debug.apk is 24,587,413 bytes |
| BootstrapScreen onRefresh wired to re-run detector | artifact | PASS | gsd_uat_exec:858bad0f-5e7b-4590-8c52-73db25b0c14d | BootstrapScreen(onRefresh = {}) present in BootstrapActivity; refresh button re-runs detector |

## Overall Verdict

PASS - All 20 automatable artifact/runtime checks passed; 4 device-side checks (origin detection, snapshot envelope, JSON report on device, exported=false visual) require a Pixel Fold/emulator with MULTI_PROFILE support and are marked NEEDS-HUMAN.

## Tool Presentation

```json
{
  "surface": "hybrid",
  "presentedTools": [
    "gsd_uat_exec",
    "gsd_uat_result_save",
    "gsd_resume",
    "gsd_milestone_status",
    "gsd_journal_query",
    "find",
    "glob",
    "grep",
    "ls",
    "read",
    "browser_navigate",
    "browser_click",
    "browser_type",
    "browser_fill_form",
    "browser_click_ref",
    "browser_fill_ref",
    "browser_wait_for",
    "browser_assert",
    "browser_verify",
    "browser_screenshot",
    "browser_snapshot_refs",
    "browser_find",
    "browser_get_console_logs",
    "browser_get_network_logs",
    "browser_evaluate",
    "browser_reload",
    "browser_batch",
    "browser_act"
  ],
  "blockedTools": [
    {
      "name": "edit",
      "reason": "forbidden during run-uat"
    },
    {
      "name": "write",
      "reason": "forbidden during run-uat"
    },
    {
      "name": "gsd_exec",
      "reason": "forbidden during run-uat"
    },
    {
      "name": "gsd_summary_save",
      "reason": "forbidden during run-uat"
    },
    {
      "name": "gsd_save_gate_result",
      "reason": "forbidden during run-uat"
    },
    {
      "name": "search-the-web",
      "reason": "forbidden during run-uat"
    },
    {
      "name": "WebSearch",
      "reason": "forbidden during run-uat"
    },
    {
      "name": "Bash",
      "reason": "forbidden during run-uat"
    },
    {
      "name": "Write",
      "reason": "forbidden during run-uat"
    },
    {
      "name": "Edit",
      "reason": "forbidden during run-uat"
    }
  ],
  "toolPresentationPlanId": "run-uat/default-v1"
}
```

## Gate

Aggregate UAT gate saved as pass.

## Manual Validation

One or more checks are marked `NEEDS-HUMAN` and require a person to validate:

- Validate the work here: /home/blasky/Projects/incremental-hub/.gsd-worktrees/M001
- This milestone runs in a git worktree, so the code lives under the GSD worktrees directory. Open it with: cd "/home/blasky/Projects/incremental-hub/.gsd-worktrees/M001"
- Follow the UAT checklist at: .gsd/phases/01-feasibility-and-qualification/01-02-UAT.md
<!-- gsd:state-version=106:0 -->
