package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.aeriotv.android.core.sync.DispatcharrSyncManager
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatcharrSyncSettingsScreen(
    onBack: () -> Unit,
    viewModel: DispatcharrSyncSettingsViewModel = hiltViewModel(),
) {
    val lastPush by viewModel.lastPushAt.collectAsStateWithLifecycle(initialValue = 0L)
    val lastPull by viewModel.lastPullAt.collectAsStateWithLifecycle(initialValue = 0L)
    val status by viewModel.status.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Dispatcharr Sync",
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
            colors = TopAppBarDefaults.topAppBarColors(
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
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val statusText = when (val s = status) {
                    DispatcharrSyncManager.Status.Idle -> "Ready."
                    is DispatcharrSyncManager.Status.Working -> s.message
                    is DispatcharrSyncManager.Status.Success -> s.message
                    is DispatcharrSyncManager.Status.Error -> s.message
                }
                SummaryPanel(
                    status = statusText,
                    lastPull = formatSyncTime(lastPull),
                    lastPush = formatSyncTime(lastPush),
                )
                ButtonRow(
                    title = "Sync Now",
                    subtitle = "Server first: pull Dispatcharr's sync data, then upload this Fire TV's newer state.",
                    action = "Sync",
                    onClick = viewModel::syncNow,
                )
                var showAdvanced by remember { mutableStateOf(false) }
                ButtonRow(
                    title = "Advanced Sync",
                    subtitle = if (showAdvanced) {
                        "Hide one-way recovery actions."
                    } else {
                        "Show pull-only and push-only recovery actions."
                    },
                    action = if (showAdvanced) "Hide" else "Show",
                    onClick = { showAdvanced = !showAdvanced },
                )
                if (showAdvanced) {
                    ButtonRow(
                        title = "Pull from Dispatcharr",
                        subtitle = "Apply the server sync document to this Fire TV without uploading local changes.",
                        action = "Pull",
                        onClick = viewModel::pull,
                    )
                    ButtonRow(
                        title = "Push to Dispatcharr",
                        subtitle = "Publish this Fire TV's current state. Use only when this device is the trusted copy.",
                        action = "Push",
                        onClick = viewModel::push,
                    )
                }
            }
        }
    }
}

@Composable
private fun ButtonRow(
    title: String,
    subtitle: String,
    action: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(
                if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            action,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) Color.White else MaterialTheme.colorScheme.primary,
        )
    }
}

private fun formatSyncTime(value: Long): String =
    if (value <= 0L) "Never"
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

@Composable
private fun SummaryPanel(
    status: String,
    lastPull: String,
    lastPush: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Hub,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Dispatcharr sync",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Sync settings, favourites, hidden groups, recent channels and watch progress through the AerioTV Dispatcharr plugin.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = "Status: $status",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Pulled: $lastPull  |  Pushed: $lastPush",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
