package com.aeriotv.android.feature.miniplayer

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import com.aeriotv.android.core.playback.MPVPlayerHolder

/**
 * Android TV mini-player.
 *
 * 2026-05-28: DISABLED pending a single-persistent-MPV refactor. The
 * Google TV Streamer (32-bit ARM, MediaTek SoC, limited RAM) cannot
 * reliably handle the rapid MPV create/destroy cycles that the
 * fresh-handle mini approach requires, and the re-parent-the-held-view
 * alternative hits mpv-android-lib's BaseMPVView surfaceDestroyed
 * blocking ANR. Iteration history:
 *
 *   - Phase 140: fresh MPV. Worked first time, broke second.
 *   - Phase 161/162: fresh MPV + async teardown. Worked once, then
 *     froze or stayed black on subsequent visits.
 *   - Phase 163: persistent MPV with re-parenting. 20s+ ANR on every
 *     Back press (removeView's surfaceDestroyed blocked the main
 *     thread waiting on libmpv's render thread).
 *   - Phase 163.3-163.5: surface-aware playFile timing variants.
 *     Mini stayed black, then app crashed after ~1s of audio.
 *
 * The proper fix needs ONE MPVPlayerView mounted at NavHost root that
 * never changes parents, with Compose-driven resize between fullscreen
 * and mini bounds (the iOS PlayerSession architecture). That's a
 * substantial refactor and shouldn't be attempted while iterating
 * against a broken release. Until then: short-circuit. No overlay
 * renders. BackHandler on TV mpvHolder.destroy() + onClose() so the
 * user gets a clean exit back to the guide.
 */
@Composable
fun BoxScope.TvMiniPlayerOverlay(
    @Suppress("UNUSED_PARAMETER") state: MiniPlayerSession.State,
    @Suppress("UNUSED_PARAMETER") mpvHolder: MPVPlayerHolder,
    @Suppress("UNUSED_PARAMETER") onResume: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onDismiss: () -> Unit,
) {
    // No-op until the TV mini-player is rearchitected. See file header.
}
