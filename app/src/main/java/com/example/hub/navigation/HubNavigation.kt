package com.example.hub.navigation

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.hub.database.HubDatabase
import com.example.hub.games.GameAdapters
import com.example.hub.games.GameRegistry
import com.example.hub.sync.SaveAssessment
import com.example.hub.session.LaunchMode
import com.example.hub.session.SessionController
import com.example.hub.sync.SaveSync
import com.example.hub.sync.WorkstationClient
import com.example.hub.ui.ConnectedLauncher
import com.example.hub.ui.PlaySurface
import kotlinx.coroutines.launch

private const val TAG = "HubNavigation"

/**
 * Navigation host. [gameRegistry] and [database] are created once by the
 * activity and shared with the launcher and the session controller.
 */
@Composable
fun HubNavigation(
    context: Context,
    gameRegistry: GameRegistry,
    database: HubDatabase,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current
    val dao = database.gameInstanceDao()
    val scope = rememberCoroutineScope()
    val workstation = remember { WorkstationClient() }
    val saveSync = remember(dao, gameRegistry) {
        SaveSync(
            dao = dao,
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
    }
    val controller = remember(lifecycleOwner, gameRegistry, dao, saveSync) {
        SessionController(
            context = context,
            gameRegistry = gameRegistry,
            instanceDao = dao,
            lifecycleOwner = lifecycleOwner,
            onSnapshotSaved = { instance ->
                scope.launch { saveSync.push(instance) }
            },
            snapshotDao = database.snapshotDao(),
        )
    }

    DisposableEffect(lifecycleOwner, controller) {
        controller.attach(lifecycleOwner)
        onDispose { controller.detach() }
    }

    NavHost(
        navController = navController,
        startDestination = "launcher",
        modifier = modifier,
    ) {
        composable("launcher") {
            ConnectedLauncher(
                context = context,
                registry = gameRegistry,
                instanceDao = dao,
                saveSync = saveSync,
                api = workstation,
                onLaunch = { gameId ->
                    Log.d(TAG, "navigate to play_surface/$gameId (launch)")
                    navController.navigate("play_surface/$gameId?mode=resume")
                },
                onResume = { gameId ->
                    Log.d(TAG, "navigate to play_surface/$gameId (resume)")
                    navController.navigate("play_surface/$gameId?mode=resume")
                },
                onInspect = { gameId ->
                    Log.d(TAG, "navigate to play_surface/$gameId (inspect)")
                    navController.navigate("play_surface/$gameId?mode=inspect")
                },
                onSafeLaunch = { gameId ->
                    Log.d(TAG, "navigate to play_surface/$gameId (safe)")
                    navController.navigate("play_surface/$gameId?mode=safe")
                },
            )
        }
        composable(
            route = "play_surface/{gameId}?mode={mode}",
            arguments = listOf(
                navArgument("gameId") { type = NavType.StringType },
                navArgument("mode") { type = NavType.StringType; defaultValue = "resume" },
            ),
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString("gameId")
                ?: throw IllegalArgumentException("gameId is required for play_surface route")
            val mode = when (backStackEntry.arguments?.getString("mode")) {
                "inspect" -> LaunchMode.INSPECT
                "safe" -> LaunchMode.SAFE
                else -> LaunchMode.RESUME
            }
            LaunchedEffect(gameId, mode) {
                Log.d(TAG, "opening session for gameId=$gameId mode=$mode")
                controller.open(gameId, mode)
            }
            PlaySurface(
                gameId = gameId,
                controller = controller,
                onExit = {
                    Log.d(TAG, "return to launcher from $gameId")
                    navController.popBackStack()
                },
            )
        }
    }
}
