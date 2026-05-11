package com.aeriotv.android

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.aeriotv.android.feature.channels.ChannelListScreen
import com.aeriotv.android.feature.onboarding.UrlEntryScreen
import com.aeriotv.android.feature.player.PlayerScreen
import com.aeriotv.android.feature.playlist.PlaylistViewModel

object Routes {
    const val PLAYLIST_GRAPH = "playlist_graph"
    const val URL_ENTRY = "url_entry"
    const val CHANNELS = "channels"
    const val PLAYER = "player/{url}"

    fun player(url: String) = "player/${Uri.encode(url)}"
}

@Composable
fun AerioTVNavHost(initialUrl: String? = null) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.PLAYLIST_GRAPH) {
        navigation(startDestination = Routes.URL_ENTRY, route = Routes.PLAYLIST_GRAPH) {
            composable(Routes.URL_ENTRY) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                // One-shot: if MainActivity received an initialUrl via intent extra,
                // pre-fill and auto-load. Subsequent recompositions are no-ops.
                androidx.compose.runtime.LaunchedEffect(initialUrl) {
                    if (!initialUrl.isNullOrBlank() && vm.state.value.url.isBlank()) {
                        vm.loadFromUrl(initialUrl)
                    }
                }
                UrlEntryScreen(
                    viewModel = vm,
                    onChannelsLoaded = {
                        navController.navigate(Routes.CHANNELS) {
                            popUpTo(Routes.URL_ENTRY) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.CHANNELS) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                ChannelListScreen(
                    viewModel = vm,
                    onChannelClick = { channel ->
                        navController.navigate(Routes.player(channel.url))
                    },
                )
            }
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument("url") { type = NavType.StringType }),
        ) { entry ->
            val encoded = entry.arguments?.getString("url").orEmpty()
            val url = Uri.decode(encoded)
            PlayerScreen(streamUrl = url, isLive = true)
        }
    }
}
