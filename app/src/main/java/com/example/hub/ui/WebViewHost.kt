package com.example.hub.ui

import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.util.Log
import android.view.View
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
import com.example.hub.games.SharkLayout
import com.example.hub.session.DiagnosticSeverity
import com.example.hub.session.DocumentGate
import com.example.hub.session.SessionState
import com.example.hub.session.StorageBootstrap
import com.example.hub.session.classifyResourceFailure
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
 * Each [WebView.loadUrl] stamps [Slot.appliedLoadToken] before navigation starts.
 * [onPageStarted] copies that into [Slot.finishToken] so [onPageFinished] reports
 * the token of the document that began loading, not a later navigation.
 */
@Composable
fun WebViewHost(
    gameId: String,
    launchUrl: String?,
    state: SessionState,
    profile: Profile?,
    reloadEpoch: Int,
    loadToken: Int,
    textZoomPercent: Int,
    pageZoomPercent: Int,
    pageScaleMode: PageScaleMode,
    presentationEpoch: Int,
    documentGate: DocumentGate,
    onTakeRetained: () -> WebView?,
    onWebViewCreated: (WebView) -> Unit,
    onPageFinished: (url: String, finishToken: Int) -> Unit,
    onMainFrameError: (String) -> Unit,
    onResourceDiagnostic: (DiagnosticSeverity, String) -> Unit,
    onExplicitLoad: (String) -> Unit,
    onRendererGone: (String) -> Unit,
    onConsoleMessage: (String) -> Unit,
    onPresentation: (PresentationAck) -> Unit,
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
            val retained = onTakeRetained()
            if (retained != null) {
                (retained.context as? MutableContextWrapper)?.baseContext = ctx
                ensureLayoutListener(retained)
                Log.d(TAG, "WebViewHost: reattaching retained WebView gameId=$gameId")
                onResourceDiagnostic(
                    DiagnosticSeverity.INFO,
                    "Reattached retained WebView for $gameId without loadUrl.",
                )
                retained
            } else {
                Log.d(TAG, "WebViewHost: creating WebView gameId=$gameId url=$launchUrl")
                onResourceDiagnostic(DiagnosticSeverity.INFO, "Creating WebView for $gameId.")
                WebView(MutableContextWrapper(ctx)).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.textZoom = textZoomPercent
                    val slot = Slot(
                        profileName = null,
                        appliedReloadEpoch = reloadEpoch,
                        appliedLoadToken = loadToken,
                        finishToken = loadToken,
                        textZoomPercent = textZoomPercent,
                        pageZoomPercent = pageZoomPercent,
                        mode = pageScaleMode,
                        presentationEpoch = presentationEpoch,
                        appliedTextZoom = textZoomPercent,
                    )
                    tag = slot
                    ensureLayoutListener(this)
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

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            val current = view.tag as? Slot ?: return
                            current.finishToken = current.appliedLoadToken
                            Log.d(
                                TAG,
                                "WebViewHost: onPageStarted url=$url token=${current.finishToken} gameId=$gameId",
                            )
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            val current = view.tag as? Slot
                            val token = current?.finishToken ?: -1
                            Log.d(TAG, "WebViewHost: onPageFinished url=$url token=$token gameId=$gameId")
                            if (current != null) {
                                current.layoutAppliedForUrl = null
                                current.appliedPageZoom = -1
                                current.appliedMode = null
                                current.forceReconcile = true
                                reconcilePresentation(view, current)
                            }
                            if (url != "about:blank") onPageFinished(url, token)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            reportResource(
                                request,
                                "onReceivedError: ${error.errorCode} ${error.description} on ${request.url} (gameId=$gameId)",
                                null,
                            )
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse,
                        ) {
                            reportResource(
                                request,
                                "onReceivedHttpError: ${errorResponse.statusCode} on ${request.url} (gameId=$gameId)",
                                errorResponse.statusCode,
                            )
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            onRendererGone(
                                "onRenderProcessGone: didCrash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()} (gameId=$gameId url=$launchUrl)",
                            )
                            return true
                        }

                        private fun reportResource(request: WebResourceRequest, message: String, status: Int?) {
                            val severity = classifyResourceFailure(
                                request.url.toString(),
                                status,
                                isMainFrameRequest(request),
                            )
                            if (severity == DiagnosticSeverity.FATAL) {
                                onMainFrameError(message)
                            } else {
                                onResourceDiagnostic(severity, message)
                            }
                        }
                    }
                    val profileName = profile?.name
                    if (shouldNavigate(profileName, launchUrl)) {
                        WebViewCompat.setProfile(this, profileName!!)
                        slot.profileName = profileName
                        Log.d(TAG, "WebViewHost: bound profile '$profileName' before $launchUrl")
                        navigate(this, slot, launchUrl!!, loadToken, "initial", onExplicitLoad)
                    }
                    onWebViewCreated(this)
                }
            }
        },
        update = { webView ->
            val slot = (webView.tag as? Slot) ?: Slot(
                profileName = null,
                appliedReloadEpoch = -1,
                appliedLoadToken = -1,
                finishToken = -1,
                textZoomPercent = textZoomPercent,
                pageZoomPercent = pageZoomPercent,
                mode = pageScaleMode,
                presentationEpoch = presentationEpoch,
            ).also { webView.tag = it }
            ensureLayoutListener(webView)
            slot.textZoomPercent = textZoomPercent
            slot.pageZoomPercent = pageZoomPercent
            slot.mode = pageScaleMode
            slot.onAck = onPresentation
            if (presentationEpoch != slot.presentationEpoch) {
                slot.presentationEpoch = presentationEpoch
                slot.forceReconcile = true
            }
            reconcilePresentation(webView, slot)
            val profileName = profile?.name
            if (slot.profileName == null && shouldNavigate(profileName, launchUrl)) {
                WebViewCompat.setProfile(webView, profileName!!)
                slot.profileName = profileName
                Log.d(TAG, "WebViewHost: bound profile '$profileName' before $launchUrl")
                navigate(webView, slot, launchUrl!!, loadToken, "initial", onExplicitLoad)
            }
            if (slot.profileName != null && launchUrl != null && loadToken != slot.appliedLoadToken) {
                navigate(webView, slot, launchUrl, loadToken, "load-token", onExplicitLoad)
            }
            if (slot.profileName != null &&
                launchUrl != null &&
                reloadEpoch != slot.appliedReloadEpoch &&
                state is SessionState.Playing
            ) {
                navigate(webView, slot, launchUrl, loadToken, "reload-epoch", onExplicitLoad)
                slot.appliedReloadEpoch = reloadEpoch
            }
        },
        onRelease = { webView ->
            Log.d(TAG, "WebViewHost: composition released WebView gameId=$gameId")
            onRelease(webView)
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        },
    )
}

private fun navigate(
    webView: WebView,
    slot: Slot,
    url: String,
    loadToken: Int,
    reason: String,
    onExplicitLoad: (String) -> Unit,
) {
    slot.layoutAppliedForUrl = null
    slot.appliedPageZoom = -1
    // Stamp before loadUrl so onPageStarted/onPageFinished can attribute the document.
    slot.appliedLoadToken = loadToken
    slot.finishToken = loadToken
    Log.d(TAG, "WebViewHost: loadUrl reason=$reason token=$loadToken url=$url")
    onExplicitLoad(reason)
    webView.loadUrl(url)
}

private fun ensureLayoutListener(webView: WebView) {
    val slot = webView.tag as? Slot ?: return
    if (slot.listener != null) return
    val listener = View.OnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
        val current = view.tag as? Slot ?: return@OnLayoutChangeListener
        val width = right - left
        val height = bottom - top
        if (width == current.viewWidth && height == current.viewHeight) return@OnLayoutChangeListener
        current.viewWidth = width
        current.viewHeight = height
        current.forceReconcile = true
        Log.d(TAG, "WebViewHost: size ${width}x$height; reconciling presentation without reload")
        view.post { reconcilePresentation(view as WebView, current) }
    }
    slot.listener = listener
    webView.addOnLayoutChangeListener(listener)
}

private fun reconcilePresentation(webView: WebView, slot: Slot) {
    if (!webView.isAttachedToWindow) return
    val textChanged = slot.appliedTextZoom != slot.textZoomPercent
    if (textChanged) {
        webView.settings.textZoom = slot.textZoomPercent
        slot.appliedTextZoom = slot.textZoomPercent
    }
    val url = webView.url
    if (url.isNullOrBlank() || url == "about:blank") {
        if (textChanged) publishTextOnly(slot)
        return
    }
    val settled = !slot.forceReconcile &&
        slot.appliedPageZoom == slot.pageZoomPercent &&
        slot.appliedMode == slot.mode &&
        slot.lastAck?.ok == true &&
        slot.viewWidth == slot.ackedWidth &&
        slot.viewHeight == slot.ackedHeight
    if (settled) {
        if (textChanged) publishTextOnly(slot)
        return
    }
    val flush = slot.forceReconcile
    slot.forceReconcile = false
    val generation = ++slot.generation
    val mode = slot.mode
    val percent = slot.pageZoomPercent
    val textZoom = slot.textZoomPercent
    if (url.contains("shark-incremental") && (flush || slot.layoutAppliedForUrl != url)) {
        webView.evaluateJavascript(SharkLayout.injectionScript(), null)
        slot.layoutAppliedForUrl = url
    }
    Log.d(
        TAG,
        "reconcile before view=${webView.width}x${webView.height} mode=${mode.stored()} " +
            "page=$percent text=$textZoom flush=$flush",
    )
    val script = presentationScript(mode, percent, flush)
    val started = runCatching {
        webView.evaluateJavascript(script) { raw ->
            if (generation != slot.generation) return@evaluateJavascript
            val ack = parsePresentationAck(raw, percent, textZoom)
            Log.d(TAG, "reconcile after raw=${raw?.take(500)} ok=${ack?.ok} vs=${ack?.viewportScale}")
            if (ack == null) {
                retryReconcile(webView, slot, generation)
                return@evaluateJavascript
            }
            slot.lastAck = ack
            slot.onAck?.invoke(ack)
            if (ack.ok) {
                slot.appliedPageZoom = percent
                slot.appliedMode = mode
                slot.ackedWidth = slot.viewWidth
                slot.ackedHeight = slot.viewHeight
                slot.retries = 0
            } else {
                retryReconcile(webView, slot, generation)
            }
        }
    }
    if (started.isFailure) {
        Log.d(TAG, "reconcile skipped ${started.exceptionOrNull()?.javaClass?.simpleName}")
    }
}

private fun publishTextOnly(slot: Slot) {
    val previous = slot.lastAck ?: return
    val next = previous.copy(textZoom = slot.textZoomPercent)
    slot.lastAck = next
    slot.onAck?.invoke(next)
}

private fun retryReconcile(webView: WebView, slot: Slot, generation: Int) {
    if (slot.retries >= MAX_PRESENTATION_RETRIES) return
    slot.retries += 1
    val delay = 80L * slot.retries
    webView.postDelayed({
        if (generation != slot.generation) return@postDelayed
        slot.forceReconcile = true
        reconcilePresentation(webView, slot)
    }, delay)
}

private class Slot(
    var profileName: String?,
    var appliedReloadEpoch: Int,
    var appliedLoadToken: Int,
    var finishToken: Int,
    var textZoomPercent: Int,
    var pageZoomPercent: Int,
    var mode: PageScaleMode,
    var presentationEpoch: Int,
    var appliedTextZoom: Int = -1,
    var appliedPageZoom: Int = -1,
    var appliedMode: PageScaleMode? = null,
    var layoutAppliedForUrl: String? = null,
    var viewWidth: Int = -1,
    var viewHeight: Int = -1,
    var ackedWidth: Int = -1,
    var ackedHeight: Int = -1,
    var forceReconcile: Boolean = true,
    var generation: Int = 0,
    var retries: Int = 0,
    var lastAck: PresentationAck? = null,
    var onAck: ((PresentationAck) -> Unit)? = null,
    var listener: View.OnLayoutChangeListener? = null,
)

private const val MAX_PRESENTATION_RETRIES = 4
