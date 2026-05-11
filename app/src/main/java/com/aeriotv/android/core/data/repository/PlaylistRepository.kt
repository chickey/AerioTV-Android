package com.aeriotv.android.core.data.repository

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.network.PlaylistFetcher
import com.aeriotv.android.core.parser.M3UParser
import com.aeriotv.android.core.parser.XMLTVParser
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for playlist persistence + fetch + parse.
 * Mirrors how iOS Aerio handles playlists: row stored in SwiftData, channels
 * re-parsed from source on every refresh (NOT cached individually).
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    private val fetcher: PlaylistFetcher,
) {

    suspend fun activePlaylist(): PlaylistEntity? = dao.firstActive()

    /**
     * Fetch the given URL, parse it, return the channels, and persist the URL
     * (or update an existing row if [existingId] is passed) with the new
     * channelCount + lastRefreshedAt. Channels themselves are NOT persisted.
     */
    suspend fun loadAndPersist(
        url: String,
        epgUrl: String? = null,
        name: String = deriveName(url),
        existingId: String? = null,
    ): Result<Pair<PlaylistEntity, List<M3UChannel>>> = runCatching {
        val bytes = fetcher.fetchBytes(url)
        val channels = M3UParser.parseBytes(bytes)
        val entity = PlaylistEntity(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            urlString = url,
            epgUrl = epgUrl?.takeIf { it.isNotBlank() },
            channelCount = channels.size,
            lastRefreshedAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        entity to channels
    }

    /**
     * Re-fetch channels for an existing playlist row, updating channelCount
     * and lastRefreshedAt without changing id/name/urlString.
     */
    suspend fun refresh(playlist: PlaylistEntity): Result<List<M3UChannel>> = runCatching {
        val bytes = fetcher.fetchBytes(playlist.urlString)
        val channels = M3UParser.parseBytes(bytes)
        dao.update(
            playlist.copy(
                channelCount = channels.size,
                lastRefreshedAt = System.currentTimeMillis(),
            )
        )
        channels
    }

    /**
     * Fetch + parse the EPG for the given playlist. Returns empty list when
     * no EPG URL is configured. .gz is transparently handled by the parser.
     */
    suspend fun loadEpg(playlist: PlaylistEntity): Result<List<EPGProgramme>> = runCatching {
        val url = playlist.epgUrl?.takeIf { it.isNotBlank() }
            ?: return@runCatching emptyList()
        val bytes = fetcher.fetchBytes(url)
        val programmes = XMLTVParser.parseBytes(bytes)
        dao.update(playlist.copy(lastEpgRefreshedAt = System.currentTimeMillis()))
        programmes
    }

    suspend fun clear() = dao.clear()

    private fun deriveName(url: String): String =
        url.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Playlist" }
}
