package dev.rsm.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.rsm.ui.about.AboutScreen
import dev.rsm.ui.volumes.VolumeListScreen
import dev.rsm.ui.volumes.rememberVolumeListState

/** Destination ids. Plain strings — there is no argument to type, and two screens need no more. */
internal object Route {
    const val VOLUMES = "volumes"
    const val ABOUT = "about"
}

/**
 * The navigation graph.
 *
 * The volume state is deliberately remembered *above* the `NavHost` rather than inside the volumes
 * destination. `rememberVolumeListState` ties its `StorageVolumeCallback` and `BroadcastReceiver`
 * registration to the composition (FR-103), so holding it in the destination would tear both down on
 * the way to About and re-register on the way back — losing any attach or state change that happened
 * while About was open, and re-scanning on return. Hoisted, the observers outlive the navigation.
 */
@Composable
fun RsmApp() {
    val navController = rememberNavController()
    val volumeState = rememberVolumeListState()

    NavHost(navController = navController, startDestination = Route.VOLUMES) {
        composable(Route.VOLUMES) {
            VolumeListScreen(
                state = volumeState,
                onOpenAbout = { navController.navigate(Route.ABOUT) },
            )
        }
        composable(Route.ABOUT) {
            AboutScreen(onNavigateUp = { navController.navigateUp() })
        }
    }
}
