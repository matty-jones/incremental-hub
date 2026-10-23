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
import com.example.hub.games.TrimpsLayout
import com.example.hub.session.DiagnosticSeverity
import com.example.hub.session.DocumentGate
import com.example.hub.session.SessionState
import com.example.hub.session.StorageBootstrap
import com.example.hub.session.classifyResourceFailure
import java.nio.charset.StandardCharsets
import kotlin.math.abs

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
                    if (launchUrl?.contains("trimps.github.io") == true) {
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                    }
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
                slot.retries = 0
                slot.confirming = false
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
        val jumped = current.viewWidth < 0 ||
            abs(width - current.viewWidth) > 24 ||
            abs(height - current.viewHeight) > 24
        current.viewWidth = width
        current.viewHeight = height
        current.forceReconcile = true
        current.confirming = false
        if (jumped) {
            current.retries = 0
            current.viewportPolls = 0
            current.viewportStable = 0
            current.holdZoom = isSharkUrl((view as? WebView)?.url)
            current.unlockPosted = false
        }
        Log.d(TAG, "WebViewHost: size ${width}x$height; reconciling presentation without reload")
        view.post { reconcilePresentation(view as WebView, current) }
    }
    slot.listener = listener
    webView.addOnLayoutChangeListener(listener)
}

internal fun isSharkUrl(url: String?): Boolean = url?.contains("shark-incremental") == true

/** The next Shark reconcile must rebuild the layout viewport before it applies zoom. */
internal fun armSharkViewportUnlock(webView: WebView) {
    val slot = webView.tag as? Slot ?: return
    if (!isSharkUrl(webView.url)) return
    slot.holdZoom = true
    slot.unlockPosted = false
    slot.viewportPolls = 0
    slot.viewportStable = 0
    slot.retries = 0
    slot.forceReconcile = true
    slot.confirming = false
}

/**
 * Asks Chromium to read the current view size again. Does not navigate or
 * recreate the WebView. Shark keeps wide-viewport mode so a later fold can
 * resolve device-width. Other games restore the previous viewport settings.
 */
internal fun nudgeStaleViewport(webView: WebView) {
    if (!webView.isAttachedToWindow) return
    if (isSharkUrl(webView.url)) {
        unlockSharkViewport(webView)
        return
    }
    val settings = webView.settings
    val wide = settings.useWideViewPort
    val overview = settings.loadWithOverviewMode
    runCatching { webView.dispatchConfigurationChanged(webView.resources.configuration) }
    settings.useWideViewPort = !wide
    webView.post {
        settings.useWideViewPort = wide
        settings.loadWithOverviewMode = overview
        webView.requestLayout()
        val scale = webView.scale
        val density = webView.resources.displayMetrics.density
        if (scale > 0.05f && abs(scale - 1f) > 0.08f && abs(scale - density) > 0.2f) {
            runCatching { webView.zoomBy(1f / scale) }
        }
        runCatching {
            webView.evaluateJavascript(
                "window.dispatchEvent(new Event('resize'));" +
                    "if(window.visualViewport)window.visualViewport.dispatchEvent(new Event('resize'));",
                null,
            )
        }
    }
}

/**
 * Clears a cover-sized layout that WebView is stretching across the inner screen.
 * Page zoom stays off until the layout viewport matches the view.
 */
@Suppress("DEPRECATION")
internal fun unlockSharkViewport(webView: WebView) {
    if (!webView.isAttachedToWindow) return
    val settings = webView.settings
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = false
    runCatching { webView.dispatchConfigurationChanged(webView.resources.configuration) }
    runCatching { webView.evaluateJavascript(viewportUnlockScript(), null) }
    webView.post {
        runCatching { webView.setInitialScale(0) }
        webView.requestLayout()
    }
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
    val shark = isSharkUrl(url)
    if (!shark) slot.holdZoom = false
    if (shark && slot.holdZoom && !slot.unlockPosted) {
        slot.unlockPosted = true
        slot.viewportStable = 0
        Log.i(TAG, "reconcile unlock shark view=${webView.width}x${webView.height}")
        unlockSharkViewport(webView)
        webView.postDelayed({
            if (!webView.isAttachedToWindow) return@postDelayed
            slot.forceReconcile = true
            reconcilePresentation(webView, slot)
        }, UNLOCK_DELAY_MS)
        return
    }
    val densityNow = webView.resources.displayMetrics.density
    val settled = !slot.forceReconcile &&
        slot.appliedPageZoom == slot.pageZoomPercent &&
        slot.appliedMode == slot.mode &&
        slot.lastAck?.ok == true &&
        presentationSettled(slot.lastFault) &&
        slot.viewWidth == slot.ackedWidth &&
        slot.viewHeight == slot.ackedHeight &&
        abs(densityNow - slot.ackedDensity) <= 0.02f
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
    val sampledWidth = webView.width
    val sampledHeight = webView.height
    if (sampledWidth > 0) slot.viewWidth = sampledWidth
    if (sampledHeight > 0) slot.viewHeight = sampledHeight
    val density = webView.resources.displayMetrics.density
    val parentWidth = (webView.parent as? View)?.width ?: 0
    if (url.contains("shark-incremental") && (flush || slot.layoutAppliedForUrl != url)) {
        webView.evaluateJavascript(SharkLayout.injectionScript(), null)
        slot.layoutAppliedForUrl = url
    }
    if (url.contains("trimps.github.io") && (flush || slot.layoutAppliedForUrl != url)) {
        webView.evaluateJavascript(TrimpsLayout.injectionScript(), null)
        slot.layoutAppliedForUrl = url
    }
    val expectW = cssPixelSize(sampledWidth, density)
    val expectH = cssPixelSize(sampledHeight, density)
    Log.i(
        TAG,
        "reconcile before view=${sampledWidth}x$sampledHeight parent=$parentWidth " +
            "expect=${expectW}x$expectH density=$density mode=${mode.stored()} " +
            "page=$percent text=$textZoom flush=$flush hold=${slot.holdZoom}",
    )
    val script = presentationScript(mode, percent, flush, expectW, expectH, applyZoom = !slot.holdZoom)
    val started = runCatching {
        webView.evaluateJavascript(script) { raw ->
            if (generation != slot.generation) return@evaluateJavascript
            val widthNow = webView.width
            val heightNow = webView.height
            val parsed = parsePresentationAck(raw, percent, textZoom)
            if (parsed == null) {
                Log.i(TAG, "reconcile fault=$FAULT_TIMEOUT view=${widthNow}x$heightNow raw=${raw?.take(240)}")
                retryReconcile(webView, slot, generation, FAULT_TIMEOUT)
                return@evaluateJavascript
            }
            if (widthNow != sampledWidth || heightNow != sampledHeight) {
                Log.i(
                    TAG,
                    "reconcile fault=$FAULT_NATIVE view moved $sampledWidth x $sampledHeight -> $widthNow x $heightNow",
                )
                slot.forceReconcile = true
                retryReconcile(webView, slot, generation, FAULT_NATIVE)
                return@evaluateJavascript
            }
            val densityNow = webView.resources.displayMetrics.density
            val parentNow = (webView.parent as? View)?.width ?: parentWidth
            val fault = assessPresentation(
                parsed,
                widthNow,
                heightNow,
                densityNow,
                webView.scale,
                parentNow,
            )
            val accepted = presentationSettled(fault)
            val ack = parsed.copy(ok = accepted, fault = fault)
            Log.i(
                TAG,
                "reconcile fault=$fault accepted=$accepted view=${widthNow}x$heightNow " +
                    "inner=${ack.innerWidth}x${ack.innerHeight} vv=${ack.visualWidth}x${ack.visualHeight} " +
                    "vs=${ack.viewportScale} pageScale=${webView.scale} zoom=${ack.rootZoom}/${ack.bodyZoom} " +
                    "scroll=${ack.scrollWidth} client=${ack.clientWidth}",
            )
            slot.lastAck = ack
            slot.lastFault = fault
            if (slot.holdZoom && shark) {
                val ready = viewportReadyForZoom(fault)
                if (ready) slot.viewportStable += 1 else slot.viewportStable = 0
                slot.viewportPolls += 1
                Log.i(
                    TAG,
                    "reconcile viewport stable=${slot.viewportStable} poll=${slot.viewportPolls} fault=$fault " +
                        "inner=${ack.innerWidth}x${ack.innerHeight}",
                )
                if (slot.viewportStable >= 2) {
                    slot.holdZoom = false
                    slot.unlockPosted = false
                    slot.viewportPolls = 0
                    slot.unlockedWidth = widthNow
                    slot.unlockedHeight = heightNow
                    slot.forceReconcile = true
                    webView.post { reconcilePresentation(webView, slot) }
                    return@evaluateJavascript
                }
                if (slot.viewportPolls >= MAX_VIEWPORT_POLLS) {
                    slot.holdZoom = false
                    slot.unlockPosted = false
                    retryReconcile(webView, slot, generation, fault)
                    return@evaluateJavascript
                }
                if (!ready && slot.viewportPolls == 4) unlockSharkViewport(webView)
                webView.postDelayed({
                    if (generation != slot.generation || !webView.isAttachedToWindow) return@postDelayed
                    slot.forceReconcile = true
                    reconcilePresentation(webView, slot)
                }, UNLOCK_DELAY_MS)
                return@evaluateJavascript
            }
            slot.onAck?.invoke(ack)
            if (accepted) {
                slot.appliedPageZoom = percent
                slot.appliedMode = mode
                slot.ackedWidth = widthNow
                slot.ackedHeight = heightNow
                slot.ackedDensity = densityNow
                slot.retries = 0
                if (!slot.confirming) scheduleConfirmation(webView, slot, generation)
                slot.confirming = false
            } else {
                val sameUnlock = widthNow == slot.unlockedWidth && heightNow == slot.unlockedHeight
                if (shark && !sameUnlock && slot.viewportPolls < MAX_VIEWPORT_POLLS &&
                    (fault == FAULT_STALE_VIEWPORT || fault == FAULT_STALE_FRAME)
                ) {
                    slot.holdZoom = true
                    slot.unlockPosted = false
                    slot.viewportStable = 0
                    slot.forceReconcile = true
                    webView.post { reconcilePresentation(webView, slot) }
                    return@evaluateJavascript
                }
                if (!shark && (fault == FAULT_STALE_VIEWPORT || fault == FAULT_STALE_FRAME)) {
                    nudgeStaleViewport(webView)
                } else {
                    webView.requestLayout()
                }
                retryReconcile(webView, slot, generation, fault)
            }
        }
    }
    if (started.isFailure) {
        Log.i(TAG, "reconcile skipped ${started.exceptionOrNull()?.javaClass?.simpleName}")
    }
}

private fun scheduleConfirmation(webView: WebView, slot: Slot, generation: Int) {
    webView.postDelayed({
        if (!webView.isAttachedToWindow || generation != slot.generation) return@postDelayed
        val width = webView.width
        val height = webView.height
        val density = webView.resources.displayMetrics.density
        val drifted = width != slot.ackedWidth ||
            height != slot.ackedHeight ||
            abs(density - slot.ackedDensity) > 0.02f
        slot.confirming = true
        slot.forceReconcile = true
        if (drifted) {
            Log.i(TAG, "reconcile confirm drifted view=${width}x$height density=$density")
            slot.confirming = false
        }
        reconcilePresentation(webView, slot)
    }, CONFIRM_DELAY_MS)
}

private fun publishTextOnly(slot: Slot) {
    val previous = slot.lastAck ?: return
    val next = previous.copy(textZoom = slot.textZoomPercent)
    slot.lastAck = next
    slot.onAck?.invoke(next)
}

private fun retryReconcile(webView: WebView, slot: Slot, generation: Int, fault: String) {
    if (slot.retries >= MAX_PRESENTATION_RETRIES) {
        Log.i(
            TAG,
            "reconcile fault=$FAULT_TIMEOUT last=$fault view=${webView.width}x${webView.height} " +
                "inner=${slot.lastAck?.innerWidth}x${slot.lastAck?.innerHeight} " +
                "vs=${slot.lastAck?.viewportScale}",
        )
        val timedOut = slot.lastAck?.copy(ok = false, fault = FAULT_TIMEOUT)
        if (timedOut != null) {
            slot.lastAck = timedOut
            slot.lastFault = FAULT_TIMEOUT
            slot.onAck?.invoke(timedOut)
        }
        return
    }
    slot.retries += 1
    val delay = 40L * slot.retries + 20L * slot.retries * slot.retries
    webView.postDelayed({
        if (generation != slot.generation) return@postDelayed
        if (!webView.isAttachedToWindow) return@postDelayed
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
    var ackedDensity: Float = 0f,
    var forceReconcile: Boolean = true,
    var confirming: Boolean = false,
    var generation: Int = 0,
    var retries: Int = 0,
    var holdZoom: Boolean = false,
    var unlockPosted: Boolean = false,
    var viewportStable: Int = 0,
    var viewportPolls: Int = 0,
    var unlockedWidth: Int = -1,
    var unlockedHeight: Int = -1,
    var lastFault: String = "",
    var lastAck: PresentationAck? = null,
    var onAck: ((PresentationAck) -> Unit)? = null,
    var listener: View.OnLayoutChangeListener? = null,
)

private const val MAX_PRESENTATION_RETRIES = 6
private const val MAX_VIEWPORT_POLLS = 10
private const val CONFIRM_DELAY_MS = 200L
private const val UNLOCK_DELAY_MS = 80L
