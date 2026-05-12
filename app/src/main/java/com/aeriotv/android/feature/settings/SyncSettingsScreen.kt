package com.aeriotv.android.feature.settings

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.sync.SyncCategory
import com.aeriotv.android.core.sync.SyncConfig
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Settings > Sync sub-screen. Mirrors iOS Settings > iCloud Sync layout:
 * master toggle, account row, per-category toggles, manual Sync Now / Pull,
 * and a red Clear Drive Data link at the bottom. The "Connect Google Drive"
 * banner explains the OAuth config requirement when SyncConfig.WEB_CLIENT_ID
 * is blank — a build-time bring-up step the user has to do once per project.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle(initialValue = false)
    val accountEmail by viewModel.accountEmail.collectAsStateWithLifecycle(initialValue = "")
    val lastPush by viewModel.lastPushAt.collectAsStateWithLifecycle(initialValue = 0L)
    val lastPull by viewModel.lastPullAt.collectAsStateWithLifecycle(initialValue = 0L)
    val statusObj by viewModel.driveStatus.collectAsState()
    val configured = remember { SyncConfig.isConfigured() }

    var inFlight by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sync", style = MaterialTheme.typography.titleMedium) },
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

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!configured) {
                item { ConfigBanner() }
            }
            item {
                Section(header = "Drive Sync", footer = "Playlists, watch progress, reminders, app preferences and credentials sync via your Drive AppData folder. Files are scoped per-app and never appear in your main Drive UI.") {
                    ToggleRow(
                        title = "Sync enabled",
                        subtitle = if (statusObj is com.aeriotv.android.core.sync.DriveSyncManager.Status.SignedIn)
                            "Signed in${if (accountEmail.isNotBlank()) " as $accountEmail" else ""}"
                        else
                            "Not connected. Tap Connect Google Drive below.",
                        checked = masterEnabled,
                        onCheckedChange = viewModel::setMasterEnabled,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (statusObj is com.aeriotv.android.core.sync.DriveSyncManager.Status.SignedIn)
                                    "Disconnect Google Drive" else "Connect Google Drive",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (statusObj is com.aeriotv.android.core.sync.DriveSyncManager.Status.SignedIn)
                                    "Sign out of Drive on this device" else "Grant Drive AppData access",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            enabled = configured && !inFlight,
                            onClick = {
                                inFlight = true
                                scope.launch {
                                    val signedIn = statusObj is com.aeriotv.android.core.sync.DriveSyncManager.Status.SignedIn
                                    if (signedIn) {
                                        viewModel.signOut()
                                    } else {
                                        viewModel.connect()
                                    }
                                    inFlight = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text(if (statusObj is com.aeriotv.android.core.sync.DriveSyncManager.Status.SignedIn) "Disconnect" else "Connect")
                        }
                    }
                }
            }

            item {
                Section(header = "Categories", footer = "Choose what syncs across your devices.") {
                    SyncCategory.entries.forEachIndexed { idx, category ->
                        val enabled by viewModel.categoryEnabled(category).collectAsStateWithLifecycle(initialValue = true)
                        ToggleRow(
                            title = category.displayName,
                            subtitle = category.subtitle,
                            checked = enabled,
                            onCheckedChange = { viewModel.setCategoryEnabled(category, it) },
                        )
                        if (idx < SyncCategory.entries.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            item {
                Section(header = "Actions", footer = "Sync Now pushes local changes then pulls remote changes. Last Push: ${formatTimestamp(lastPush)}. Last Pull: ${formatTimestamp(lastPull)}.") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Button(
                            enabled = !inFlight && statusObj is com.aeriotv.android.core.sync.DriveSyncManager.Status.SignedIn,
                            onClick = {
                                inFlight = true
                                scope.launch {
                                    val result = viewModel.syncNow()
                                    val failed = result.entries.count { !it.value }
                                    Toast.makeText(
                                        context,
                                        if (failed == 0) "Sync complete."
                                        else "Sync finished with $failed category failure${if (failed > 1) "s" else ""}.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    inFlight = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(imageVector = Icons.Filled.Sync, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Sync Now")
                        }
                        Spacer(Modifier.height(10.dp))
                        TextButton(
                            onClick = {
                                inFlight = true
                                scope.launch {
                                    val ok = viewModel.clearRemote()
                                    Toast.makeText(
                                        context,
                                        if (ok) "Drive data cleared." else "Clear failed.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    inFlight = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !inFlight && statusObj is com.aeriotv.android.core.sync.DriveSyncManager.Status.SignedIn,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Clear Drive Data", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column {
            Text(
                text = "Drive Sync needs a Google Cloud OAuth client",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Set SyncConfig.WEB_CLIENT_ID with your project's OAuth 2.0 Web Client ID and register this app's signing-cert SHA-1 with an Android client id. Until then, sign-in will fail.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Section(
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
        ) { content() }
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
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatTimestamp(value: Long): String {
    if (value <= 0L) return "never"
    val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return df.format(Date(value))
}
