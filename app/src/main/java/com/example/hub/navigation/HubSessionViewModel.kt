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
import com.example.hub.session.DiagnosticSeverity
import com.example.hub.session.SessionController
import com.example.hub.session.SessionRuntime
import com.example.hub.session.SessionState
import com.example.hub.sync.SaveAssessment
import com.example.hub.sync.SaveSync
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
    ) { gameId, localPayload, remotePayload ->
        val game = gameRegistry.getGame(gameId) ?: return@SaveSync SaveAssessment()
        val adapter = GameAdapters.create(game.adapterId) ?: return@SaveSync SaveAssessment()
        val comparison = adapter.compare(localPayload, remotePayload)
        SaveAssessment(
            localStanding = adapter.summarize(localPayload).standing,
            remoteStanding = adapter.summarize(remotePayload).standing,
            comparison = if (game.adapterId == "shark-incremental-adapter") comparison else null,
        )
    }
    init {
        saveSync.protectsLocal = protects@{ gameId, reference, candidate ->
            val game = gameRegistry.getGame(gameId) ?: return@protects false
            val adapter = GameAdapters.create(game.adapterId) ?: return@protects false
            when (adapter.classifyProgress(candidate, reference)) {
                com.example.hub.games.ProgressionClass.ADVANCED,
                com.example.hub.games.ProgressionClass.EQUAL,
                com.example.hub.games.ProgressionClass.RESET_APPROVED,
                -> true
                com.example.hub.games.ProgressionClass.UNORDERED,
                com.example.hub.games.ProgressionClass.REGRESSED,
                com.example.hub.games.ProgressionClass.INCOMPARABLE,
                com.example.hub.games.ProgressionClass.INVALID,
                -> false
            }
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
