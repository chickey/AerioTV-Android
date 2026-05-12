package com.aeriotv.android.feature.miniplayer

import com.aeriotv.android.core.data.M3UChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state holder for the mini-player overlay anchored above the
 * bottom nav. Mirrors iOS NowPlayingManager's surfaced channel handle, but
 * scoped down: v1 ships as a tap-to-resume affordance only — no in-row
 * live video render, no audio continuation across the boundary. The user
 * still sees what they were last watching and can re-enter the player
 * with one tap.
 *
 * State machine:
 *   Hidden  ◀──────────── system back from PlayerScreen
 *      │                  (or explicit Close button)
 *      │
 *      ▼  channel tap
 *   Active(channel)  ◀── system back while playing
 *      │
 *      ▼  tap row
 *   Hidden + nav PLAYER(channel)
 *
 * Why a Hilt @Singleton and not a ViewModel: the session has to outlive
 * every screen-scoped ViewModel and survive nav-stack pops. ViewModels
 * scoped to NavBackStackEntry would lose state every time the user exits
 * the player and the back stack collapses.
 */
@Singleton
class MiniPlayerSession @Inject constructor() {

    private val _state: MutableStateFlow<State> = MutableStateFlow(State.Hidden)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Called when PlayerScreen mounts (or flips channel). Holding fullscreen
     * is represented by [State.Pending] so the mini-player UI doesn't render
     * underneath the player; a system back then promotes Pending → Active. */
    fun setCurrentChannel(channel: M3UChannel) {
        _state.value = State.Pending(channel)
    }

    /** System back inside PlayerScreen — promote pending channel into the
     * active mini-player surface. */
    fun showMiniPlayer() {
        val pending = (_state.value as? State.Pending)?.channel
            ?: (_state.value as? State.Active)?.channel
            ?: return
        _state.value = State.Active(pending)
    }

    /** User dismissed the mini-player or hit explicit Close in fullscreen. */
    fun dismiss() {
        _state.value = State.Hidden
    }

    /** User tapped the mini-player to resume; caller navigates back into PlayerScreen. */
    fun resumeChannel(): M3UChannel? {
        val channel = (_state.value as? State.Active)?.channel
        if (channel != null) _state.value = State.Pending(channel)
        return channel
    }

    sealed interface State {
        /** No mini-player; no playback context. */
        data object Hidden : State

        /** Player is currently fullscreen; mini-player UI hidden but channel remembered. */
        data class Pending(val channel: M3UChannel) : State

        /** Mini-player row visible above the bottom nav. */
        data class Active(val channel: M3UChannel) : State
    }
}
