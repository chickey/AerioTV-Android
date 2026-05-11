package com.aeriotv.android.feature.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.nowPlaying

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    onChannelClick: (M3UChannel) -> Unit,
    onChangePlaylist: () -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val groups by remember(state.channels) {
        derivedStateOf {
            val unique = state.channels.asSequence()
                .map { it.groupTitle }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedBy { it.lowercase() }
                .toList()
            listOf(PlaylistViewModel.ALL_GROUPS) + unique
        }
    }

    val filtered by remember(state.channels, state.searchQuery, state.selectedGroup) {
        derivedStateOf {
            val query = state.searchQuery.trim()
            state.channels.asSequence()
                .filter {
                    state.selectedGroup == PlaylistViewModel.ALL_GROUPS ||
                            it.groupTitle.equals(state.selectedGroup, ignoreCase = true)
                }
                .filter {
                    query.isEmpty() || it.name.contains(query, ignoreCase = true)
                }
                .toList()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                val playlistName = state.playlist?.name?.takeIf { it.isNotBlank() } ?: "Channels"
                Text(
                    text = "$playlistName  •  ${filtered.size} / ${state.channels.size}",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            actions = {
                IconButton(onClick = onChangePlaylist) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Change playlist",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text("Search channels") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        )

        if (groups.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups, key = { it }) { group ->
                    FilterChip(
                        selected = state.selectedGroup == group,
                        onClick = { viewModel.onGroupSelected(group) },
                        label = { Text(group) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.surfaceVariant,
            thickness = 0.5.dp,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items = filtered, key = { it.id }) { channel ->
                val nowProgramme = state.epgByChannel[channel.tvgID]?.nowPlaying()
                ChannelRow(
                    channel = channel,
                    nowProgramme = nowProgramme,
                    onClick = { onChannelClick(channel) },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    thickness = 0.5.dp,
                )
            }
        }
    }
}

@Composable
private fun ChannelLogo(channel: M3UChannel) {
    val box = Modifier
        .size(48.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surface)

    val logoUrl = channel.tvgLogo.takeIf { it.isNotBlank() }
    if (logoUrl == null) {
        Box(modifier = box, contentAlignment = Alignment.Center) {
            Text(
                text = channel.name.take(2).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(logoUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = box,
            loading = {
                Box(modifier = box, contentAlignment = Alignment.Center) {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            error = {
                Box(modifier = box, contentAlignment = Alignment.Center) {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            success = { SubcomposeAsyncImageContent() },
        )
    }
}

@Composable
private fun ChannelRow(
    channel: M3UChannel,
    nowProgramme: EPGProgramme?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(channel = channel)
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 12.dp)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Prefer "now playing" from EPG if available; fall back to group title.
            val subtitle = nowProgramme?.title ?: channel.groupTitle
            val subtitleColor = if (nowProgramme != null)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
