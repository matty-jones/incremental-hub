package com.example.hub.navigation

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.hub.database.HubDatabase
import com.example.hub.games.GameRegistry
import com.example.hub.session.SessionController
import com.example.hub.ui.LauncherScreen
import com.example.hub.ui.PlaySurface

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
    val controller = remember(lifecycleOwner, gameRegistry, dao) {
        SessionController(
            context = context,
            gameRegistry = gameRegistry,
            instanceDao = dao,
            lifecycleOwner = lifecycleOwner,
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
            LauncherScreen(
                registry = gameRegistry,
                instanceDao = dao,
                onLaunch = { gameId ->
                    Log.d(TAG, "navigate to play_surface/$gameId (launch)")
                    navController.navigate("play_surface/$gameId")
                },
                onResume = { gameId ->
                    Log.d(TAG, "navigate to play_surface/$gameId (resume)")
                    navController.navigate("play_surface/$gameId")
                },
            )
        }
        composable(
            route = "play_surface/{gameId}",
            arguments = listOf(navArgument("gameId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString("gameId")
                ?: throw IllegalArgumentException("gameId is required for play_surface route")
            LaunchedEffect(gameId) {
                Log.d(TAG, "opening session for gameId=$gameId")
                controller.open(gameId)
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
