package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Manage Groups bottom sheet. Mirrors iOS Settings > Manage Groups modal:
 * a scrollable checkbox list with "All / None" toggles in the header. Checked
 * groups stay visible in the Live TV filter row; unchecked groups disappear
 * from the chips but their channels remain in "All" so they can still be
 * found via search.
 *
 * Persistence layer is the [hiddenGroups] set in AppPreferences. We work on
 * a mutable copy and commit on Done so toggling rapidly doesn't churn the
 * DataStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageGroupsSheet(
    allGroups: List<String>,
    hiddenGroups: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var working by remember(hiddenGroups) { mutableStateOf(hiddenGroups.toMutableSet()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 600.dp)
                .padding(bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Manage Groups",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    onSave(working.toSet())
                    onDismiss()
                }) {
                    Text("Done", color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Check groups to show, uncheck to hide.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { working = mutableSetOf() }) {
                    Text("All", color = MaterialTheme.colorScheme.primary)
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                TextButton(onClick = { working = allGroups.toMutableSet() }) {
                    Text("None", color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(allGroups, key = { it }) { group ->
                    val visible = group !in working
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                working = working.toMutableSet().apply {
                                    if (visible) add(group) else remove(group)
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = visible,
                            onCheckedChange = { checked ->
                                working = working.toMutableSet().apply {
                                    if (checked) remove(group) else add(group)
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = group,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
