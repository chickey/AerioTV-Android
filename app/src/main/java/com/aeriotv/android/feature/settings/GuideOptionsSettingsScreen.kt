package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideOptionsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val showChannelName by viewModel.guideShowChannelName.collectAsStateWithLifecycle(initialValue = true)
    val showChannelNumber by viewModel.guideShowChannelNumber.collectAsStateWithLifecycle(initialValue = true)
    val transparentLogoBackground by viewModel.guideTransparentLogoBackground.collectAsStateWithLifecycle(initialValue = true)
    val logoScaleMode by viewModel.guideLogoScaleMode.collectAsStateWithLifecycle(initialValue = "fit")
    val fixedHourAnchor by viewModel.guideFixedHourAnchor.collectAsStateWithLifecycle(initialValue = true)
    val showDetailsPanel by viewModel.guideShowDetailsPanel.collectAsStateWithLifecycle(initialValue = false)
    val miniPlayerEnabled by viewModel.guideMiniPlayerEnabled.collectAsStateWithLifecycle(initialValue = true)
    val miniPlayerPosition by viewModel.guideMiniPlayerPosition.collectAsStateWithLifecycle(initialValue = "top_right")

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Guide Options",
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
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                GuideGroup(
                    header = "Channel Rail",
                    footer = "Turning labels off gives the channel logo more room in the TV Guide rail.",
                ) {
                    GuideToggleRow(
                        title = "Show channel name",
                        subtitle = "Display channel name under the logo in Guide view.",
                        checked = showChannelName,
                        onCheckedChange = viewModel::setGuideShowChannelName,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    GuideToggleRow(
                        title = "Show channel number",
                        subtitle = "Display channel number to the left of the logo.",
                        checked = showChannelNumber,
                        onCheckedChange = viewModel::setGuideShowChannelNumber,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    GuideToggleRow(
                        title = "Transparent logo background",
                        subtitle = "Remove the tile background behind channel logos.",
                        checked = transparentLogoBackground,
                        onCheckedChange = viewModel::setGuideTransparentLogoBackground,
                    )
                }

                GuideGroup(
                    header = "Logo Scale",
                    footer = "Fit keeps full logo visible. Fill stretches to box. Crop fills without distortion but may trim edges.",
                ) {
                    GuideScaleRow(
                        label = "Fit (Recommended)",
                        selected = logoScaleMode == "fit",
                        onClick = { viewModel.setGuideLogoScaleMode("fit") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    GuideScaleRow(
                        label = "Fill",
                        selected = logoScaleMode == "fill",
                        onClick = { viewModel.setGuideLogoScaleMode("fill") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    GuideScaleRow(
                        label = "Crop",
                        selected = logoScaleMode == "crop",
                        onClick = { viewModel.setGuideLogoScaleMode("crop") },
                    )
                }

                GuideGroup(
                    header = "Navigation",
                    footer = "Fixed-hour anchor keeps UP/DOWN movement stable. Details panel reserves space for focused show info.",
                ) {
                    GuideToggleRow(
                        title = "Fixed-hour vertical anchor",
                        subtitle = "Lock UP/DOWN focus to the left-hour column to prevent horizontal guide drift.",
                        checked = fixedHourAnchor,
                        onCheckedChange = viewModel::setGuideFixedHourAnchor,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    GuideToggleRow(
                        title = "Show focused programme details panel",
                        subtitle = "Reserve space above the grid for focused programme title/time/description.",
                        checked = showDetailsPanel,
                        onCheckedChange = viewModel::setGuideShowDetailsPanel,
                    )
                }

                GuideGroup(
                    header = "Mini Player",
                    footer = "When enabled, backing out of fullscreen live TV keeps playback in a small overlay window.",
                ) {
                    GuideToggleRow(
                        title = "Enable mini-player on Back",
                        subtitle = "Back from fullscreen keeps the channel playing in mini-player mode.",
                        checked = miniPlayerEnabled,
                        onCheckedChange = viewModel::setGuideMiniPlayerEnabled,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    GuideScaleRow(
                        label = "Top right",
                        selected = miniPlayerPosition == "top_right",
                        onClick = { viewModel.setGuideMiniPlayerPosition("top_right") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    GuideScaleRow(
                        label = "Top left",
                        selected = miniPlayerPosition == "top_left",
                        onClick = { viewModel.setGuideMiniPlayerPosition("top_left") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    GuideScaleRow(
                        label = "Bottom right",
                        selected = miniPlayerPosition == "bottom_right",
                        onClick = { viewModel.setGuideMiniPlayerPosition("bottom_right") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    GuideScaleRow(
                        label = "Bottom left",
                        selected = miniPlayerPosition == "bottom_left",
                        onClick = { viewModel.setGuideMiniPlayerPosition("bottom_left") },
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideScaleRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun GuideGroup(
    header: String,
    footer: String,
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
        ) { content() }
        Text(
            text = footer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun GuideToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
