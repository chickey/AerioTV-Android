package com.aeriotv.android.feature.miniplayer

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aeriotv.android.feature.player.MPVPlayerView
import `is`.xyz.mpv.Utils

/**
 * Android TV mini-player: a small top-right window that keeps the channel
 * playing after the user backs out of the fullscreen PlayerScreen onto the
 * guide. tvOS PlayerSession parity (the "small window in the top-right with
 * the stream still playing" Archie pointed to in screenshots).
 *
 * Architecture choice: this overlay creates a FRESH MPVPlayerView with its
 * own MPV handle and re-plays the channel URL, rather than re-parenting the
 * fullscreen MPVPlayerView. The reparenting path technically works for one
 * frame but mpv-android-lib's BaseMPVView clears `vo` to null when the
 * SurfaceView's surfaceDestroyed callback fires between parents, and the
 * post-attach restoration is not reliable - producing a black mini. The
 * fresh-handle path is straightforward, robust, and the (3-5 second) buffer
 * gap matches the user's expectation that mpv has to re-start a stream.
 *
 * PlayerScreen.BackHandler on TV destroys the held MPV before this overlay
 * mounts (clean handoff, no double-decoding the same URL). The resume flow
 * (double-press Select wired in MainActivity.dispatchKeyEvent, or tap)
 * re-pushes the PLAYER route which creates a fresh handle of its own.
 *
 * TV-only - phone uses [MiniPlayerRow] above the bottom nav.
 */
@Composable
fun BoxScope.TvMiniPlayerOverlay(
    state: MiniPlayerSession.State,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state !is MiniPlayerSession.State.Active) return
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    if (!isTv) return
    val channel = state.channel
    if (channel.url.isBlank()) return

    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            // ~5% overscan-safe inset, matching the rest of the TV chrome.
            .padding(end = 40.dp, top = 36.dp)
            .clickable(onClick = onResume),
        horizontalAlignment = Alignment.End,
    ) {
        // ── Video window ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(width = 280.dp, height = 158.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            AndroidView(
                factory = { ctx ->
                    Utils.copyAssets(ctx)
                    val configDir = ctx.filesDir.path
                    val cacheDir = ctx.cacheDir.path
                    val view = MPVPlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.isLive = true
                        this.caFilePath = "$configDir/cacert.pem"
                        // Smaller cache than the fullscreen player - 4MiB
                        // demuxer buffer is plenty for a 158dp preview and
                        // keeps native memory low while the user is on the
                        // guide.
                        this.cachingMs = 3_000
                    }
                    view.initialize(configDir, cacheDir)
                    view.playFile(channel.url)
                    view
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { v ->
                    // Tear down completely on unmount. Resume creates a
                    // fresh handle in PlayerScreen, so there's nothing to
                    // hand off.
                    runCatching { v.destroy() }
                },
            )
        }

        Spacer(Modifier.height(6.dp))

        // ── Resume hint (below the video, subtle) ────────────────────
        Text(
            text = "Double press OK to resume",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
