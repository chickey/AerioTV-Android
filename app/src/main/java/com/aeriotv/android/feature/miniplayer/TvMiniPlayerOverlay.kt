package com.aeriotv.android.feature.miniplayer

import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aeriotv.android.feature.main.MainScaffoldEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * Android TV mini-player: a small top-right window that continues showing the
 * channel video after the user backs out of the full-screen PlayerScreen onto
 * the guide. tvOS-PlayerSession parity. The Google TV Streamer remote has no
 * play/pause key, so the resume affordance is double-press of D-pad Select
 * (wired in MainActivity.dispatchKeyEvent); a hint underneath the window
 * tells the user that.
 *
 * Video continuity is "seamless" because [com.aeriotv.android.core.playback.MPVPlayerHolder]
 * is a @Singleton that retains the MPVPlayerView across composable
 * lifecycles. PlayerScreen's AndroidView.onRelease calls
 * [MPVPlayerHolder.detach] which removes the view from its old parent without
 * destroying mpv; this overlay's AndroidView factory then re-parents the
 * same view into its own frame, and mpv keeps decoding through the swap.
 * On resume, the same flip happens in reverse.
 *
 * The phone path (MainScaffold's [MiniPlayerRow] above the bottom nav)
 * continues to render the audio-only chip on phones; this overlay only
 * renders on TV.
 */
@Composable
fun BoxScope.TvMiniPlayerOverlay(
    state: MiniPlayerSession.State,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state !is MiniPlayerSession.State.Active) return
    val context = LocalContext.current
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    if (!isTv) return  // phone uses MiniPlayerRow above the bottom nav.

    val mpvHolder = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            MainScaffoldEntryPoint::class.java,
        ).mpvPlayerHolder()
    }

    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            // 5% overscan-safe inset matching the rest of TV chrome.
            .padding(end = 40.dp, top = 36.dp)
            .clickable(onClick = onResume),
        horizontalAlignment = Alignment.End,
    ) {
        // ── Video window ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(width = 320.dp, height = 180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    val held = mpvHolder.view
                    if (held != null) {
                        // Detach from whatever parent it was last in
                        // (PlayerScreen's AndroidView typically) so this
                        // frame can adopt it. The mpv handle keeps decoding.
                        (held.parent as? ViewGroup)?.removeView(held)
                        held
                    } else {
                        // No surviving mpv handle (edge case: holder destroyed
                        // mid-transition). Render an empty black frame; the
                        // user can resume to re-create the player.
                        FrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { v ->
                    // Detach but DON'T destroy - the same view goes back to
                    // PlayerScreen's AndroidView when the user resumes. The
                    // holder.detach() call is idempotent.
                    if (v === mpvHolder.view) {
                        (v.parent as? ViewGroup)?.removeView(v)
                    }
                },
            )
            // Dismiss (X) button - small chip top-right, doesn't steal focus
            // from the underlying guide on TV.
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss mini-player",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Resume hint ───────────────────────────────────────────────
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
