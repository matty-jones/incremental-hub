# Pixel Fold emulator and WebView capability

Checked on `PixelFold_API34` (`emulator-5556`) with emulator 36.1.9. `Pixel6_API34_PS` (`emulator-5554`) was not deleted and was not written. No new AVD was created.

## Root cause

The fold AVD and the phone AVD use the same system image, `system-images;android-34;google_apis_playstore;x86_64` version 14, hardware profile `pixel_fold`. The crash was not the system image, not a data-directory suffix, and not an application logic bug.

`ProfileStore.getInstance()` in androidx.webkit 1.14.0 throws when `WebViewFeature.MULTI_PROFILE` is unsupported:

```text
java.lang.UnsupportedOperationException: This method is not supported by the current version of the framework and the current WebView APK
    at androidx.webkit.ProfileStore.getInstance(ProfileStore.java:60)
    at com.example.hub.session.SessionController.<init>
```

The image's stock provider is `com.google.android.webview` 113.0.5672.136 (versionCode 567263637). The WebView factory log on the crashing launch was `Loading com.google.android.webview version 113.0.5672.136`. Multi-profile needs WebView 118 or newer. Compose never got a frame because the session controller constructs `ProfileStore` during the first composition.

The phone AVD already had provider 155.0.8059.43 (versionCode 805904308), signed with the same certificate as the system stub. Installing that APK onto the fold AVD with `adb install -r -d` replaced the provider. The APK is not in this repository. Wiping the fold userdata restores 113. `scripts/android/provision-webview.sh` installs it again from `${XDG_CACHE_HOME:-$HOME/.cache}/incremental-hub/webview/` only when the hash, package, and certificate match `scripts/android/webview-provider.pin`. An already-compatible provider is not replaced, and a newer same-certificate provider is not downgraded. `scripts/android/check-webview.sh` runs that provision step and still refuses instrumentation below major version 118. How to rebuild the cache is in `docs/pixel-fold-emulator.md`.

After the install, `dumpsys webviewupdate` reports current and preferred package `(com.google.android.webview, 155.0.8059.43)`. The Hub process stays up, `MainActivity` is displayed, and `FoldProfileContinuityTest` creates `hub-game-shark-incremental` plus a disposable profile.

## Postures

`cmd device_state state 0` selects the outer panel, physical size 1080x2092. `state 2` selects the inner panel, 2208x1840. `FoldProfileContinuityTest` kept one Shark WebView, its in-page token, and its page-finish count across both. Shark reached `JS_UI_READY`.

The hinge does the same display switch when no device-state override is set. After `cmd device_state state reset`, `scripts/android/posture.sh fold` (`adb emu sensor set hinge-angle0 0`) committed state `CLOSED`, physical size 1080x2092, active display `local:4619827551948147201`, with the inner display off. An earlier `cmd device_state state 2` leaves an override. While that override is set, moving `hinge-angle0` changes the base state and leaves the committed state `OPENED`. Reset the override before treating a hinge command as the thing that moved the screen.

## Browser capabilities

Disposable page, production-shaped `WebChromeClient` (console only, no dialog or file-chooser overrides), WebView 155.0.8059.43. `WebViewCapabilityProbeTest`.

| Call | Result |
| --- | --- |
| `alert()` | Appeared. JavaScript blocked until the dialog was accepted, then returned. |
| `confirm()` | Appeared. Same as `alert()`. |
| `prompt()` | Appeared. JavaScript blocked. The text field took the first accept key; moving to the button accepted the dialog and the call returned. |
| `<input type="file">` | Returned. A tap produced no DocumentsUI, file picker, or resolver window within 3 seconds. |
| `<a download href="data:text/plain,...">` | Returned. A tap produced no chooser and no notification for that file within 3 seconds. |

No production file picker or import UI was added. Trimps text import does not need one. NG+3 migrations that call `prompt()` will show a dialog and will stall until that dialog is accepted.
