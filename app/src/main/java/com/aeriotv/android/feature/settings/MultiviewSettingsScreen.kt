package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Multiview sub-screen. Mirrors iOS MultiviewSettingsView field-for-field:
 * Audio Focus Indicator style picker + Tile Padding toggle + Rounded
 * Corners toggle. Each pref is read by MultiviewScreen at compose time so
 * changes take effect without re-entering multiview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiviewSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val style by viewModel.multiviewAudioFocusStyle.collectAsStateWithLifecycle(initialValue = "centerIcon")
    val padding by viewModel.multiviewTilePadding.collectAsStateWithLifecycle(initialValue = false)
    val rounded by viewModel.multiviewTileCornersRounded.collectAsStateWithLifecycle(initialValue = false)

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Multiview",
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

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
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
                    // so the Tile Appearance card's footer text stays
                    // visible on short displays.
                    bottom = 104.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsCard(
                header = "Audio Focus Indicator",
                footer = "How the grid shows which tile is unmuted. Center Icon fades with the chrome, Gray Outline stays visible, Accent Outline appears on switch and fades after 5 seconds.",
            ) {
                AUDIO_FOCUS_OPTIONS.forEachIndexed { idx, opt ->
                    RadioRow(
                        title = opt.label,
                        subtitle = opt.detail,
                        selected = style == opt.id,
                        onClick = { viewModel.setMultiviewAudioFocusStyle(opt.id) },
                    )
                    if (idx < AUDIO_FOCUS_OPTIONS.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }

            SettingsCard(
                header = "Tile Appearance",
                footer = "How tiles sit in the grid. Both default to off, matching the iOS edge-to-edge look.",
            ) {
                ToggleRow(
                    title = "Padding Between Tiles",
                    subtitle = "Add a small gap between tiles for visual separation.",
                    checked = padding,
                    onCheckedChange = viewModel::setMultiviewTilePadding,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ToggleRow(
                    title = "Rounded Corners",
                    subtitle = "Soften tile edges with a 12dp corner radius.",
                    checked = rounded,
                    onCheckedChange = viewModel::setMultiviewTileCornersRounded,
                )
            }
        }
        }
    }
}

private data class AudioFocusOption(val id: String, val label: String, val detail: String)

private val AUDIO_FOCUS_OPTIONS: List<AudioFocusOption> = listOf(
    AudioFocusOption("centerIcon", "Center Icon", "Speaker icon centered on the active tile. Default."),
    AudioFocusOption("grayPersistent", "Gray Outline", "Subtle gray border always around the active tile."),
    AudioFocusOption("themeFading", "Accent Outline (Fading)", "Accent-tinted border that auto-hides after 5 seconds."),
)

@Composable
private fun SettingsCard(
    header: String,
    footer: String?,
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
private fun RadioRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (focused || selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else Color.Transparent,
            )
            .border(
                width = if (focused) 2.dp else if (selected) 1.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
        Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused || selected) Color.White
            else MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (focused || selected) Color.White.copy(alpha = 0.85f)
            else MaterialTheme.colorScheme.onSurfaceVariant,
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
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else Color.Transparent,
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) Color.White else MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (focused) Color.White.copy(alpha = 0.85f)
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            ),
        )
    }
}
