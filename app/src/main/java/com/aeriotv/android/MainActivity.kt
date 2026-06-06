package com.aeriotv.android

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aeriotv.android.core.pip.enterPip16x9
import com.aeriotv.android.core.playback.AerioMediaPlaybackService
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.playback.AerioExoPlayerHolder
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.system.NotificationPermissionGate
import com.aeriotv.android.core.update.AppUpdateManager
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import com.aeriotv.android.feature.player.ExoWindowState
import com.aeriotv.android.feature.player.PersistentExoWindow
import com.aeriotv.android.feature.splash.SplashGate
import com.aeriotv.android.feature.update.UpdatePromptDialog
import com.aeriotv.android.ui.theme.AerioTVTheme
import com.aeriotv.android.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var miniPlayerSession: MiniPlayerSession
    @Inject lateinit var exoHolder: AerioExoPlayerHolder
    @Inject lateinit var exoWindowState: ExoWindowState
    @Inject lateinit var updateManager: AppUpdateManager

    /**
     * Most recent deep-link target the activity has received from a
     * `aeriotv://channel/<id>` or `aeriotv://vod/<uuid>` Intent. Read by
     * the Compose tree via [DeepLinkTargetHolder] / a CompositionLocal
     * provider so NavHost can pop straight onto the target route once
     * the active playlist is ready. Drained (set null) after consumption
     * so a second tap on the same notification re-fires correctly.
     */
    private val deepLinkTarget = androidx.compose.runtime.mutableStateOf<DeepLinkTarget?>(null)

    /**
     * Mini-player resume gesture on TV remotes while mini-player is Active:
     * long-press Select (DPAD_CENTER / ENTER) restores fullscreen playback.
     * Single Select is consumed as a no-op so focus/clicks under the mini
     * overlay aren't accidentally triggered.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val miniActive = miniPlayerSession.state.value is MiniPlayerSession.State.Active
        if (miniActive &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            // Mini-player should always dismiss first on Back, regardless of
            // whichever screen currently owns D-pad focus behind the mini.
            miniPlayerSession.dismiss()
            exoWindowState.hide()
            exoHolder.stop()
            AerioMediaPlaybackService.stop(this)
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        val miniActive = miniPlayerSession.state.value is MiniPlayerSession.State.Active
        if (miniActive &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            Log.i("MiniPlayerResume", "onKeyLongPress detected -> requestResume()")
            miniPlayerSession.requestResume()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.inPictureInPicture.value = isInPictureInPictureMode
    }

    override fun onResume() {
        super.onResume()
        // Re-pin on resume so a fold/unfold display switch (the cover and inner
        // panels expose different display-mode ids) keeps the highest rate.
        requestHighestRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop means a second LAUNCH/aeriotv:// intent arrives here
        // instead of recreating the activity. Capture the URI for the
        // Compose tree.
        captureDeepLinkFrom(intent)
    }

    /**
     * Pull a deep-link target out of [intent.data] when the scheme is
     * `aeriotv`. Supported hosts: `channel`, `vod`. Path is the id /
     * uuid. Anything else is ignored.
     */
    private fun captureDeepLinkFrom(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.scheme.equals("aeriotv", ignoreCase = true)) return
        val host = data.host?.lowercase() ?: return
        val path = data.pathSegments?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        val target = when (host) {
            "channel" -> DeepLinkTarget.Channel(path)
            "vod" -> DeepLinkTarget.Vod(path)
            else -> null
        } ?: return
        deepLinkTarget.value = target
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        when {
            // Audio-only: never enter PiP. Keep a foreground media notification
            // alive so audio continues with status-bar + lock-screen controls.
            PipState.audioPlaybackActive.value -> {
                // MediaSession picks up title / subtitle / artwork from
                // MediaItem.mediaMetadata automatically; no extras needed.
                AerioMediaPlaybackService.startBackground(this)
            }
            // Video on API < 31 has no setAutoEnterEnabled, so trigger PiP here.
            // API 31+ auto-enters via the params synced in syncAutoEnterPip.
            PipState.videoPlaybackActive.value &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> enterPip16x9()
        }
    }

    /**
     * Mirror [PipState.videoPlaybackActive] into the window's PiP params so the
     * system auto-enters Picture-in-Picture on leave (API 31+). No-op on older
     * versions (handled by onUserLeaveHint) and on devices without PiP.
     */
    private fun syncAutoEnterPip(videoActive: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(videoActive)
                    .build(),
            )
        }
    }

    /**
     * Opt the window into the display's highest-refresh-rate mode at the current
     * resolution (e.g. 120Hz on the Z Fold panels) so the UI renders at the full
     * panel rate instead of being held at 60Hz. Samsung One UI in particular runs
     * apps that don't request a mode at 60Hz, and Android's frame-rate "category"
     * keeps non-voting surfaces low; pinning preferredDisplayModeId is the
     * documented opt-in. Filters to the current resolution so we never switch the
     * panel's pixel size, only its refresh rate. No-op when one mode exists.
     */
    private fun requestHighestRefreshRate() {
        val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION") windowManager.defaultDisplay
        } ?: return
        val current = disp.mode ?: return
        val best = disp.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate } ?: return
        if (window.attributes.preferredDisplayModeId != best.modeId) {
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = best.modeId
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the window's PiP params in sync with player video state so the
        // system auto-enters Picture-in-Picture when the user leaves the app while
        // video is playing (API 31+). Audio-only is excluded -- onUserLeaveHint
        // surfaces a background media notification for that case instead.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PipState.videoPlaybackActive.collect { syncAutoEnterPip(it) }
            }
        }
        // Startup update check: runs once per process lifetime (AppUpdateManager
        // guards against re-runs). Launched on CREATED so it fires as soon as
        // the activity exists, running in the background while the splash shows.
        lifecycleScope.launch {
            updateManager.checkOnStartup()
        }
        // Debug-only auto-load hook so dev iteration on emulators doesn't have to fight
        // Gboard's stylus tutorial when typing test URLs. Hard-gated behind BuildConfig.DEBUG
        // so release builds NEVER accept a URL via intent extra. Production deep-link
        // handling will introduce its own intent-filter when needed, not this path.
        val initialUrl = if (BuildConfig.DEBUG) intent?.getStringExtra("url") else null
        val initialEpgUrl = if (BuildConfig.DEBUG) intent?.getStringExtra("epg") else null
        val initialApiKey = if (BuildConfig.DEBUG) intent?.getStringExtra("apikey") else null
        // Audit task #47: parse the launching intent's data URI for a
        // aeriotv:// deep link. The Compose tree consumes deepLinkTarget
        // via a top-level effect, navigates, then clears it.
        captureDeepLinkFrom(intent)
        setContent {
            val updateState by updateManager.state.collectAsStateWithLifecycle()
            val theme by appPreferences.selectedTheme.collectAsState(initial = AppTheme.Aerio)
            val useCustomAccent by appPreferences.useCustomAccent.collectAsState(initial = false)
            val customAccentHex by appPreferences.customAccentHex.collectAsState(initial = "")
            val miniPlayerPosition by appPreferences.guideMiniPlayerPosition
                .collectAsState(initial = "top_right")
            val customAccent = if (useCustomAccent && customAccentHex.length == 6) {
                runCatching {
                    val n = customAccentHex.toLong(16)
                    androidx.compose.ui.graphics.Color(
                        red = ((n shr 16) and 0xFF).toInt(),
                        green = ((n shr 8) and 0xFF).toInt(),
                        blue = (n and 0xFF).toInt(),
                    )
                }.getOrNull()
            } else null
            AerioTVTheme(appTheme = theme, customAccent = customAccent) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotificationPermissionGate()
                    // Update prompt: overlays on top of everything. The dialog
                    // manages its own visibility based on AppUpdateManager.state.
                    UpdatePromptDialog(
                        state = updateState,
                        onDismiss = { updateManager.dismiss() },
                        onDownload = { info ->
                            lifecycleScope.launch { updateManager.startDownload(info) }
                        },
                        onInstall = { apkFile -> updateManager.install(apkFile) },
                    )
                    SplashGate {
                        // Phase 165/167: PersistentMpvWindow lives as a
                        // SIBLING of NavHost inside an outer Box. The
                        // video SurfaceView is mounted ONCE at this scope
                        // and never changes parents -- only its modifier
                        // (Hidden / Fullscreen / Mini) flips.
                        //
                        // ORDER MATTERS: PersistentMpvWindow is declared
                        // FIRST so it draws at the BOTTOM of the Box's
                        // z-stack. NavHost (containing PlayerScreen's
                        // chrome overlay) is declared SECOND so its
                        // children draw ON TOP, occluding the
                        // PersistentMpvWindow's black backing wherever
                        // chrome controls are visible. Without this
                        // ordering, PersistentMpvWindow's
                        // fillMaxSize+black background paints over the
                        // chrome and the user only sees the SurfaceView
                        // punch-through (video) -- chrome IS in state
                        // but never reaches the pixels.
                        Box(modifier = Modifier.fillMaxSize()) {
                            // PersistentExoWindow is declared FIRST so it
                            // sits at the bottom of the z-stack. Fullscreen
                            // mode = NavHost (containing PlayerScreen
                            // chrome) paints OVER the video; Mini mode
                            // lifts via zIndex(1f) (Phase 175 fix). Live
                            // TV mounts here; VOD owns its own per-screen
                            // PlayerView; multiview owns per-tile
                            // PlayerViews.
                            PersistentExoWindow(
                                holder = exoHolder,
                                state = exoWindowState,
                                miniPosition = miniPlayerPosition,
                            )
                            AerioTVNavHost(
                                initialUrl = initialUrl,
                                initialEpgUrl = initialEpgUrl,
                                initialApiKey = initialApiKey,
                                deepLinkTarget = deepLinkTarget.value,
                                onDeepLinkConsumed = { deepLinkTarget.value = null },
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object
}
