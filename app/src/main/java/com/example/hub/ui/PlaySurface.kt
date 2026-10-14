package com.example.hub.ui

import androidx.compose.foundation.layout.padding

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.example.hub.session.SessionController
import com.example.hub.session.SessionState
import com.example.hub.session.canTransition

private const val TAG = "PlaySurface"

/**
 * Play-surface composable wired to a [SessionController] as the sole owner
 * of session lifecycle. No local remember state — all state is observed from
 * [SessionController.state] via collectAsState.
 *
 * Menu callbacks route through [SessionController] request*() methods.
 * Navigation back coordinates with [SessionController.close()].
 */
@Composable
fun PlaySurface(
    gameId: String,
    controller: SessionController,
    modifier: Modifier = Modifier,
) {
    Log.d(TAG, "PlaySurface: created for gameId=$gameId (state=${controller.state.value})")

    val state = controller.state.collectAsState().value

    // Back handler: close session via controller, then pop navigation.
    BackHandler(
        enabled = state !is SessionState.Closed && state !is SessionState.RecoveryRequired
    ) {
        Log.d(TAG, "PlaySurface: back pressed (state=$state)")
        controller.requestClosing()
    }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        FoldAwareLayout(
            state = state,
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    // WebView host: stable across recomposition, profile bound by controller.
                    WebViewHost(
                        gameId = gameId,
                        state = state,
                        profile = controller.activeProfile.value,
                        onError = controller.createErrorHandler(),
                        modifier = Modifier.fillMaxSize(),
                        onDestroy = { controller.webView = null },
                    )

                    // NativeMenu is a sibling overlay — it sits on top of WebViewHost
                    // without wrapping it, preserving WebView identity across menu cycles.
                    if (state !is SessionState.Opening &&
                        state !is SessionState.Closed &&
                        state !is SessionState.Closing &&
                        state !is SessionState.RecoveryRequired
                    ) {
                        NativeMenu(
                            state = state,
                            onAction = { action ->
                                Log.d(TAG, "PlaySurface: menu action=${action.label} (state=$state)")
                                when (action) {
                                    is MenuAction.Resume -> {
                                        if (state is SessionState.Playing) {
                                            Log.d(TAG, "Resume is no-op (already Playing)")
                                        } else if (canTransition(state, SessionState.Playing)) {
                                            controller.requestForeground()
                                        } else {
                                            Log.w(TAG, "Resume rejected from $state")
                                        }
                                    }
                                    is MenuAction.SnapshotNow -> {
                                        Log.d(TAG, "SnapshotNow side-effect (state=$state)")
                                    }
                                    is MenuAction.Checkpoint -> {
                                        Log.d(TAG, "Checkpoint side-effect (state=$state)")
                                    }
                                    is MenuAction.Vault -> {
                                        Log.d(TAG, "Vault side-effect (state=$state)")
                                    }
                                    is MenuAction.Reload -> {
                                        Log.d(TAG, "Reload side-effect (state=$state)")
                                    }
                                    is MenuAction.Display -> {
                                        Log.d(TAG, "Display side-effect (state=$state)")
                                    }
                                    is MenuAction.ReturnToHub -> {
                                        Log.d(TAG, "ReturnToHub — requesting closing")
                                        controller.requestClosing()
                                    }
                                }
                            },
                            onBack = {
                                Log.d(TAG, "PlaySurface: menu back pressed (state=$state)")
                                controller.requestClosing()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // Recovery overlay: shown only when session is in RecoveryRequired state.
                    if (state is SessionState.RecoveryRequired) {
                        RecoveryOverlay(
                            gameId = gameId,
                            onRetry = { /* controller.open() called from hub */ },
                            onClose = {
                                Log.d(TAG, "PlaySurface: close from RecoveryRequired")
                                controller.requestClosing()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        )
    }
}
