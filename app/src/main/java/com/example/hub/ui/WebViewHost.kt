package com.example.hub.ui

import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.Profile
import androidx.webkit.WebViewCompat
import com.example.hub.session.DocumentGate
import com.example.hub.session.SessionState
import com.example.hub.session.StorageBootstrap
import java.nio.charset.StandardCharsets

private const val TAG = "WebViewHost"

internal fun shouldCreateWebView(state: SessionState): Boolean =
    state !is SessionState.Closed &&
        state !is SessionState.Closing &&
        state !is SessionState.RecoveryRequired

internal fun shouldNavigate(profileName: String?, launchUrl: String?): Boolean =
    !profileName.isNullOrBlank() && !launchUrl.isNullOrBlank()

internal fun isMainFrameRequest(request: WebResourceRequest): Boolean = request.isForMainFrame

/**
 * Hosts one WebView for the active session.
 *
 * The view is created only while the session can show a page. Profile binding
 * happens before the first [WebView.loadUrl]. Ordinary recomposition updates
 * the same instance. The session controller is the only owner that destroys it.
 */
@Composable
fun WebViewHost(
    gameId: String,
    launchUrl: String?,
    state: SessionState,
    profile: Profile?,
    reloadEpoch: Int,
    loadToken: Int,
    documentGate: DocumentGate,
    onWebViewCreated: (WebView) -> Unit,
    onPageFinished: (String) -> Unit,
    onMainFrameError: (String) -> Unit,
    onSubresourceError: (String) -> Unit,
    onRendererGone: (String) -> Unit,
    onConsoleMessage: (String) -> Unit,
    onRelease: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!shouldCreateWebView(state)) {
        Log.d(TAG, "WebViewHost: no WebView for state=$state gameId=$gameId")
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Log.d(TAG, "WebViewHost: creating WebView gameId=$gameId url=$launchUrl")
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                val slot = Slot(
                    profileName = null,
                    appliedReloadEpoch = reloadEpoch,
                    appliedLoadToken = loadToken,
                )
                tag = slot
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val message = consoleMessage?.message() ?: return true
                        onConsoleMessage("${consoleMessage.messageLevel()}: $message")
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        if (!StorageBootstrap.shouldServe(
                                documentGate.phase,
                                request.url.toString(),
                                documentGate.launchUrl,
                                request.isForMainFrame,
                            )
                        ) {
                            return null
                        }
                        val html = StorageBootstrap.document(
                            documentGate.phase,
                            documentGate.storageKey,
                            documentGate.payload,
                        )
                        return WebResourceResponse(
                            "text/html",
                            "utf-8",
                            200,
                            "OK",
                            mapOf("Cache-Control" to "no-store"),
                            html.byteInputStream(StandardCharsets.UTF_8),
                        )
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d(TAG, "WebViewHost: onPageFinished url=$url gameId=$gameId")
                        if (url != "about:blank") onPageFinished(url)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        val message = "onReceivedError: ${error.errorCode} ${error.description} on ${request.url} (gameId=$gameId)"
                        if (isMainFrameRequest(request)) onMainFrameError(message) else onSubresourceError(message)
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        val message = "onReceivedHttpError: ${errorResponse.statusCode} on ${request.url} (gameId=$gameId)"
                        if (isMainFrameRequest(request)) onMainFrameError(message) else onSubresourceError(message)
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        onRendererGone(
                            "onRenderProcessGone: didCrash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()} (gameId=$gameId url=$launchUrl)",
                        )
                        return true
                    }
                }
                val profileName = profile?.name
                if (shouldNavigate(profileName, launchUrl)) {
                    WebViewCompat.setProfile(this, profileName!!)
                    slot.profileName = profileName
                    Log.d(TAG, "WebViewHost: bound profile '$profileName' before $launchUrl")
                    loadUrl(launchUrl!!)
                }
                onWebViewCreated(this)
            }
        },
        update = { webView ->
            val slot = (webView.tag as? Slot) ?: Slot(null, -1, -1).also { webView.tag = it }
            val profileName = profile?.name
            if (slot.profileName == null && shouldNavigate(profileName, launchUrl)) {
                WebViewCompat.setProfile(webView, profileName!!)
                slot.profileName = profileName
                Log.d(TAG, "WebViewHost: bound profile '$profileName' before $launchUrl")
                webView.loadUrl(launchUrl!!)
            }
            if (slot.profileName != null && launchUrl != null && loadToken != slot.appliedLoadToken) {
                slot.appliedLoadToken = loadToken
                Log.d(TAG, "WebViewHost: load token=$loadToken phase=${documentGate.phase}")
                webView.loadUrl(launchUrl)
            }
            if (slot.profileName != null &&
                launchUrl != null &&
                reloadEpoch != slot.appliedReloadEpoch &&
                state is SessionState.Playing
            ) {
                slot.appliedReloadEpoch = reloadEpoch
                Log.d(TAG, "WebViewHost: reload $launchUrl epoch=$reloadEpoch")
                webView.loadUrl(launchUrl)
            }
        },
        onRelease = { webView ->
            Log.d(TAG, "WebViewHost: composition released WebView gameId=$gameId")
            onRelease(webView)
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        },
    )
}

private class Slot(
    var profileName: String?,
    var appliedReloadEpoch: Int,
    var appliedLoadToken: Int,
)
