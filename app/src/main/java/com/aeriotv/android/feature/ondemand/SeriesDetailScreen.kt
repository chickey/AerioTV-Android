package com.aeriotv.android.feature.ondemand

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.network.DispatcharrVODEpisode
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel

/**
 * Series detail screen. Mirrors iOS VODDetailView (Aerio/Features/VOD/VODDetailView.swift)
 * shape: hero with poster + title + plot, then episodes grouped by season.
 *
 * Phase 10c-2 lands the episode picker + tap-to-play wiring. Cast / director /
 * provider-info enrichment + WatchProgress's "Continue Watching" CTA are
 * deferred to 10c-3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    seriesId: Int,
    onBack: () -> Unit,
    onEpisodeClick: (DispatcharrVODEpisode) -> Unit,
    viewModel: OnDemandViewModel = hiltViewModel(),
    watchVm: WatchProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val series = viewModel.seriesById(seriesId)
    val recent by watchVm.observeRecent(50).collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(seriesId) {
        viewModel.loadEpisodes(seriesId)
    }
    BackHandler(enabled = true) { onBack() }

    val episodes = state.episodesBySeries[seriesId].orEmpty()
    val isLoading = seriesId in state.episodesLoadingFor
    val error = state.episodesErrorFor[seriesId]

    val groupedEpisodes = remember(episodes) {
        episodes
            .groupBy { it.seasonNumber ?: 0 }
            .toSortedMap()
            .mapValues { (_, eps) -> eps.sortedBy { it.episodeNumber ?: Int.MAX_VALUE } }
    }

    // Continue Watching pick: most-recently-updated episode that (a) belongs
    // to this series and (b) isn't within 5 min of the end. Uses the same
    // heuristic iOS VODDetailView applies (architecture spec section D).
    val continueWatchingEpisode = remember(recent, episodes) {
        val episodeIds = episodes.asSequence().map { it.uuid }.toSet()
        val pick = recent.firstOrNull { row ->
            row.videoId in episodeIds &&
                    row.positionMs > 0L &&
                    (row.durationMs <= 0L || row.positionMs < row.durationMs - 5 * 60 * 1000L)
        }
        pick?.let { row -> episodes.firstOrNull { it.uuid == row.videoId }?.to(row) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = series?.displayName ?: "Series",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (series != null) item {
                HeroBlock(series = series)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
            }

            continueWatchingEpisode?.let { (episode, progress) ->
                item(key = "continue-watching") {
                    ContinueWatchingCard(
                        episode = episode,
                        positionMs = progress.positionMs,
                        durationMs = progress.durationMs,
                        onResume = { onEpisodeClick(episode) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (isLoading && episodes.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else if (error != null && episodes.isEmpty()) {
                item {
                    Text(
                        text = "Couldn't load episodes: $error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else if (episodes.isEmpty()) {
                item {
                    Text(
                        text = "Server returned no episodes for this series.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                groupedEpisodes.forEach { (season, eps) ->
                    item(key = "season-$season-header") {
                        Text(
                            text = if (season == 0) "Episodes" else "Season $season",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(items = eps, key = { it.id }) { ep ->
                        EpisodeRow(
                            episode = ep,
                            onClick = { onEpisodeClick(ep) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBlock(series: com.aeriotv.android.core.network.DispatcharrVODSeries) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(110.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            val poster = series.posterUrl
            if (!poster.isNullOrBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = series.displayName,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = series.displayName.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                series.year?.toString(),
                series.rating?.takeIf { it.isNotBlank() },
                series.genre?.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!series.plot.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = series.plot,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!series.tmdbId.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                val context = androidx.compose.ui.platform.LocalContext.current
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val url = "https://www.themoviedb.org/tv/${series.tmdbId}"
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "View on TMDB",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    episode: DispatcharrVODEpisode,
    positionMs: Long,
    durationMs: Long,
    onResume: () -> Unit,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val remainingMin = if (durationMs > positionMs) {
        ((durationMs - positionMs) / 60_000L).toInt().coerceAtLeast(1)
    } else 0
    val seasonEpisodeTag = listOfNotNull(
        episode.seasonNumber?.let { "S$it" },
        episode.episodeNumber?.let { "E$it" },
    ).joinToString("·")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Continue Watching",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                if (seasonEpisodeTag.isNotBlank()) append(seasonEpisodeTag).append("  ·  ")
                append(episode.displayName.ifBlank { "Episode ${episode.id}" })
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text("Resume")
            }
            if (remainingMin > 0) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "$remainingMin min left",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: DispatcharrVODEpisode,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = episode.episodeNumber?.toString() ?: "•",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.displayName.ifBlank { "Episode ${episode.id}" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.effectivePlot?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        episode.airDate?.takeIf { it.isNotBlank() }?.let { date ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = date.take(10),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
