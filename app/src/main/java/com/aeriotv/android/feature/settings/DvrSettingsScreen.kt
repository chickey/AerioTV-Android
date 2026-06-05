package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

import android.content.Intent
import android.net.Uri
import android.os.StatFs
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.feature.dvr.DvrViewModel

/**
 * DVR Settings sub-screen. Mirrors iOS DVRSettingsView field-for-field:
 * local-recording storage cap, default pre-roll, default post-roll. Custom
 * folder picker via SAF tree URI is queued for a follow-up cut.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DvrSettingsScreen(
    onBack: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
    dvrVm: DvrViewModel = hiltViewModel(),
) {
    val capMB by settingsVm.dvrMaxLocalStorageMB.collectAsStateWithLifecycle(initialValue = 10_240)
    val reserveFreeMB by settingsVm.dvrReserveFreeSpaceMB.collectAsStateWithLifecycle(initialValue = 200)
    val preRoll by settingsVm.dvrDefaultPreRollMins.collectAsStateWithLifecycle(initialValue = 0)
    val postRoll by settingsVm.dvrDefaultPostRollMins.collectAsStateWithLifecycle(initialValue = 0)
    val customFolderUri by settingsVm.dvrCustomFolderUri.collectAsStateWithLifecycle(initialValue = "")
    val keepAwake by settingsVm.dvrKeepAwakeDuringRecording.collectAsStateWithLifecycle(initialValue = true)
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Take persistable RW permission so LocalRecordingService can still
        // write here after a reboot. Without this the URI's grant expires
        // with the activity scope and recordings fail with SecurityException.
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        settingsVm.setDvrCustomFolderUri(uri.toString())
    }
    val dvrState by dvrVm.state.collectAsStateWithLifecycle()
    val usedBytes = dvrState.recordings
        .filter { it.source == DvrViewModel.Source.Local }
        .sumOf { it.fileSizeBytes }
    val usedMB = (usedBytes / (1024L * 1024L)).toInt()
    val usedFraction = if (capMB > 0) (usedMB.toFloat() / capMB.toFloat()).coerceIn(0f, 1f) else 0f
    val availableBytes = defaultRecordingAvailableBytes(context)
    val availableMB = (availableBytes / (1024L * 1024L)).toInt()
    val effectiveBudgetBytes = computeEffectiveLocalBudgetBytes(
        capMB = capMB,
        usedBytes = usedBytes,
        availableBytes = availableBytes,
        reserveFreeMB = reserveFreeMB,
    )
    val effectiveBudgetMB = (effectiveBudgetBytes / (1024L * 1024L)).toInt()
    val maxSelectableCapMB = effectiveBudgetMB.coerceAtLeast(0)
    val capOptions = remember(maxSelectableCapMB) { buildRecordingCapOptions(maxSelectableCapMB) }
    val displayCapMB = capMB.coerceAtMost(maxSelectableCapMB.takeIf { it > 0 } ?: capMB)

    androidx.compose.runtime.LaunchedEffect(maxSelectableCapMB, capMB) {
        if (maxSelectableCapMB > 0 && capMB > maxSelectableCapMB) {
            settingsVm.setDvrMaxLocalStorageMB(maxSelectableCapMB)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "DVR Settings",
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
        LazyColumn(
            modifier = Modifier.adaptiveFormWidth().fillMaxSize(),
            // Bottom padding clears the MainScaffold NavigationBar (~80dp)
            // so the final card (Output Folder + its footer) isn't clipped.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Card(
                    header = "Local Storage",
                    footer = "The cap is limited to the usable recording budget on this device, after keeping the reserve floor free.",
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Maximum",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatStorage(displayCapMB),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        if (capOptions.isEmpty()) {
                            Text(
                                text = "Not enough free space for local recordings right now.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            CapOptionsList(
                                options = capOptions,
                                selected = displayCapMB,
                                onSelect = settingsVm::setDvrMaxLocalStorageMB,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${formatStorage(usedMB)} of ${formatStorage(displayCapMB)} used",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (usedFraction > 0.8f)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${(usedFraction * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (usedFraction > 0.8f)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { usedFraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = if (usedFraction > 0.8f)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            drawStopIndicator = {},
                        )
                        Spacer(Modifier.height(10.dp))
                        StorageInfoRow(
                            label = "Device free space",
                            value = formatBytes(availableBytes),
                            valueColor = if (availableMB <= reserveFreeMB)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StorageInfoRow(
                            label = "Usable recording budget",
                            value = formatBytes(effectiveBudgetBytes),
                            valueColor = if (effectiveBudgetBytes <= 0)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            item {
                Card(
                    header = "Storage Safety",
                    footer = "Keep some free space in reserve so the Fire TV stays responsive while recordings are running. The service will stop before it dips below this floor.",
                ) {
                    Column {
                        ReserveRow(
                            label = "Keep free",
                            options = RESERVE_OPTIONS_MB,
                            selected = reserveFreeMB,
                            onSelect = settingsVm::setDvrReserveFreeSpaceMB,
                        )
                    }
                }
            }

            item {
                Card(
                    header = "Default Recording Buffers",
                    footer = "Buffers extend new recordings beyond the scheduled window. Existing recordings aren't touched. Useful for sports and live events that run over.",
                ) {
                    Column {
                        BufferRow(
                            label = "Start Early",
                            options = ROLL_OPTIONS,
                            selected = preRoll,
                            onSelect = settingsVm::setDvrDefaultPreRollMins,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        BufferRow(
                            label = "End Late",
                            options = ROLL_OPTIONS,
                            selected = postRoll,
                            onSelect = settingsVm::setDvrDefaultPostRollMins,
                        )
                    }
                }
            }

            item {
                Card(
                    header = "Output Folder",
                    footer = "Local recordings save here. Picked via Storage Access Framework so AerioTV retains read+write access across reboots.",
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "Currently saving to:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = formatCustomFolderLabel(customFolderUri),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row {
                            TextButton(onClick = { folderPicker.launch(null) }) {
                                Text(
                                    text = "Choose Folder",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (customFolderUri.isNotBlank()) {
                                Spacer(Modifier.size(8.dp))
                                TextButton(onClick = {
                                    val toRelease = customFolderUri
                                    runCatching {
                                        context.contentResolver.releasePersistableUriPermission(
                                            Uri.parse(toRelease),
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                        )
                                    }
                                    settingsVm.setDvrCustomFolderUri("")
                                }) {
                                    Text(
                                        text = "Reset to Default",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    header = "Behavior",
                    footer = "Holds a CPU wake lock while a local recording is downloading so Doze can't stall it. Server-side recordings are unaffected (they run on Dispatcharr). Leave on unless you're debugging battery drain.",
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Keep device awake during recording",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Recommended for long local recordings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        androidx.compose.material3.Switch(
                            checked = keepAwake,
                            onCheckedChange = settingsVm::setDvrKeepAwakeDuringRecording,
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun StorageInfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Card(
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

/**
 * Compact buffer-picker row matching iOS DVR Settings > DEFAULT RECORDING
 * BUFFERS. Shows the label + the current value as a chevron-tagged value;
 * tapping expands a DropdownMenu of the supported minute options. Two
 * BufferRows share one card via stacked layout.
 */
@Composable
private fun BufferRow(
    label: String,
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menuOpen = true }
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (focused) Color.White else MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatRoll(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) Color.White else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(6.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = if (focused) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            options.forEach { mins ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = mins == selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(formatRoll(mins))
                        }
                    },
                    onClick = {
                        onSelect(mins)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ReserveRow(
    label: String,
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menuOpen = true }
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (focused) Color.White else MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatBytes(selected.toLong() * 1024L * 1024L),
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) Color.White else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(6.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = if (focused) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            options.forEach { mb ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = mb == selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(formatBytes(mb.toLong() * 1024L * 1024L))
                        }
                    },
                    onClick = {
                        onSelect(mb)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CapOptionsList(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        options.forEachIndexed { index, mb ->
            CapOptionRow(
                label = formatStorage(mb),
                selected = mb == selected,
                onClick = { onSelect(mb) },
            )
            if (index < options.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun CapOptionRow(
    label: String,
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused || selected) Color.White
            else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium,
        )
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color.White,
                unselectedColor = if (focused) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

private fun formatRoll(mins: Int): String = if (mins == 0) "None" else "$mins min"

private fun buildRecordingCapOptions(maxBudgetMB: Int): List<Int> {
    if (maxBudgetMB <= 0) return emptyList()
    val options = mutableListOf<Int>()
    val step = if (maxBudgetMB < 1024) 100 else 1024
    var value = step
    while (value < maxBudgetMB) {
        options += value
        value += step
    }
    options += maxBudgetMB
    return options.distinct().sorted()
}

private fun formatStorage(mb: Int): String {
    if (mb >= 1024) {
        val gb = mb / 1024.0
        return if (gb >= 10) "${gb.toInt()} GB" else String.format("%.1f GB", gb)
    }
    return "$mb MB"
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1024 -> {
            val gb = mb / 1024.0
            if (gb >= 10) "${gb.toInt()} GB" else String.format("%.1f GB", gb)
        }
        else -> String.format("%.0f MB", mb)
    }
}

private fun defaultRecordingAvailableBytes(context: android.content.Context): Long {
    val dir = File(context.getExternalFilesDir(null), "Recordings").apply { mkdirs() }
    return runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
}

private fun computeEffectiveLocalBudgetBytes(
    capMB: Int,
    usedBytes: Long,
    availableBytes: Long,
    reserveFreeMB: Int,
): Long {
    val capBytes = capMB.coerceAtLeast(0).toLong() * 1024L * 1024L
    val reserveBytes = reserveFreeMB.coerceAtLeast(0).toLong() * 1024L * 1024L
    val capRemaining = (capBytes - usedBytes).coerceAtLeast(0L)
    val freeRemaining = (availableBytes - reserveBytes).coerceAtLeast(0L)
    return minOf(capRemaining, freeRemaining)
}

private val ROLL_OPTIONS: List<Int> = listOf(0, 5, 10, 15, 30, 60)
private val RESERVE_OPTIONS_MB: List<Int> = listOf(100, 200, 500, 1024, 2048)

/**
 * Render a SAF tree URI as a human-readable label by extracting the
 * tail of the document path, or fall back to the URI's authority. Blank
 * input → "App default (Android/data/.../files/Recordings)". Skipping
 * a full DocumentFile lookup here keeps the row cheap to render — names
 * shift to canonical only after the picker callback resolves the URI.
 */
private fun formatCustomFolderLabel(uriString: String): String {
    if (uriString.isBlank()) {
        return "App default folder"
    }
    return runCatching {
        val uri = Uri.parse(uriString)
        val raw = uri.lastPathSegment?.substringAfterLast(':') ?: uri.path ?: uriString
        raw.ifBlank { uriString }
    }.getOrElse { uriString }
}
