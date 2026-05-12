package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

/**
 * Network sub-screen. Mirrors iOS NetworkSettingsView (SettingsView.swift:3118+):
 * Request Timeout slider (5..60 step 5), Max Retries stepper (0..10), and a
 * Buffer Size radio list with iOS-canon labels + millisecond detail rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val timeoutSecs by viewModel.networkTimeoutSecs.collectAsStateWithLifecycle(initialValue = 15.0)
    val maxRetries by viewModel.maxRetries.collectAsStateWithLifecycle(initialValue = 3)
    val bufferSize by viewModel.streamBufferSize.collectAsStateWithLifecycle(initialValue = "default")

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Network", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ConnectionSection(
                timeoutSecs = timeoutSecs,
                maxRetries = maxRetries,
                onTimeoutChange = viewModel::setNetworkTimeoutSecs,
                onMaxRetriesChange = viewModel::setMaxRetries,
            )
            BufferSizeSection(
                current = bufferSize,
                onSelect = viewModel::setStreamBufferSize,
            )
        }
    }
}

@Composable
private fun ConnectionSection(
    timeoutSecs: Double,
    maxRetries: Int,
    onTimeoutChange: (Double) -> Unit,
    onMaxRetriesChange: (Int) -> Unit,
) {
    SettingsCard(header = "Connection", footer = "Adjust timeouts if you have a slow or unstable connection.") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Request Timeout",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${timeoutSecs.toInt()}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Slider(
                value = timeoutSecs.toFloat(),
                onValueChange = { onTimeoutChange(it.toDouble()) },
                valueRange = 5f..60f,
                steps = 10, // 5..60 step 5 -> 11 marks -> 10 intermediate steps
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Max Retries",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Per-request retry budget (0-10).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { if (maxRetries > 0) onMaxRetriesChange(maxRetries - 1) },
                enabled = maxRetries > 0,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease")
            }
            Text(
                text = maxRetries.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.size(32.dp).padding(top = 4.dp),
            )
            IconButton(
                onClick = { if (maxRetries < 10) onMaxRetriesChange(maxRetries + 1) },
                enabled = maxRetries < 10,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase")
            }
        }
    }
}

@Composable
private fun BufferSizeSection(
    current: String,
    onSelect: (String) -> Unit,
) {
    SettingsCard(
        header = "Buffer Size",
        footer = "Controls how much stream data is pre-loaded. Larger buffers reduce stuttering on poor connections but add startup delay.",
    ) {
        BUFFER_OPTIONS.forEachIndexed { idx, opt ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(opt.id) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = opt.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = opt.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (opt.id == current) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (idx < BUFFER_OPTIONS.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

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

data class BufferOption(val id: String, val label: String, val detail: String, val cachingMs: Int)

internal val BUFFER_OPTIONS: List<BufferOption> = listOf(
    BufferOption("small", "Small", "300 ms - fast, stable networks", 300),
    BufferOption("default", "Default", "1 second - recommended", 1_000),
    BufferOption("large", "Large", "3 seconds - unstable connections", 3_000),
    BufferOption("xlarge", "Extra Large", "8 seconds - very poor networks", 8_000),
)

internal fun bufferMillisFor(id: String): Int =
    BUFFER_OPTIONS.firstOrNull { it.id == id }?.cachingMs ?: 1_000
