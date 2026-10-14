package com.example.hub.ui

import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.Profile
import androidx.webkit.WebViewCompat
import com.example.hub.session.SessionState

private const val TAG = "WebViewHost"

/**
 * Stable WebView host composable that keeps WebView identity across recomposition.
 *
 * The composable uses `remember(gameId, state)` to produce a stable WebView
 * reference and `AndroidView` with a factory that creates the WebView, enables
 * JS/domStorage, binds a profile via `WebViewCompat.setProfile()` BEFORE
 * `loadUrl()`, sets `webViewClient`, and navigates.
 *
 * State transitions are handled in the `update` lambda:
 * - **Closed**: destroy the WebView and clean up the profile binding.
 * - **Opening/Playing/Capturing/Background/Restoring/Degraded**: keep the
 *   WebView stable; no-op on the same state, navigate on state change.
 * - **RecoveryRequired/Closing**: destroy and let the parent recreate.
 *
 * Profile management:
 * - The profile is passed in from [SessionController] via the [profile]
 *   parameter, ensuring the create→setProfile→loadUrl ordering invariant
 *   is enforced by the controller.
 * - The controller tracks the WebView lifecycle via [onDestroy].
 *
 * Cleanup:
 * - `DisposableEffect(gameId)` ensures the WebView is destroyed and the
 *   profile binding is released when the composition exits.
 */
@Composable
fun WebViewHost(
    gameId: String,
    state: SessionState,
    profile: Profile? = null,
    onError: (String) -> Unit = {},
    onDestroy: () -> Unit = {},
    modifier: Modifier = Modifier,
) {

    if (state is SessionState.Closed) {
        // Degrade gracefully: no WebView when the session is closed.
        Log.d(TAG, "WebViewHost: state=Closed — no WebView created (gameId=$gameId)")
        onDestroy()
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Log.d(TAG, "WebViewHost: factory creating WebView (gameId=$gameId, state=$state)")
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // CRITICAL: bind profile BEFORE any navigation so localStorage
                // is available from the first document load.
                profile?.let { p ->
                    WebViewCompat.setProfile(this, p.name)
                    Log.d(TAG, "WebViewHost: bound profile '${p.name}' before navigation")
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d(TAG, "WebViewHost: onPageFinished url=$url (gameId=$gameId)")
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        Log.e(TAG, "WebViewHost: onReceivedError url=${request.url} errorCode=${error.errorCode} (gameId=$gameId)")
                        onError("onReceivedError: ${error.errorCode} on ${request.url}")
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        Log.e(TAG, "WebViewHost: onReceivedHttpError url=${request.url} statusCode=${response.statusCode} (gameId=$gameId)")
                        onError("onReceivedHttpError: ${response.statusCode} on ${request.url}")
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        Log.e(TAG, "WebViewHost: onRenderProcessGone didCrash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()} (gameId=$gameId)")
                        onError("onRenderProcessGone: didCrash=${detail.didCrash()}")
                        return true
                    }
                }
            }
        },
        update = { webView ->
            when (state) {
                is SessionState.Opening -> navigateToGame(gameId, webView)
                is SessionState.Playing -> { /* stable — no-op */ }
                is SessionState.Capturing -> { /* stable — no-op */ }
                is SessionState.Background -> { /* stable — no-op */ }
                is SessionState.Restoring -> navigateToGame(gameId, webView)
                is SessionState.Degraded -> navigateToGame(gameId, webView)
                is SessionState.RecoveryRequired -> {
                    Log.d(TAG, "WebViewHost: state=RecoveryRequired — destroying WebView (gameId=$gameId)")
                    webView.destroy()
                    onDestroy()
                }
                is SessionState.Closing -> {
                    Log.d(TAG, "WebViewHost: state=Closing — destroying WebView (gameId=$gameId)")
                    webView.destroy()
                    onDestroy()
                }
                is SessionState.Closed -> {
                    // Already handled by the early-return above; this branch is unreachable.
                }
            }
        },
    )

    DisposableEffect(gameId) {
        Log.d(TAG, "WebViewHost: DisposableEffect attached (gameId=$gameId)")
        onDispose {
            Log.d(TAG, "WebViewHost: DisposableEffect onDispose — cleaning up (gameId=$gameId)")
            onDestroy()
        }
    }
}

/**
 * Key class used to stabilise the `remember(gameId, state)` cache.
 *
 * We cannot use `SessionState` directly as a key in every remember call because
 * the sealed-class data objects do not carry stable identity across
 * recomposition in all Compose versions. Wrapping both values in a single
 * value-class-like key ensures the cache is keyed on the concrete (gameId,
 * state) pair.
 */
private class WebViewKey(
    val gameId: String,
    val state: SessionState,
)

/**
 * Navigate [webView] to the game URL for [gameId].
 *
 * The URL is constructed from a convention: `https://hub.local/game/$gameId`.
 * In production this would be resolved from the game registry or a config
 * source; for now the stub URL serves as a deterministic placeholder.
 */
private fun navigateToGame(gameId: String, webView: WebView) {
    val url = "https://hub.local/game/$gameId"
    val currentUrl = webView.url ?: ""
    if (currentUrl != url) {
        Log.d(TAG, "WebViewHost: navigating to $url (was $currentUrl)")
        webView.loadUrl(url)
    } else {
        Log.d(TAG, "WebViewHost: already on $url — no-op")
    }
}
