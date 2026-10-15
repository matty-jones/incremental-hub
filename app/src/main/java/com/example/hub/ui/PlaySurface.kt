package com.example.hub.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hub.session.SessionController
import com.example.hub.session.SessionState
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
    val subresource by controller.subresourceDiagnostic.collectAsState()
    val generation by controller.webViewGeneration.collectAsState()
    val reloadEpoch by controller.reloadEpoch.collectAsState()
    val scope = rememberCoroutineScope()
    var menuOpen by remember(gameId) { mutableStateOf(false) }

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

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        FoldAwareLayout(state = state, content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                androidx.compose.runtime.key(gameId, generation) {
                    WebViewHost(
                        gameId = gameId,
                        launchUrl = launchUrl,
                        state = state,
                        profile = profile,
                        reloadEpoch = reloadEpoch,
                        onWebViewCreated = controller::registerWebView,
                        onPageFinished = controller::onMainFrameFinished,
                        onMainFrameError = controller::onMainFrameFailed,
                        onSubresourceError = controller::onSubresourceFailed,
                        onRendererGone = controller::onRendererGone,
                        onRelease = controller::releaseWebView,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (state is SessionState.Opening) {
                    OpeningStatus(gameId = gameId, launchUrl = launchUrl)
                }

                if (!menuOpen && state !is SessionState.Closed && state !is SessionState.Opening) {
                    TextButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Text("Menu")
                    }
                }

                if (menuOpen && state !is SessionState.Closed && state !is SessionState.Closing) {
                    NativeMenu(
                        state = state,
                        expanded = true,
                        onExpandedChange = { menuOpen = it },
                        onAction = { action ->
                            Log.d(TAG, "PlaySurface: menu action=${action.label} state=$state")
                            when (action) {
                                MenuAction.Resume -> {
                                    menuOpen = false
                                    controller.requestForeground()
                                }
                                MenuAction.SnapshotNow, MenuAction.Checkpoint -> controller.requestCapture()
                                MenuAction.Reload -> controller.requestReload()
                                MenuAction.Vault, MenuAction.Display -> Unit
                                MenuAction.ReturnToHub -> exitSession()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (state is SessionState.RecoveryRequired) {
                    RecoveryOverlay(
                        gameId = gameId,
                        launchUrl = launchUrl,
                        detail = sessionError,
                        onRetry = { controller.retryRecovery() },
                        onClose = { exitSession() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (subresource != null && state is SessionState.Playing) {
                    Text(
                        text = subresource!!,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                    )
                }
            }
        })
    }
}

@Composable
private fun OpeningStatus(gameId: String, launchUrl: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Opening $gameId\n${launchUrl ?: "resolving launch URL"}")
    }
}
