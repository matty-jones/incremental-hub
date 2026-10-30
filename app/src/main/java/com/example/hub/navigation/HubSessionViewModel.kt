package com.example.hub.navigation

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.hub.database.HubDatabase
import com.example.hub.games.GameAdapters
import com.example.hub.games.GameRegistry
import com.example.hub.games.ProgressionClass
import com.example.hub.session.DiagnosticSeverity
import com.example.hub.session.SessionController
import com.example.hub.session.SessionRuntime
import com.example.hub.session.SessionState
import com.example.hub.sync.SaveSync
import com.example.hub.sync.SyncBootstrap
import com.example.hub.sync.WorkstationApi
import com.example.hub.sync.WorkstationClient
import kotlinx.coroutines.launch

/**
 * Survives activity recreation so a fold does not construct a second session.
 * [HubNavigation] attaches the activity lifecycle. This owner is only a
 * constructor placeholder.
 */
class HubSessionViewModel(
    context: Context,
    gameRegistry: GameRegistry,
    database: HubDatabase,
) : ViewModel() {
    private val workstation = WorkstationClient()
    val workstationApi: WorkstationApi = workstation
    val saveSync: SaveSync = SaveSync(
        dao = database.gameInstanceDao(),
        api = workstation,
        snapshotDao = database.snapshotDao(),
        outboxDao = database.syncOutboxDao(),
        historyCursorDao = database.syncHistoryCursorDao(),
    )
    init {
        viewModelScope.launch {
            SyncBootstrap.enqueueExisting(
                database.gameInstanceDao(),
                database.snapshotDao(),
                database.syncOutboxDao(),
            )
        }
        saveSync.classify = classify@{ gameId, candidate, reference ->
            val game = gameRegistry.getGame(gameId) ?: return@classify ProgressionClass.INCOMPARABLE
            val adapter = GameAdapters.create(game.adapterId) ?: return@classify ProgressionClass.INCOMPARABLE
            adapter.classifyProgress(candidate, reference)
        }
    }
    val controller: SessionController = SessionController(
        context = context.applicationContext,
        gameRegistry = gameRegistry,
        instanceDao = database.gameInstanceDao(),
        lifecycleOwner = SilentLifecycleOwner(),
        onSnapshotSaved = { instance ->
            viewModelScope.launch { saveSync.push(instance) }
        },
        snapshotDao = database.snapshotDao(),
        roomDatabase = database,
        syncOutboxDao = database.syncOutboxDao(),
    )

    init {
        saveSync.activeGameId = {
            val state = controller.state.value
            if (state is SessionState.Closed || state is SessionState.Closing) {
                null
            } else {
                controller.activeGameId.value
            }
        }
        saveSync.onDecision = { message ->
            controller.recordDiagnostic(DiagnosticSeverity.INFO, "sync", message)
        }
        SessionRuntime.controller = controller
    }

    override fun onCleared() {
        if (SessionRuntime.controller === controller) {
            SessionRuntime.controller = null
        }
    }
}

class HubSessionViewModelFactory(
    private val context: Context,
    private val gameRegistry: GameRegistry,
    private val database: HubDatabase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HubSessionViewModel(context, gameRegistry, database) as T
    }
}

private class SilentLifecycleOwner : LifecycleOwner {
    override val lifecycle: Lifecycle = LifecycleRegistry(this)
}
