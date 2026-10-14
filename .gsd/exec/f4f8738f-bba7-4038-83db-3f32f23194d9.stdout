package com.example.hub.navigation

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * Navigation host for the Playable Hub app.
 *
 * Two routes:
 * - `"launcher"` — the main launcher screen ([LauncherScreen]).
 * - `"play_surface/{gameId}"` — the play surface ([PlaySurface]),
 *   wired with [SessionController] as the sole session lifecycle owner.
 *
 * The [SessionController] is created once at NavHost scope and shared across
 * all play-surface routes so it survives navigation.
 */
@Composable
fun HubNavigation(
    context: Context,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Create dependencies once at NavHost scope so they survive recomposition.
    val registry = remember(context) { GameRegistry(context) }
    val database = remember(context) { HubDatabase.getInstance(context) }
    val dao = database.gameInstanceDao()

    // SessionController created once at NavHost scope — NOT inside a composable
    // remember, so it survives recomposition and navigation.
    val controller = remember(lifecycleOwner, registry, dao) {
        SessionController(
            context = context,
            gameRegistry = registry,
            instanceDao = dao,
            lifecycleOwner = lifecycleOwner,
        )
    }

    NavHost(
        navController = navController,
        startDestination = "launcher",
        modifier = modifier,
    ) {
        composable("launcher") {
            LauncherScreen(
                onLaunch = { gameId ->
                    Log.d(TAG, "HubNavigation: navigate to play_surface/$gameId (launch)")
                    navController.navigate("play_surface/$gameId")
                },
                onResume = { gameId ->
                    Log.d(TAG, "HubNavigation: navigate to play_surface/$gameId (resume)")
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
            Log.d(TAG, "HubNavigation: play_surface displayed for gameId=$gameId")
            PlaySurface(gameId = gameId, controller = controller)
        }
    }
}
