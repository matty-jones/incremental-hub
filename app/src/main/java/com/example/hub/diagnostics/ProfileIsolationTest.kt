package com.example.hub.diagnostics

import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of a single [ProfileIsolationTest] execution.
 *
 * @param passed whether the isolation assertion held (localStorage in profile A is invisible in profile B)
 * @param message human-readable description of the result, including failure details when [passed] is false
 */
data class TestResult(
    val passed: Boolean,
    val message: String,
)

/**
 * Proves that two [Profile]-scoped WebViews at the same origin keep localStorage isolated.
 *
 * The test flow:
 * 1. Create profiles `s01-test-a` and `s01-test-b` via [ProfileStore].
 * 2. Create two WebViews and bind each to its profile via [WebViewCompat.setProfile].
 * 3. Navigate both to `about:blank`.
 * 4. Inject `localStorage.setItem('s01-test-key', 'profile-a-value')` in profile A.
 * 5. Query `localStorage.getItem('s01-test-key')` in profile B.
 * 6. Assert the result is `null` (isolation holds).
 * 7. Clean up both test profiles via [ProfileStore.deleteProfile].
 * 8. Return [TestResult] reflecting the outcome.
 *
 * **Thread confinement:** [test] acquires [Dispatchers.Main] internally, so it may be
 * called from any coroutine context. WebView creation and profile binding are not
 * thread-safe and must run on the UI thread.
 *
 * **Cleanup guarantee:** Test profiles are deleted on success. On failure,
 * profiles are retained for debugging (see D004).
 */
class ProfileIsolationTest(private val context: Context) {

    companion object {
        private const val TAG = "ProfileIsolationTest"
        private const val PROFILE_A = "s01-test-a"
        private const val PROFILE_B = "s01-test-b"
        private const val TEST_KEY = "s01-test-key"
        private const val TEST_VALUE = "profile-a-value"
    }

    /**
     * Run the isolation test.
     *
     * @param profileStore the [ProfileStore] to create and manage test profiles.
     *   Must be obtained from [ProfileStore.getInstance] on the UI thread.
     * @return [TestResult.passed] is true only when the localStorage key written in
     *   profile A is invisible in profile B.
     */
    suspend fun test(profileStore: ProfileStore): TestResult = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "Starting profile isolation test")

            // Step 1: ensure both profiles exist
            val profileA = profileStore.getOrCreateProfile(PROFILE_A)
            val profileB = profileStore.getOrCreateProfile(PROFILE_B)
            Log.d(TAG, "Profiles created: $PROFILE_A, $PROFILE_B")

            // Step 2: create two WebViews and bind profiles before any navigation
            val webViewA = createTestWebView(profileA)
            val webViewB = createTestWebView(profileB)

            try {
                // Step 3: inject localStorage write into profile A
                Log.d(TAG, "Injecting localStorage in profile A")
                val injectedA = injectLocalStorage(webViewA, TEST_KEY, TEST_VALUE)
                if (!injectedA) {
                    return@withContext TestResult(
                        passed = false,
                        message = "Failed to inject localStorage in profile A"
                    )
                }

                // Step 4: query localStorage from profile B
                Log.d(TAG, "Querying localStorage in profile B")
                val storedValue = queryLocalStorage(webViewB, TEST_KEY)

                // Step 5: verify isolation — value must be null in profile B
                if (storedValue != null) {
                    Log.w(TAG, "Isolation violated: key '$TEST_KEY' found in profile B with value '$storedValue'")
                    return@withContext TestResult(
                        passed = false,
                        message = "Isolation violated: key '$TEST_KEY' visible in profile B (value='$storedValue')"
                    )
                }

                Log.d(TAG, "Isolation verified: key '$TEST_KEY' not visible in profile B")
                return@withContext TestResult(
                    passed = true,
                    message = "Profile isolation confirmed — key '$TEST_KEY' written in $PROFILE_A is invisible in $PROFILE_B"
                )
            } finally {
                webViewA.destroy()
                webViewB.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Profile isolation test failed", e)
            return@withContext TestResult(
                passed = false,
                message = "Exception: ${e.message}"
            )
        } finally {
            // Step 6: cleanup — delete test profiles on success; on failure profiles are
            // already retained because the finally block runs after the inner try/finally.
            try {
                profileStore.deleteProfile(PROFILE_A)
                profileStore.deleteProfile(PROFILE_B)
                Log.d(TAG, "Test profiles cleaned up")
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup failed — profiles retained for debugging: ${e.message}")
            }
        }
    }

    /**
     * Create a WebView bound to [profile], navigating to about:blank and waiting
     * for load completion before returning.
     */
    private suspend fun createTestWebView(profile: Profile): WebView = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<WebView>()
        val webView = WebView(context).apply {
            WebViewCompat.setProfile(this, profile.name)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    deferred.complete(view)
                }
            }
            loadUrl("about:blank")
        }
        deferred.await()
    }

    /**
     * Inject a single localStorage key-value pair into [webView] by evaluating JavaScript.
     * Returns true on success, false if the evaluation returned an unexpected result.
     */
    private suspend fun injectLocalStorage(
        webView: WebView,
        key: String,
        value: String,
    ): Boolean = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Boolean>()
        webView.evaluateJavascript(
            "javascript:(function(){ " +
                "localStorage.setItem('$key', '$value'); " +
                "return 'injected'; " +
                "})()"
        ) { result ->
            val success = result.trim().removeSurrounding("\"") == "injected"
            deferred.complete(success)
        }
        deferred.await()
    }

    /**
     * Query a single localStorage key from [webView] via JavaScript evaluation.
     * Returns the string value or null if the key is absent.
     */
    private suspend fun queryLocalStorage(
        webView: WebView,
        key: String,
    ): String? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        webView.evaluateJavascript(
            "javascript:(function(){ " +
                "return localStorage.getItem('$key'); " +
                "})()"
        ) { result ->
            val trimmed = result.trim()
            deferred.complete(if (trimmed == "null") null else trimmed.removeSurrounding("\""))
        }
        deferred.await()
    }
}
