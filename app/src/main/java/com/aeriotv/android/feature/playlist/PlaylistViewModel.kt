package com.aeriotv.android.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.network.PlaylistFetcher
import com.aeriotv.android.core.parser.M3UParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-scoped state for the M3U-thin-slice flow:
 *   UrlEntryScreen → load → ChannelListScreen → tap → PlayerScreen.
 *
 * Phase 2b will replace this in-memory state with a Room-backed repository and
 * promote channels to a real entity. For now keep it dead simple.
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val fetcher: PlaylistFetcher,
) : ViewModel() {

    data class UiState(
        val url: String = "",
        val channels: List<M3UChannel> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onUrlChange(value: String) {
        _state.update { it.copy(url = value, error = null) }
    }

    /** Pre-fill URL and immediately attempt to load. Used by debug intent-extra and future deep links. */
    fun loadFromUrl(url: String) {
        _state.update { it.copy(url = url, error = null) }
        loadPlaylist()
    }

    fun loadPlaylist() {
        val url = _state.value.url.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(error = "Enter a playlist URL") }
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update { it.copy(error = "URL must start with http:// or https://") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val bytes = fetcher.fetchBytes(url)
                M3UParser.parseBytes(bytes)
            }.fold(
                onSuccess = { channels ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            channels = channels,
                            error = if (channels.isEmpty()) "No channels found. Check the URL." else null,
                        )
                    }
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
}
