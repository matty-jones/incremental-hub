# S01: WebView Profile Capability Spike — Research

## Summary

S01 delivers a minimal Android app that detects support for three non-negotiable WebView features on the target Pixel Fold: `MULTI_PROFILE`, `DOCUMENT_START_SCRIPT`, and `WEB_MESSAGE_LISTENER`. Results are reported in a native capability screen. The spike also creates and inspects named WebView profiles to prove profile-scoped isolation at a basic level.

**First proof:** Runtime feature detection via `WebViewFeature.isFeatureSupported()`. This is the highest-risk unknown because the Pixel Fold's installed Android System WebView version determines availability. If any feature is missing, the full profile-isolated integration path is blocked and remediation must be shown.

---

## Required APIs and Feature Availability

### 1. WebViewFeature.isFeatureSupported()

The central detection API. All three features are queried the same way:

```kotlin
val hasMultiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
val hasDocStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
val hasWebMessageListener = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
```

Returns `true` if the installed Android System WebView supports the feature, `false` otherwise. Never infer from Android OS version alone.

### 2. ProfileStore (requires MULTI_PROFILE)

```kotlin
// Thread-confined to UI thread
val profileStore = ProfileStore.getInstance()  // @RequiresFeature(MULTI_PROFILE)
val profile = profileStore.getOrCreateProfile("test-profile-1")
val allNames = profileStore.getAllProfileNames()
profileStore.deleteProfile("test-profile-1")
```

Profile properties accessible only when feature is supported:
- `profile.getName()` — returns profile name
- `profile.getCookieManager()` — profile-scoped cookie manager
- `profile.getWebStorage()` — profile-scoped WebStorage
- `profile.getServiceWorkerController()` — profile-scoped service worker controller

### 3. WebViewCompat.addWebMessageListener (requires WEB_MESSAGE_LISTENER)

```kotlin
WebViewCompat.addWebMessageListener(
    webView,
    "AndroidBridge",
    setOf("https://example.com"),  // origin rules — never "*"
    object : WebViewCompat.WebMessageListener {
        override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
            // handle message
        }
    }
)
```

### 4. WebViewCompat.addDocumentStartJavaScript (requires DOCUMENT_START_SCRIPT)

```kotlin
val handler = WebViewCompat.addDocumentStartJavaScript(
    webView,
    "window.__hubBootstrap = true;",
    setOf("https://example.com")
)
handler.remove()  // cleanup when done
```

### 5. WebViewAssetLoader (for native HTML bootstrap)

```kotlin
val assetLoader = WebViewAssetLoader.Builder()
    .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context))
    .build()

webView.setAssetLoader(assetLoader)
webView.loadUrl("https://appassets.androidplatform.net/bootstrap.html")
```

### 6. WebViewCompat.setProfile / getProfile (requires MULTI_PROFILE)

```kotlin
WebViewCompat.setProfile(webView, "my-profile-name")
val profile: Profile = WebViewCompat.getProfile(webView)
```

**Critical ordering:** Bind profile before configuring scripts or navigating.

---

## Capability Report Structure

The capability screen and report must document:

| Field | Value |
|---|---|
| WebView provider package | e.g., `com.google.android.webview` |
| WebView provider version | e.g., `119.0.6045.123` |
| `MULTI_PROFILE` supported | `true` / `false` |
| `DOCUMENT_START_SCRIPT` supported | `true` / `false` |
| `WEB_MESSAGE_LISTENER` supported | `true` / `false` |
| All three supported | `true` / `false` |
| Profile isolation test result | `passed` / `failed` / `skipped` |

If any required feature is missing, the report must show remediation guidance.

---

## Files and Natural Seams

### Seam 1: Feature Detection Module (pure logic, no Android dependency)

**File:** `app/src/main/java/com/example/hub/diagnostics/CapabilityDetector.kt`

```kotlin
data class WebViewCapabilities(
    val multiProfile: Boolean,
    val documentStartScript: Boolean,
    val webMessageListener: Boolean,
    val allSupported: Boolean,
    val webViewPackage: String?,
    val webViewVersion: String?
) {
    val isSupported = allSupported && multiProfile && documentStartScript && webMessageListener
}

class CapabilityDetector(private val context: Context) {
    fun detect(): WebViewCapabilities {
        val packageInfo = WebViewCompat.getCurrentWebViewPackage(context)
        return WebViewCapabilities(
            multiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
            documentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
            webMessageListener = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
            allSupported = /* compute */,
            webViewPackage = packageInfo?.packageName,
            webViewVersion = packageInfo?.versionName
        )
    }
}
```

**Verification:** Unit test with mocked `WebViewFeature.isFeatureSupported()` calls.

### Seam 2: Profile Isolation Test

**File:** `app/src/main/java/com/example/hub/diagnostics/ProfileIsolationTest.kt`

```kotlin
class ProfileIsolationTest {
    suspend fun test(profileStore: ProfileStore): TestResult {
        // 1. Create two profiles with same origin
        // 2. Write localStorage key "test-key" in profile A
        // 3. Verify key "test-key" is NOT visible in profile B
        // 4. Clean up profiles
    }
}
```

**Verification:** Android instrumentation test on real device.

### Seam 3: Capability Screen (Compose UI)

**File:** `app/src/main/java/com/example/hub/ui/CapabilityScreen.kt`

```kotlin
@Composable
fun CapabilityScreen(capabilities: WebViewCapabilities, onRefresh: () -> Unit) {
    Column {
        Text("WebView Capability Report")
        FeatureRow("MULTI_PROFILE", capabilities.multiProfile)
        FeatureRow("DOCUMENT_START_SCRIPT", capabilities.documentStartScript)
        FeatureRow("WEB_MESSAGE_LISTENER", capabilities.webMessageListener)
        Divider()
        Text("WebView: ${capabilities.webViewPackage} v${capabilities.webViewVersion}")
        if (!capabilities.allSupported) {
            Text("REMEDIATION: Update Android System WebView or use fallback path")
        }
    }
}
```

**File:** `app/src/main/java/com/example/hub/ui/DiagnosticsScreen.kt`

Hosts the capability screen + profile isolation test result.

### Seam 4: Main Activity / Composition Root

**File:** `app/src/main/java/com/example/hub/MainActivity.kt`

Single Activity with Compose navigation. Default route: `DiagnosticsScreen`.

### Seam 5: Build Configuration

**File:** `app/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.hub"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.hub"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.material3:material3:1.1.0")
    implementation("androidx.activity:activity-compose:1.8.0")
}
```

---

## Implementation Landscape

### Approach A: Minimal Single-Activity App (Recommended for S01)

One Activity, one Compose screen, pure capability detection + profile test. No Room, no vault, no game integration. This is the right scope for a spike.

**Pros:**
- Fast to build and iterate
- Minimal dependency surface
- Clear separation between detection and production code

**Cons:**
- Requires creating new project structure from scratch

### Approach B: Extend Existing Project

If an Android project already exists in the parent repo, extend it. Current check shows only `.gsd-worktrees/M001` exists — no existing Android project.

### Approach C: Pure Kotlin/JVM Test with Mock WebView

Not viable for proving real device feature support. Must run on Pixel Fold.

---

## First Proof and Highest Risk

**First proof:** `WebViewFeature.isFeatureSupported()` calls on the Pixel Fold.

This is the highest-risk item because:
1. The Pixel Fold's WebView version is unknown at planning time
2. If `MULTI_PROFILE` is missing, the entire profile-isolated path is blocked
3. If `DOCUMENT_START_SCRIPT` is missing, native bootstrap ordering cannot be proven
4. If `WEB_MESSAGE_LISTENER` is missing, bridge communication is blocked

**Risk mitigation:** Report all three features independently. If any are missing, show remediation guidance in the UI rather than crashing.

---

## Verification Strategy

### Contract Verification (automated)
- Feature detection results logged to file or printed to logcat
- Profile isolation test passes: two profiles at same origin, localStorage keys do not leak
- Capability report file written to app-private storage

### Integration Verification (device)
- Install signed build on Pixel Fold
- Run capability screen, confirm all three detections
- Run profile isolation test, confirm isolation works

### UAT / Human Verification
- Device tester confirms feature detection results match expectations
- Device tester confirms profile isolation test passes

---

## Open Questions for Planner

1. **Package naming:** What application ID to use? (`com.example.hub` is placeholder)
2. **WebView version pinning:** Spec says pin in M0. Should we pin 1.14.0 or use a range?
3. **Profile cleanup:** Should the spike clean up test profiles on exit, or leave them for debugging?
4. **Report output:** Capability report as JSON file, logcat, or both?
5. **Compose BOM version:** Spec says 2024.09.00. Should we use that or the latest stable?

---

## Decisions to Record

- **D001:** Use `androidx.webkit:webkit:1.14.0` as target version; verify on device and pin final version in M0 completion.
- **D002:** Package name `com.example.hub` for spike; replace with final product ID in M1.
- **D003:** Capability report written as JSON to `filesDir/capability_report.json` and displayed in UI.
- **D004:** Test profiles are cleaned up after isolation test; if test fails, profiles are retained for debugging.
<!-- gsd:state-version=106:0 -->
