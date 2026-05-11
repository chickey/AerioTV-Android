package com.aeriotv.android.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import android.util.Log
import com.aeriotv.android.core.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-scoped state for the playlist flow:
 *   Bootstrap → (existing playlist? auto-refresh : UrlEntry → load) →
 *   ChannelList → tap → Player.
 *
 * EPG loading happens in the background once channels are ready; channel
 * rows are usable immediately and the now/next badge appears when the EPG
 * map populates.
 *
 * Persistence is in [PlaylistRepository] (Room PlaylistEntity row). Channels
 * and programmes are kept only in memory and re-parsed on every refresh —
 * same as iOS Aerio.
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    enum class Phase { Bootstrapping, NeedsUrl, ChannelsReady }

    data class UiState(
        val phase: Phase = Phase.Bootstrapping,
        val url: String = "",
        val epgUrl: String = "",
        val playlist: PlaylistEntity? = null,
        val channels: List<M3UChannel> = emptyList(),
        /** channelId (XMLTV `channel` attr, matches M3U tvg-id) -> programmes sorted by start. */
        val epgByChannel: Map<String, List<EPGProgramme>> = emptyMap(),
        val isEpgLoading: Boolean = false,
        val searchQuery: String = "",
        val selectedGroup: String = ALL_GROUPS,
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    companion object {
        const val ALL_GROUPS = "All"
        private const val TAG = "PlaylistViewModel"
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val saved = repository.activePlaylist()
            if (saved == null) {
                _state.update { it.copy(phase = Phase.NeedsUrl) }
                return@launch
            }
            _state.update {
                it.copy(
                    playlist = saved,
                    url = saved.urlString,
                    epgUrl = saved.epgUrl.orEmpty(),
                    isLoading = true,
                )
            }
            repository.refresh(saved).fold(
                onSuccess = { channels ->
                    _state.update {
                        it.copy(
                            phase = Phase.ChannelsReady,
                            channels = channels,
                            isLoading = false,
                            error = null,
                        )
                    }
                    loadEpgIfConfigured(saved)
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            phase = Phase.NeedsUrl,
                            isLoading = false,
                            error = "Failed to refresh saved playlist: ${t.message ?: t::class.simpleName}",
                        )
                    }
                }
            )
        }
    }

    fun onUrlChange(value: String) {
        _state.update { it.copy(url = value, error = null) }
    }

    fun onEpgUrlChange(value: String) {
        _state.update { it.copy(epgUrl = value) }
    }

    fun onSearchQueryChange(value: String) {
        _state.update { it.copy(searchQuery = value) }
    }

    fun onGroupSelected(group: String) {
        _state.update { it.copy(selectedGroup = group) }
    }

    /** Pre-fill URLs and immediately attempt to load. Used by debug intent-extras and future deep links. */
    fun loadFromUrl(url: String, epgUrl: String? = null) {
        _state.update {
            it.copy(
                url = url,
                epgUrl = epgUrl.orEmpty(),
                error = null,
            )
        }
        loadPlaylist()
    }

    fun loadPlaylist() {
        val url = _state.value.url.trim()
        val epgUrl = _state.value.epgUrl.trim().takeIf { it.isNotEmpty() }
        if (url.isEmpty()) {
            _state.update { it.copy(error = "Enter a playlist URL") }
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update { it.copy(error = "Playlist URL must start with http:// or https://") }
            return
        }
        if (epgUrl != null && !epgUrl.startsWith("http://") && !epgUrl.startsWith("https://")) {
            _state.update { it.copy(error = "EPG URL must start with http:// or https://") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.loadAndPersist(
                url = url,
                epgUrl = epgUrl,
                existingId = _state.value.playlist?.id,
            ).fold(
                onSuccess = { (entity, channels) ->
                    _state.update {
                        it.copy(
                            phase = Phase.ChannelsReady,
                            playlist = entity,
                            channels = channels,
                            isLoading = false,
                            error = if (channels.isEmpty()) "No channels found. Check the URL." else null,
                        )
                    }
                    loadEpgIfConfigured(entity)
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            channels = emptyList(),
                            error = "Failed to load: ${t.message ?: t::class.simpleName}",
                        )
                    }
                }
            )
        }
    }

    private fun loadEpgIfConfigured(playlist: PlaylistEntity) {
        if (playlist.epgUrl.isNullOrBlank()) {
            Log.i(TAG, "loadEpgIfConfigured: no EPG URL configured (epgUrl=${playlist.epgUrl})")
            return
        }
        viewModelScope.launch {
            Log.i(TAG, "loadEpgIfConfigured: fetching ${playlist.epgUrl}")
            _state.update { it.copy(isEpgLoading = true) }
            repository.loadEpg(playlist).fold(
                onSuccess = { programmes ->
                    Log.i(TAG, "EPG loaded: ${programmes.size} programmes across ${programmes.map { it.channelId }.toSet().size} channels")
                    val byChannel: Map<String, List<EPGProgramme>> = programmes
                        .groupBy { it.channelId }
                        .mapValues { (_, list) -> list.sortedBy { it.startMillis } }
                    _state.update { it.copy(epgByChannel = byChannel, isEpgLoading = false) }
                },
                onFailure = { t ->
                    Log.w(TAG, "EPG load failed", t)
                    _state.update { it.copy(isEpgLoading = false) }
                }
            )
        }
    }

    /** Clear the saved playlist and return to URL entry. */
    fun clearPlaylist() {
        viewModelScope.launch {
            repository.clear()
            _state.update {
                UiState(phase = Phase.NeedsUrl)
            }
        }
    }
}

/**
 * Find the programme that contains `now` for a given channel.
 * Returns null if no EPG entries exist for that channel or none match the time.
 */
fun List<EPGProgramme>.nowPlaying(now: Long = System.currentTimeMillis()): EPGProgramme? =
    firstOrNull { it.startMillis <= now && now < it.endMillis }
