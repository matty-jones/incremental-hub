package com.example.hub.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.hub.session.SessionController
import com.example.hub.session.SessionState
import com.example.hub.ui.chrome.PlayChrome
import com.example.hub.ui.theme.ImmersiveSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PlaySurface"

/**
 * Play surface. Session lifecycle stays on [SessionController]. Leaving the
 * surface captures when possible, then asks the navigator to pop.
 */
@Composable
fun PlaySurface(
    gameId: String,
    controller: SessionController,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val profile by controller.activeProfile.collectAsState()
    val launchUrl by controller.launchUrl.collectAsState()
    val sessionError by controller.sessionError.collectAsState()
    val generation by controller.webViewGeneration.collectAsState()
    val reloadEpoch by controller.reloadEpoch.collectAsState()
    val loadToken by controller.loadToken.collectAsState()
    val presentationEpoch by controller.presentationEpoch.collectAsState()
    val diagnostic by controller.diagnostic.collectAsState()
    val consoleLines by controller.consoleLines.collectAsState()
    val events by controller.diagnosticEvents.collectAsState()
    val context = LocalContext.current
    val displayStore = remember(context) { DisplayPreferenceStore(context) }
    var displayPrefs by remember(gameId) { mutableStateOf(displayStore.read(gameId)) }
    var presentation by remember(gameId) { mutableStateOf<PresentationAck?>(null) }
    val folded = !isWideWindow()
    val saveNotice by controller.manualSaveNotice.collectAsState()
    val saveBusy by controller.manualSaveInFlight.collectAsState()
    val smallestWidth = LocalConfiguration.current.smallestScreenWidthDp
    val trimpsInset = with(LocalDensity.current) {
        TrimpsFrame.bottomInsetPx(gameId, smallestWidth).toDp()
    }
    TrimpsCoverOrientation(gameId)
    val zoom = displayPrefs.forPosture(folded)
    val pageScaleMode = pageScaleModeFor(gameId, displayPrefs.pageScaleMode)
    val scope = rememberCoroutineScope()
    var menuOpen by remember(gameId) { mutableStateOf(false) }
    var menuPanel by remember(gameId) { mutableStateOf(MenuPanel.Actions) }

    ImmersiveSystemBars()

    fun updateZoom(next: GameDisplayPreferences) {
        displayPrefs = next
        displayStore.write(gameId, next)
    }

    fun exitSession() {
        scope.launch {
            controller.close()
            withContext(Dispatchers.Main.immediate) {
                onExit()
            }
        }
    }

    BackHandler(enabled = !menuOpen && state !is SessionState.Closed && state !is SessionState.RecoveryRequired) {
        Log.d(TAG, "PlaySurface: back pressed state=$state")
        exitSession()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        FoldAwareLayout(state = state, content = {
            // Chrome sits above the WebView so games that put controls at the
            // top of the document (FE000000) are not covered by the hub header.
            Column(modifier = Modifier.fillMaxSize()) {
                if (!menuOpen && state !is SessionState.Closed) {
                    PlayChrome(
                        onMenuClick = {
                            menuPanel = MenuPanel.Actions
                            menuOpen = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = trimpsInset),
                ) {
                    androidx.compose.runtime.key(gameId, generation) {
                        WebViewHost(
                            gameId = gameId,
                            launchUrl = launchUrl,
                            state = state,
                            profile = profile,
                            reloadEpoch = reloadEpoch,
                            loadToken = loadToken,
                            textZoomPercent = zoom.textZoomPercent,
                            pageZoomPercent = zoom.pageZoomPercent,
                            pageScaleMode = pageScaleMode,
                            presentationEpoch = presentationEpoch,
                            documentGate = controller.documentGate,
                            onTakeRetained = controller::takeRetainedWebView,
                            onWebViewCreated = controller::registerWebView,
                            onPageFinished = controller::onMainFrameFinished,
                            onMainFrameError = controller::onMainFrameFailed,
                            onResourceDiagnostic = { severity, message ->
                                controller.recordDiagnostic(severity, "network", message)
                            },
                            onExplicitLoad = controller::onWebViewLoad,
                            onRendererGone = controller::onRendererGone,
                            onConsoleMessage = controller::onConsoleMessage,
                            onPresentation = { presentation = it },
                            onRelease = controller::releaseWebView,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // Keep the opening chrome until the game document is requested, not
                    // only while loadToken is still 0 (token is never reset across opens).
                    if (state is SessionState.Opening &&
                        controller.documentGate.phase != com.example.hub.session.DocumentPhase.GAME
                    ) {
                        OpeningStatus(gameId = gameId, launchUrl = launchUrl)
                    }

                    if (menuOpen && state !is SessionState.Closed && state !is SessionState.Closing) {
                        NativeMenu(
                            state = state,
                            expanded = true,
                            onExpandedChange = {
                                menuOpen = it
                                if (!it) menuPanel = MenuPanel.Actions
                            },
                            onAction = { action ->
                                Log.d(TAG, "PlaySurface: menu action=${action.label} state=$state")
                                when (action) {
                                    MenuAction.Resume -> {
                                        menuOpen = false
                                        controller.requestForeground()
                                    }
                                    MenuAction.Save -> controller.requestCheckpoint()
                                    MenuAction.Reload -> controller.requestReload()
                                    MenuAction.Vault -> Unit
                                    MenuAction.Display -> menuPanel = MenuPanel.Display
                                    MenuAction.Diagnostics -> menuPanel = MenuPanel.Diagnostics
                                    MenuAction.ReturnToHub -> exitSession()
                                }
                            },
                            showDisplay = menuPanel == MenuPanel.Display,
                            showDiagnostics = menuPanel == MenuPanel.Diagnostics,
                            saveBusy = saveBusy,
                            saveNotice = saveNotice,
                            onPanelBack = { menuPanel = MenuPanel.Actions },
                            display = {
                                DisplayPanel(
                                    folded = folded,
                                    textZoomPercent = zoom.textZoomPercent,
                                    pageZoomPercent = zoom.pageZoomPercent,
                                    pageScaleMode = pageScaleMode,
                                    allowGameFit = gameId == FE000000_GAME_ID,
                                    presentation = presentation,
                                    onTextZoom = { percent ->
                                        updateZoom(
                                            displayPrefs.withPosture(
                                                folded,
                                                zoom.copy(textZoomPercent = percent),
                                            ),
                                        )
                                    },
                                    onPageZoom = { percent ->
                                        updateZoom(
                                            displayPrefs.withPosture(
                                                folded,
                                                zoom.copy(pageZoomPercent = percent),
                                            ),
                                        )
                                    },
                                    onMode = { mode ->
                                        updateZoom(displayPrefs.copy(pageScaleMode = mode))
                                    },
                                    onReset = {
                                        presentation = null
                                        updateZoom(defaultDisplayPreferences(gameId))
                                    },
                                )
                            },
                            diagnostics = {
                                DiagnosticsPanel(
                                    report = controller.diagnosticReport(),
                                    events = events,
                                )
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    if (state is SessionState.RecoveryRequired) {
                        RecoveryOverlay(
                            gameId = gameId,
                            launchUrl = launchUrl,
                            detail = sessionError,
                            diagnostic = diagnostic,
                            consoleLines = consoleLines,
                            onRetry = { controller.retryRecovery() },
                            onSafeLaunch = { controller.requestSafeLaunch() },
                            onRecover = { controller.confirmRecover() },
                            onClose = { exitSession() },
                            onExport = { shareReport(context, controller.diagnosticReport()) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        })
    }
}

@Composable
private fun OpeningStatus(gameId: String, launchUrl: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text(
                    text = "Opening $gameId",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = launchUrl ?: "resolving launch URL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private enum class MenuPanel {
    Actions,
    Display,
    Diagnostics,
}
