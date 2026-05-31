package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.feature.main.AppTab

/**
 * App Behaviors sub-screen. Mirrors iOS AppBehaviorsSettingsView.swift:
 * launch behaviour toggles + channel-flip gesture toggle. Adds a Default Tab
 * picker which iOS keeps in a different surface but lives here for parity with
 * the @AppStorage("defaultTab") key (architecture spec section C).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBehaviorsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val skipLoadingScreen by viewModel.skipLoadingScreen.collectAsStateWithLifecycle(initialValue = false)
    val appleTVChannelFlip by viewModel.appleTVChannelFlip.collectAsStateWithLifecycle(initialValue = true)
    val playbackSkipSeconds by viewModel.playbackSkipSeconds.collectAsStateWithLifecycle(initialValue = 10)
    val autoResumeLastChannel by viewModel.autoResumeLastChannel.collectAsStateWithLifecycle(initialValue = false)
    val defaultTab by viewModel.defaultTab.collectAsStateWithLifecycle(initialValue = "")
    val playbackSkipPresets = listOf(5, 10, 15, 30, 60, 90, 120)

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "App Behaviors",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary,
                        )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            modifier = Modifier
                .adaptiveFormWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    // 104dp bottom clears the MainScaffold NavigationBar
                    // so the Default Tab list at the bottom stays
                    // reachable on short displays.
                    bottom = 104.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsGroup(header = "Launch", footer = "Skip loading screen may cause brief stutter while data hydrates. Resume last channel re-opens the player on launch if the saved channel still exists in your playlist.") {
                ToggleRow(
                    title = "Skip loading screen",
                    subtitle = "Land on Live TV instantly; data hydrates in the background",
                    checked = skipLoadingScreen,
                    onCheckedChange = viewModel::setSkipLoadingScreen,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ToggleRow(
                    title = "Resume last channel",
                    subtitle = "Auto-start the last-played channel on launch.",
                    checked = autoResumeLastChannel,
                    onCheckedChange = viewModel::setAutoResumeLastChannel,
                )
            }

            SettingsGroup(header = "Channel Flip Gesture", footer = "Turn off if accidental swipes during playback flip channels by mistake.") {
                ToggleRow(
                    title = "Up / Down channel change",
                    subtitle = "While the player chrome is visible, swipe up for the next channel and down for the previous. Live single-stream playback only.",
                    checked = appleTVChannelFlip,
                    onCheckedChange = viewModel::setAppleTVChannelFlip,
                )
            }

            SettingsGroup(
                header = "Playback Skip Interval",
                footer = "Controls how far Back/Forward seek jumps during recording and VOD playback.",
            ) {
                playbackSkipPresets.forEachIndexed { idx, seconds ->
                    PresetRow(
                        label = "${seconds}s",
                        selected = playbackSkipSeconds == seconds,
                        onClick = { viewModel.setPlaybackSkipSeconds(seconds) },
                    )
                    if (idx < playbackSkipPresets.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }

            SettingsGroup(header = "Default Tab", footer = "Which tab the app lands on after launch. Live TV is the iOS default.") {
                AppTab.entries.forEachIndexed { idx, tab ->
                    val selected = (defaultTab.isEmpty() && tab == AppTab.LiveTV) ||
                            defaultTab == tab.name
                    DefaultTabRow(
                        label = tab.label,
                        selected = selected,
                        onClick = { viewModel.setDefaultTab(tab.name) },
                    )
                    if (idx < AppTab.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun SettingsGroup(
    header: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = header.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
        ) {
            content()
        }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            ),
        )
    }
}

@Composable
private fun DefaultTabRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun PresetRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
    }
}
