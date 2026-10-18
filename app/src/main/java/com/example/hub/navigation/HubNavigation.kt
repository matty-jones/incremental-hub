package com.example.hub.navigation

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.hub.database.HubDatabase
import com.example.hub.games.GameRegistry
import com.example.hub.session.LaunchMode
import com.example.hub.ui.ConnectedLauncher
import com.example.hub.ui.PlaySurface
import com.example.hub.ui.findActivity

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
    val activity = LocalContext.current.findActivity()
    val sessionModel: HubSessionViewModel = viewModel(
        factory = HubSessionViewModelFactory(
            context = context.applicationContext,
            gameRegistry = gameRegistry,
            database = database,
        ),
    )
    val controller = sessionModel.controller
    val dao = database.gameInstanceDao()

    SideEffect {
        controller.configurationChanging = { activity?.isChangingConfigurations == true }
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
                saveSync = sessionModel.saveSync,
                api = sessionModel.workstationApi,
                onPlay = { gameId ->
                    Log.d(TAG, "navigate to play_surface/$gameId")
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
                onRecover = { gameId ->
                    Log.d(TAG, "navigate to play_surface/$gameId (recover)")
                    navController.navigate("play_surface/$gameId?mode=recover")
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
                "recover" -> LaunchMode.RECOVER
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
