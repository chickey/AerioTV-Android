package com.aeriotv.android.feature.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Mirrors iOS Aerio/Features/Home/HomeView.swift AppTab enum (lines 2783-2787):
 * liveTV, favorites, dvr, onDemand, settings.
 *
 * Order here is the on-screen tab order. iOS default is liveTV (via the
 * `@AppStorage("defaultTab")` setting on ThemeManager).
 */
enum class AppTab(
    val id: String,
    val label: String,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
) {
    LiveTV(
        id = "livetv",
        label = "Live TV",
        iconSelected = Icons.Filled.LiveTv,
        iconUnselected = Icons.Outlined.LiveTv,
    ),
    Favorites(
        id = "favorites",
        label = "Favorites",
        iconSelected = Icons.Filled.Favorite,
        iconUnselected = Icons.Outlined.Favorite,
    ),
    DVR(
        id = "dvr",
        label = "DVR",
        iconSelected = Icons.Filled.Tv,
        iconUnselected = Icons.Outlined.Tv,
    ),
    OnDemand(
        id = "ondemand",
        label = "On Demand",
        iconSelected = Icons.Filled.OndemandVideo,
        iconUnselected = Icons.Outlined.OndemandVideo,
    ),
    Settings(
        id = "settings",
        label = "Settings",
        iconSelected = Icons.Filled.Settings,
        iconUnselected = Icons.Outlined.Settings,
    ),
}
