package com.aeriotv.android.feature.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aeriotv.android.core.update.AppUpdateManager
import com.aeriotv.android.core.update.UpdateInfo
import java.io.File

/**
 * Full-lifecycle update dialog.
 *
 * Shown as a centred overlay (not a bottom sheet) so it works equally well
 * on TV (D-pad) and phone (touch). Focus is auto-requested on the primary
 * action button on each state transition so the user can confirm or dismiss
 * without moving the remote.
 *
 * States handled:
 *  - [AppUpdateManager.State.UpdateAvailable] — version info + Later / Download
 *  - [AppUpdateManager.State.Downloading]     — progress bar, no dismiss
 *  - [AppUpdateManager.State.ReadyToInstall]  — Cancel / Install Now
 *  - [AppUpdateManager.State.UpToDate]        — brief "up to date" + Close
 *  - [AppUpdateManager.State.Error]           — error message + Close
 *  - All other states                         — dialog not shown
 */
@Composable
fun UpdatePromptDialog(
    state: AppUpdateManager.State,
    onDismiss: () -> Unit,
    onDownload: (UpdateInfo) -> Unit,
    onInstall: (File) -> Unit,
) {
    val showDialog = state is AppUpdateManager.State.UpdateAvailable ||
        state is AppUpdateManager.State.Downloading ||
        state is AppUpdateManager.State.ReadyToInstall ||
        state is AppUpdateManager.State.UpToDate ||
        state is AppUpdateManager.State.Error
    if (!showDialog) return

    Dialog(
        onDismissRequest = {
            // Block dismiss during active download — user must wait.
            if (state !is AppUpdateManager.State.Downloading) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = state !is AppUpdateManager.State.Downloading,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = 0.82f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(28.dp),
        ) {
            when (state) {
                is AppUpdateManager.State.UpdateAvailable ->
                    UpdateAvailableContent(state.info, onDismiss, onDownload)
                is AppUpdateManager.State.Downloading ->
                    DownloadingContent(state.info, state.progressPercent)
                is AppUpdateManager.State.ReadyToInstall ->
                    ReadyToInstallContent(state.info, onDismiss) { onInstall(state.apkFile) }
                is AppUpdateManager.State.UpToDate ->
                    UpToDateContent(onDismiss)
                is AppUpdateManager.State.Error ->
                    ErrorContent(state.message, onDismiss)
                else -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------------------
// State-specific content
// ---------------------------------------------------------------------------

@Composable
private fun UpdateAvailableContent(
    info: UpdateInfo,
    onLater: () -> Unit,
    onDownload: (UpdateInfo) -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }

    Column {
        DialogTitle(icon = { UpdateIcon() }, text = "Update Available")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "AerioTV ${info.versionName} is ready to download.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (info.releaseNotes.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                    .padding(12.dp)
                    .heightMax(140.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = info.releaseNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DialogButton(
                label = "Later",
                modifier = Modifier.weight(1f),
                primary = false,
                onClick = onLater,
            )
            DialogButton(
                label = "Download & Install",
                modifier = Modifier
                    .weight(1.6f)
                    .focusRequester(primaryFocus),
                primary = true,
                onClick = { onDownload(info) },
            )
        }
    }
}

@Composable
private fun DownloadingContent(info: UpdateInfo, progressPercent: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DialogTitle(icon = { UpdateIcon() }, text = "Downloading…")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "AerioTV ${info.versionName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = { progressPercent / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (progressPercent > 0) "$progressPercent%" else "Starting…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyToInstallContent(
    info: UpdateInfo,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }

    Column {
        DialogTitle(
            icon = {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            },
            text = "Ready to Install",
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "AerioTV ${info.versionName} has been downloaded. " +
                "The app will restart after installation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DialogButton(
                label = "Cancel",
                modifier = Modifier.weight(1f),
                primary = false,
                onClick = onCancel,
            )
            DialogButton(
                label = "Install Now",
                modifier = Modifier
                    .weight(1.4f)
                    .focusRequester(primaryFocus),
                primary = true,
                onClick = onInstall,
            )
        }
    }
}

@Composable
private fun UpToDateContent(onClose: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "You're up to date",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "AerioTV is running the latest version.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        DialogButton(
            label = "Close",
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .focusRequester(closeFocus),
            primary = true,
            onClick = onClose,
        )
    }
}

@Composable
private fun ErrorContent(message: String, onClose: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    Column {
        DialogTitle(icon = {}, text = "Update Check Failed")
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        DialogButton(
            label = "Close",
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .focusRequester(closeFocus),
            primary = false,
            onClick = onClose,
        )
    }
}

// ---------------------------------------------------------------------------
// Shared sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun DialogTitle(icon: @Composable () -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun UpdateIcon() {
    Icon(
        imageVector = Icons.Outlined.SystemUpdateAlt,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp),
    )
}

/**
 * TV-friendly action button: outlined when unfocused, filled primary when
 * focused (matches the WelcomeScreen / DispatcharrSyncSettingsScreen pattern).
 */
@Composable
private fun DialogButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .background(
                color = when {
                    focused -> MaterialTheme.colorScheme.primary
                    primary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else -> Color.Transparent
                },
                shape = shape,
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused || primary) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = shape,
            )
            .padding(vertical = 14.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) Color.White
            else if (primary) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Caps a modifier's height for the release-notes scroll box. */
private fun Modifier.heightMax(maxHeight: androidx.compose.ui.unit.Dp) =
    this.then(Modifier.height(maxHeight))
