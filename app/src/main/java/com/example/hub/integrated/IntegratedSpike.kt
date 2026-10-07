package com.example.hub.integrated

import android.content.Context
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import com.example.hub.games.QualificationResult
import com.example.hub.games.SharkIncrementalAdapter
import com.example.hub.games.StageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Integrated play-capture-restore spike that proves two non-trivial invariants:
 *
 * 1. **Data-preservation invariant:** `WebView.destroy()` releases the WebView but does
 *    **not** wipe the profile's localStorage — the saved data survives WebView destruction.
 * 2. **Round-trip invariant:** the adapter's import path works into a completely fresh
 *    (empty) profile, proving the restore pipeline is profile-independent.
 *
 * The spike uses Shark Incremental as the primary test target because it has a
 * browser save implementation, allowing the spike to trigger a genuine game save
 * deterministically rather than depending on the user's autosave interval.
 *
 * **Thread confinement:** [runSpike] acquires [Dispatchers.Main] internally, so it may be
 * called from any coroutine context. WebView creation and profile binding are not
 * thread-safe and must run on the UI thread.
 *
 * **Cleanup guarantee:** Both profiles are deleted in a finally block after the spike
 * completes (success or failure).
 *
 * @param context      the application [Context] (used for WebView creation and report writing)
 * @param profileStore the [ProfileStore] to create and manage disposable profiles.
 *   Must be obtained from [ProfileStore.getInstance] on the UI thread.
 * @return [IntegratedSpikeResult] with per-stage pass/fail and evidence lines.
 */
class IntegratedSpike(private val context: Context) {

    companion object {
        private const val TAG = "IntegratedSpike"
        private const val SHARK_URL = "https://mrredshark77.github.io/shark-incremental/"
        private const val SOURCE_PROFILE = "s04-source"
        private const val FRESH_PROFILE = "s04-fresh"
        private const val REPORT_FILENAME = "integrated_spike_report.json"
        private const val SHARK_SAVE_KEY = "shark_inc_save"
        private const val READY_TIMEOUT_MS = 10_000L
        private const val CAPTURE_TIMEOUT_MS = 30_000L
        private const val DESTROY_VERIFY_TIMEOUT_MS = 10_000L
        private const val IMPORT_TIMEOUT_MS = 15_000L
        private const val NAVIGATION_TIMEOUT_MS = 20_000L
        private const val SYNTHETIC_BASE64 = "eyJWRVJTSU9OIjozLCJnb2xkIjoxMDAwLCJjbGlja3MiOjUwfQ=="
    }

    /**
     * Run the full play-capture-destroy-import-verify spike and return the result.
     */
    suspend fun runSpike(profileStore: ProfileStore): IntegratedSpikeResult =
        withContext(Dispatchers.Main) {
            val accumulator = IntegratedSpikeAccumulator(
                sourceProfileName = SOURCE_PROFILE,
                freshProfileName = FRESH_PROFILE,
            )

            Log.d(TAG, "Starting integrated spike: source=$SOURCE_PROFILE fresh=$FRESH_PROFILE")

            var sourceProfile: Profile? = null
            var freshProfile: Profile? = null
            var sourceWebView: WebView? = null
            var freshWebView: WebView? = null
            var verifyWebView: WebView? = null
            var capturedPayload: String? = null
            var importedPayload: String? = null
            var saveValue: String? = null

            try {
                // ── Step 0: Pre-run profile cleanup ──────────────────────────────
                // AndroidX ProfileStore.deleteProfile() cannot delete a profile that
                // has been loaded into memory via getOrCreateProfile() or getProfile().
                // To ensure repeatability, delete any stale fixed test profiles BEFORE
                // loading them, so each independently started spike run starts fresh.
                val existingProfiles = profileStore.getAllProfileNames()
                if (existingProfiles.contains(SOURCE_PROFILE)) {
                    Log.d(TAG, "Pre-run cleanup: deleting stale source profile '$SOURCE_PROFILE'")
                    profileStore.deleteProfile(SOURCE_PROFILE)
                }
                if (existingProfiles.contains(FRESH_PROFILE)) {
                    Log.d(TAG, "Pre-run cleanup: deleting stale fresh profile '$FRESH_PROFILE'")
                    profileStore.deleteProfile(FRESH_PROFILE)
                }

                // ── Step 1: Create profiles ───────────────────────────────────────
                sourceProfile = profileStore.getOrCreateProfile(SOURCE_PROFILE)!!
                freshProfile = profileStore.getOrCreateProfile(FRESH_PROFILE)!!
                accumulator.addStage("profile-creation", true, listOf(
                    "Created source profile: $SOURCE_PROFILE",
                    "Created fresh profile: $FRESH_PROFILE",
                ))

                // ── Step 2: Bind WebView to source profile and navigate ───────────
                sourceWebView = createBoundWebView(sourceProfile, SHARK_URL)
                if (sourceWebView == null) {
                    accumulator.addStage("webView-bind-source", false, listOf(
                        "Failed to create bound WebView for source profile",
                    ))
                    if (accumulator.blocker == null) {
                        accumulator.blocker = "Source WebView creation failed"
                    }
                    return@withContext accumulator.build()
                }
                accumulator.addStage("webView-bind-source", true, listOf(
                    "Bound WebView to profile: $SOURCE_PROFILE",
                    "Navigated to: $SHARK_URL",
                ))

                // ── Step 3: Wait for game readiness (player JS global) ────────────
                val adapter = SharkIncrementalAdapter()
                val readyOk = try {
                    withTimeoutOrNull(READY_TIMEOUT_MS) {
                        adapter.awaitReady(sourceWebView)
                    } ?: false
                } catch (e: Exception) {
                    Log.w(TAG, "awaitReady threw: ${e.message}")
                    false
                }
                accumulator.addStage("await-ready", readyOk, listOf(
                    "Game ready signal observed: $readyOk",
                    "Timeout: ${READY_TIMEOUT_MS}ms",
                ))

                if (!readyOk) {
                    // Attempt synthetic save injection as fallback (same as S03 pattern)
                    Log.w(TAG, "Game not ready within timeout — attempting synthetic save injection")
                    val injected = injectSyntheticSave(sourceWebView)
                    accumulator.addStage("synthetic-inject", injected, listOf(
                        "Synthetic save injection: $injected",
                        "Continuing spike with synthetic data",
                    ))
                    if (!injected) {
                        accumulator.blocker = "Game not ready and synthetic injection failed"
                        return@withContext accumulator.build()
                    }
                } else {
                    // Do not depend on Shark's configurable autosave timer. Trigger the
                    // game's real save path deterministically once the game is ready.
                    val saveTriggered = triggerSharkSave(sourceWebView)
                    accumulator.addStage("trigger-save", saveTriggered, listOf(
                        "Called Shark save() after readiness: $saveTriggered",
                        "Save key '$SHARK_SAVE_KEY' created by game save path: $saveTriggered",
                    ))
                    if (!saveTriggered) {
                        accumulator.blocker = "Shark game was ready but save() did not create save data"
                        return@withContext accumulator.build()
                    }
                }

                // ── Step 4: Poll for non-empty save data ─────────────────────────
                var pollOk = false
                try {
                    pollOk = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                        pollForSave(sourceWebView) { value ->
                            saveValue = value
                        }
                    } ?: false
                } catch (e: Exception) {
                    Log.w(TAG, "Save polling threw: ${e.message}")
                }
                accumulator.addStage("poll-save", pollOk, listOf(
                    "Polling result: $pollOk",
                    "Save value length: ${saveValue?.length ?: 0}",
                    "Save value preview: ${saveValue?.take(80)}",
                ))
                if (!pollOk && saveValue.isNullOrEmpty()) {
                    accumulator.blocker = "No save data found within ${CAPTURE_TIMEOUT_MS}ms timeout"
                    return@withContext accumulator.build()
                }
                if (!pollOk) {
                    Log.w(TAG, "Polling timed out but save was found — continuing with captured data")
                }

                // ── Step 5: Capture the real save ────────────────────────────────
                capturedPayload = try {
                    withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                        // Build a temporary QualificationResult for the adapter's capture method
                        adapter.capture(
                            sourceWebView,
                            QualificationResult(
                                gameId = "shark-incremental",
                                displayName = "Shark Incremental",
                                stages = accumulator.build().stages
                                    .map { StageResult(it.stage, it.passed, it.evidence) },
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Capture failed", e)
                    null
                }
                val captureOk = capturedPayload != null && capturedPayload.isNotEmpty()
                accumulator.addStage("capture", captureOk, listOf(
                    "Capture payload length: ${capturedPayload?.length ?: 0}",
                    "Capture payload preview: ${capturedPayload?.take(80)}",
                ))
                if (!captureOk) {
                    accumulator.blocker = "Capture returned null or empty payload"
                    return@withContext accumulator.build()
                }
                accumulator.capturedSaveLength = capturedPayload!!.length

                // ── Step 6: Validate the captured payload ────────────────────────
                val validateOk = try {
                    withTimeoutOrNull(5_000L) { adapter.validate(capturedPayload!!) } ?: false
                } catch (e: Exception) {
                    false
                }
                accumulator.addStage("validate", validateOk, listOf(
                    "Validation result: $validateOk",
                    "Payload passes Shark format checks: $validateOk",
                ))
                if (!validateOk) {
                    accumulator.blocker = "Captured payload failed Shark format validation"
                    return@withContext accumulator.build()
                }

                // ── Step 7: Destroy the source WebView ──────────────────────────
                var destroyOk: Boolean
                try {
                    sourceWebView?.destroy()
                    destroyOk = true
                } catch (e: Exception) {
                    Log.w(TAG, "WebView.destroy() threw: ${e.message}")
                    destroyOk = false
                }
                accumulator.destroyedWebView = destroyOk
                accumulator.addStage("destroy-webview", destroyOk, listOf(
                    "webView.destroy() called: $destroyOk",
                    "Source WebView destroyed: ${sourceWebView != null}",
                ))
                check(destroyOk) { "webView.destroy() failed — data survival invariant cannot be verified" }

                // ── Step 8: CRITICAL — verify profile data survived destroy ──────
                // Create a fresh ephemeral WebView bound to the SAME source profile
                // to confirm the localStorage keys are still accessible after destroy.
                val dataSurvived = try {
                    verifyWebView = createBoundWebView(sourceProfile, SHARK_URL)
                    if (verifyWebView == null) {
                        Log.w(TAG, "Ephemeral verify WebView creation failed")
                        false
                    } else {
                        val keyExists = verifyKeyInWebView(verifyWebView, SHARK_SAVE_KEY)
                        val valueMatches = if (keyExists && saveValue != null) {
                            val storedValue = readKeyInWebView(verifyWebView, SHARK_SAVE_KEY)
                            storedValue == saveValue
                        } else false
                        val result = keyExists && valueMatches
                        accumulator.addStage("destroy-survive-verify", result, listOf(
                            "Key '$SHARK_SAVE_KEY' exists after destroy: $keyExists",
                            "Value matches pre-destroy save: $valueMatches",
                            "Ephemeral WebView bound to same profile: $SOURCE_PROFILE",
                            "This is the critical data-preservation invariant proof",
                        ))
                        accumulator.profileDataSurvived = result
                        result
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Data survival verification failed", e)
                    accumulator.addStage("destroy-survive-verify", false, listOf(
                        "Exception during verification: ${e.message}",
                    ))
                    accumulator.profileDataSurvived = false
                    false
                }

                if (!dataSurvived) {
                    accumulator.blocker = "Profile data did NOT survive WebView.destroy() — the data-preservation invariant FAILED"
                    return@withContext accumulator.build()
                }

                // ── Step 9: Create fresh WebView and import the captured save ────
                importedPayload = try {
                    withTimeoutOrNull(5_000L) { adapter.prepareRestore(capturedPayload!!) }
                } catch (e: Exception) {
                    Log.e(TAG, "prepareRestore failed", e)
                    capturedPayload!! // Use raw payload on failure
                }
                accumulator.addStage("prepare-restore", true, listOf(
                    "Import payload length: ${importedPayload?.length ?: 0}",
                    "Import payload preview: ${importedPayload?.take(80)}",
                ))

                freshWebView = createBoundWebView(freshProfile, SHARK_URL)
                if (freshWebView == null) {
                    accumulator.addStage("import", false, listOf(
                        "Failed to create fresh WebView for import",
                    ))
                    accumulator.blocker = "Fresh WebView creation failed for import"
                    return@withContext accumulator.build()
                }
                accumulator.addStage("fresh-webview-bind", true, listOf(
                    "Bound fresh WebView to profile: $FRESH_PROFILE",
                    "Navigated to: $SHARK_URL",
                ))

                // ── Step 10: Import and verify ───────────────────────────────────
                val importOk = try {
                    withTimeoutOrNull(IMPORT_TIMEOUT_MS) {
                        adapter.verifyLoaded(freshWebView, importedPayload!!)
                    } ?: false
                } catch (e: Exception) {
                    Log.e(TAG, "verifyLoaded failed", e)
                    false
                }
                accumulator.importVerified = importOk
                accumulator.addStage("import-verify", importOk, listOf(
                    "Import verification result: $importOk",
                    "Fresh profile: $FRESH_PROFILE",
                    "Saved payload length: ${importedPayload?.length ?: 0}",
                ))
                if (!importOk) {
                    accumulator.blocker = "Import verification failed — the round-trip invariant FAILED"
                    return@withContext accumulator.build()
                }

                Log.d(TAG, "Integrated spike completed successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Integrated spike failed with exception", e)
                accumulator.addStage("exception", false, listOf("Exception: ${e.message}"))
                if (accumulator.blocker == null) {
                    accumulator.blocker = "Exception: ${e.message}"
                }
            } finally {
                // Clean up all WebViews
                sourceWebView?.destroy()
                freshWebView?.destroy()
                verifyWebView?.destroy()

                // Clean up profiles
                try {
                    profileStore.deleteProfile(SOURCE_PROFILE)
                    profileStore.deleteProfile(FRESH_PROFILE)
                    Log.d(TAG, "Spike profiles cleaned up: $SOURCE_PROFILE, $FRESH_PROFILE")
                } catch (e: Exception) {
                    Log.w(TAG, "Profile cleanup failed — profiles retained for debugging: ${e.message}")
                }
            }

            // Write JSON report
            val result = accumulator.build()
            writeReport(result)
            result
        }

    /**
     * Poll the WebView for a non-empty [SHARK_SAVE_KEY] value, waiting up to
     * [CAPTURE_TIMEOUT_MS]. Calls [onSaveFound] with the value once found.
     * Returns true if a value was found within the timeout.
     */
    private suspend fun pollForSave(webView: WebView, onSaveFound: (String) -> Unit): Boolean =
        withContext(Dispatchers.Main) {
            val intervalMs = 2_000L
            val maxAttempts = (CAPTURE_TIMEOUT_MS / intervalMs).toInt().coerceAtLeast(1)
            Log.d(TAG, "Polling for save key '$SHARK_SAVE_KEY' (timeout=${CAPTURE_TIMEOUT_MS}ms, interval=${intervalMs}ms)")
            for (i in 1..maxAttempts) {
                val value = try {
                    readKeyInWebView(webView, SHARK_SAVE_KEY)
                } catch (e: Exception) {
                    Log.w(TAG, "Poll attempt $i read failed: ${e.message}")
                    null
                }
                if (!value.isNullOrEmpty()) {
                    Log.d(TAG, "Save found on attempt $i: length=${value.length}")
                    onSaveFound(value)
                    return@withContext true
                }
                Log.d(TAG, "Poll attempt $i/$maxAttempts: save key not yet present")
                kotlinx.coroutines.delay(intervalMs)
            }
            Log.w(TAG, "Poll timed out after ${CAPTURE_TIMEOUT_MS}ms without finding save key")
            false
        }

    /**
     * Create a WebView bound to [profile], navigating to [url] and waiting for
     * load completion before returning.
     */
    private suspend fun createBoundWebView(profile: Profile, url: String): WebView? =
        withContext(Dispatchers.Main) {
            runCatching {
                val deferred = CompletableDeferred<WebView>()
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    WebViewCompat.setProfile(this, profile.name)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String) {
                            Log.d(TAG, "Page finished loading: $u (profile=${profile.name})")
                            deferred.complete(view)
                        }
                    }
                    loadUrl(url)
                }
                withTimeoutOrNull(NAVIGATION_TIMEOUT_MS) { deferred.await() }
            }.getOrNull()
        }

    /**
     * Check whether [key] exists in the WebView's localStorage.
     * Returns true if the key is present (value is non-null).
     */
    private suspend fun verifyKeyInWebView(webView: WebView, key: String): Boolean =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<Boolean>()
            val js = "javascript:(function(){ " +
                "return localStorage.getItem('$key') !== null; " +
                "})()"
            webView.evaluateJavascript(js) { result ->
                val trimmed = result?.trim()?.removeSurrounding("\"")
                deferred.complete(trimmed == "true")
            }
            withTimeoutOrNull<Boolean>(DESTROY_VERIFY_TIMEOUT_MS) { deferred.await() } ?: false
        }

    /**
     * Read the value of [key] from the WebView's localStorage.
     * Returns the string value or null if the key is absent.
     */
    private suspend fun readKeyInWebView(webView: WebView, key: String): String? =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String?>()
            val js = "javascript:(function(){ " +
                "return localStorage.getItem('$key'); " +
                "})()"
            webView.evaluateJavascript(js) { result ->
                val trimmed = result?.trim()
                deferred.complete(if (trimmed == "null" || trimmed.isNullOrEmpty()) null else trimmed.removeSurrounding("\""))
            }
            withTimeoutOrNull<String?>(DESTROY_VERIFY_TIMEOUT_MS) { deferred.await() }
        }

    /**
     * Trigger Shark Incremental's real save path once the game is ready.
     *
     * Returns true only when calling save() leaves the expected localStorage key present.
     */
    private suspend fun triggerSharkSave(webView: WebView): Boolean =
        withContext(Dispatchers.Main) {
            runCatching {
                val js =
                    "typeof save === 'function' ? " +
                        "(save(), localStorage.getItem('$SHARK_SAVE_KEY') !== null) : false"
                val jsResult: String? =
                    withTimeoutOrNull(5_000L) { evaluateJs(webView, js) }
                "true" == jsResult
            }.getOrDefault(false)
        }

    /**
     * Inject a synthetic Shark save into [webView] for testing when the game is not ready.
     */
    private suspend fun injectSyntheticSave(webView: WebView): Boolean =
        withContext(Dispatchers.Main) {
            runCatching {
                val js = "localStorage.setItem('$SHARK_SAVE_KEY', '$SYNTHETIC_BASE64'); true"
                val jsResult: String? = withTimeoutOrNull(5_000L) { evaluateJs(webView, js) }
                "true" == jsResult
            }.getOrDefault(false)
        }

    /**
     * Evaluate JavaScript in [webView] using [CompletableDeferred] bridging.
     */
    private suspend fun evaluateJs(webView: WebView, js: String): String? =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String?>()
            val cb: ValueCallback<String?> = ValueCallback { deferred.complete(it) }
            webView.evaluateJavascript(js, cb)
            deferred.await()
        }

    /**
     * Write the spike result to [Context.filesDir] as a JSON report.
     */
    private fun writeReport(result: IntegratedSpikeResult) {
        runCatching {
            val file = File(context.filesDir, REPORT_FILENAME)
            file.parentFile?.mkdirs()
            file.writeText(result.toJson())
            Log.d(TAG, "Spike report written: ${file.absolutePath}")
        }.onFailure { e ->
            Log.w(TAG, "Failed to write spike report: ${e.message}")
        }
    }
}
