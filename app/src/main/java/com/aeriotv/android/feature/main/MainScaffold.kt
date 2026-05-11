package com.aeriotv.android.feature.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.channels.ChannelListScreen
import com.aeriotv.android.feature.settings.SettingsScreen

/**
 * Top-level scaffold once a playlist is loaded. Mirrors iOS MainTabView with the
 * same five tabs in the same order. Live TV is the default landing tab
 * (matches iOS @AppStorage("defaultTab") = "livetv").
 *
 * Phase 3a only wires Live TV (real channel list) and Settings (real playlist
 * management). Favorites, DVR, and On Demand are placeholder screens until
 * their respective phases ship.
 */
@Composable
fun MainScaffold(
    onChannelClick: (M3UChannel) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.LiveTV) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                AppTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.iconSelected else tab.iconUnselected,
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        // Each tab is a Composable invoked here. Keeping them inline rather than
        // routing through a nested NavHost so navigation state is just the
        // selectedTab variable; the bottom bar is the only "navigation" surface.
        when (selectedTab) {
            AppTab.LiveTV -> ChannelListScreen(
                onChannelClick = onChannelClick,
                modifierWrap = Modifier.padding(padding),
            )
            AppTab.Favorites -> PlaceholderScreen(
                tabLabel = "Favorites",
                hint = "Pin channels for quick access. Coming with the Favorites phase.",
            )
            AppTab.DVR -> PlaceholderScreen(
                tabLabel = "DVR",
                hint = "Schedule and play recordings. Coming with the DVR phase.",
            )
            AppTab.OnDemand -> PlaceholderScreen(
                tabLabel = "On Demand",
                hint = "Movies and series from your Xtream Codes or Dispatcharr server. " +
                        "Coming after the API client phase.",
            )
            AppTab.Settings -> SettingsScreen()
        }
    }
}
