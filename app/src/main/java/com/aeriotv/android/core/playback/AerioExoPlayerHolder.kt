package com.aeriotv.android.core.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Media3 ExoPlayer holder mirroring [MPVPlayerHolder]'s lifetime contract.
 * Hoists ONE [ExoPlayer] instance out of any single composable lifecycle so
 * the underlying codec + audio renderer survives PlayerScreen <-> mini
 * transitions and channel switches. Without this, every nav transition
 * tears the player down and the next open costs a fresh MediaCodec
 * allocation + DataSource warm-up.
 *
 * Pattern (intentionally identical to MPVPlayerHolder so PlayerScreen
 * doesn't need to know which player is mounted):
 *   - PersistentExoWindow.factory calls [acquireOrCreate] on first
 *     composition. Subsequent calls (after back-out + resume) return
 *     the same ExoPlayer reference; the caller just rebinds the
 *     PlayerView's surface to it.
 *   - AndroidView's onRelease calls [detach] instead of release(),
 *     leaving ExoPlayer alive while surface is unparented.
 *   - X-close goes through [destroy] which releases the player.
 *
 * Live TV scaffold for the Media3 migration. VOD, MediaSession, and
 * multiview are subsequent tasks (#62, #63, #64).
 *
 * Threading: all entry points expect main thread (ExoPlayer's
 * `Looper.getMainLooper()` requirement).
 */
@OptIn(UnstableApi::class)
@Singleton
class AerioExoPlayerHolder @Inject constructor() {

    var player: ExoPlayer? = null
        private set

    /** Most-recent channel id played, so a resuming PlayerScreen knows
     *  whether to skip the setMediaItem re-init. */
    var currentChannelId: String? = null

    /** Currently-applied custom HTTP headers, replayed onto the
     *  DataSource.Factory each time we build a MediaSource. Dispatcharr
     *  API-key auth lives here. */
    var httpHeaders: Map<String, String> = emptyMap()

    private val holderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Becomes true when the player reaches STATE_READY and starts playing; false
    // on playUrl() so the follow poller never overlaps the cold-start buffer.
    private val _reachedSteadyPlayback = MutableStateFlow(false)
    val reachedSteadyPlayback: StateFlow<Boolean> = _reachedSteadyPlayback.asStateFlow()

    private val reprimeMutex = Mutex()
    @Volatile private var reprimeInFlight = false
    val isReprimeInFlight: Boolean get() = reprimeInFlight

    /**
     * Return the active ExoPlayer, creating it once on first call.
     * The caller is expected to bind it to a PlayerView via
     * `playerView.player = holder.acquireOrCreate(...)`.
     */
    fun acquireOrCreate(
        context: Context,
    ): ExoPlayer {
        player?.let { return it }
        Log.i(TAG, "Creating fresh ExoPlayer in holder")

        // RenderersFactory: enable SW fallback (Media3 equivalent of
        // mpv's hwdec-software-fallback). On the rare codec that fails
        // HW init the renderer transparently retries SW. The QTI HEVC-
        // in-TS bug we hit on libmpv is fixed at this layer: Media3's
        // MediaCodecRenderer pulls SPS/VPS/PPS out of in-band Annex-B
        // NALs before MediaCodec.configure, so we don't even need the
        // fallback for that case -- HW just works.
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // LoadControl: live-stream-friendly buffer durations. The
        // defaults (50s min, 50s max for VOD) over-buffer for live and
        // delay channel-tap response. Mirror our libmpv `cache-secs=5`
        // for live: keep ~2.5s buffered so a brief network hiccup
        // doesn't stall, but don't wait long before showing first frame.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2_500,
                /* maxBufferMs = */ 5_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val fresh = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            // Persistent-view architecture: handleAudioBecomingNoisy
            // pauses on headphone unplug. This is Media3's built-in
            // equivalent of the audio focus handling we hand-rolled
            // for MPV.
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(LoggingPlayerListener)
                addListener(SteadyPlaybackListener)
                // Repeat off for live; setRepeatMode(REPEAT_MODE_ONE) is
                // a VOD concern.
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
            }

        player = fresh
        return fresh
    }

    /**
     * Build a MediaSource appropriate to the URL + apply the current
     * HTTP headers. The factory is rebuilt each time so the latest
     * headers (Dispatcharr API key, custom User-Agent) ride along.
     *
     * Optional metadata (channel name / program / logo) is attached
     * to the MediaItem so MediaSessionService can render its
     * notification + lock-screen art automatically. We mirror the
     * iOS NowPlayingManager fields here.
     */
    fun buildMediaSource(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
    ): MediaSource {
        val dataSourceFactory = httpDataSourceFactory()

        // Force-route raw .ts URLs through ProgressiveMediaSource +
        // TsExtractor. Without this, DefaultMediaSourceFactory looks at
        // the file extension and might mis-identify or fall through to
        // a generic path that doesn't know how to extract HEVC SPS/VPS/
        // PPS from MPEG-TS in-band NAL units.
        //
        // Dispatcharr serves channels as
        //   http://<host>:<port>/proxy/ts/stream/<uuid>
        // which has no extension. We detect raw TS by URL shape AND let
        // DefaultMediaSourceFactory handle .m3u8 (HLS) / .mpd (DASH) /
        // .mp4 (progressive) on its own.
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUri)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId(title.orEmpty().ifBlank { url })
            .setMediaMetadata(mediaMetadata)
            .build()
        return when {
            isRawTsUrl(url) -> {
                // SINGLE_PMT is what HlsMediaSource uses internally and
                // what nearly every IPTV provider delivers: one program,
                // one PMT, one video PID, one or more audio PIDs.
                // MULTI_PMT is for mux'd transports with sibling programs
                // (BBC HD vs SD on the same TS) which Dispatcharr / Xtream
                // proxies never deliver.
                //
                // No additional FLAG_* on Media3 1.4 -- the only one
                // available is FLAG_EMIT_RAW_SUBTITLE_DATA which we leave
                // off (subtitle handling is task #66 and the parser
                // factory route is cleaner anyway).
                // TS-only extractor factory: skip the 21-extractor sniff that can
                // fail on a mid-packet (non-0x47-aligned) join from /proxy/ts/stream,
                // causing an UnrecognizedInputFormatException and doubling cold start.
                // TsExtractor scans for the sync byte itself; supplying exactly one
                // extractor makes BundledExtractorsAdapter skip the sniff entirely.
                val tsExtractorsFactory = ExtractorsFactory {
                    val all: Array<Extractor> = DefaultExtractorsFactory()
                        .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
                        .createExtractors()
                    val tsOnly: List<Extractor> = all.filterIsInstance<TsExtractor>()
                    if (tsOnly.isNotEmpty()) tsOnly.toTypedArray() else all
                }
                ProgressiveMediaSource.Factory(dataSourceFactory, tsExtractorsFactory)
                    .createMediaSource(mediaItem)
            }
            url.endsWith(".m3u8", ignoreCase = true) -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }
            else -> {
                DefaultMediaSourceFactory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }
        }
    }

    /**
     * Set the media item + start loading. Equivalent of MPV's
     * mpv.command("loadfile", url). Pass [title] / [subtitle] /
     * [artworkUri] for the MediaSession notification + lock-screen
     * art.
     */
    fun playUrl(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
    ) {
        val p = player ?: run {
            Log.w(TAG, "playUrl called before acquireOrCreate")
            return
        }
        _reachedSteadyPlayback.value = false
        val source = buildMediaSource(url, title, subtitle, artworkUri)
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    /**
     * Re-prime the SAME proxy [url] with a keepalive connection held open across
     * the flush so the channel's client count never hits 0. Without this,
     * Dispatcharr's default channel_shutdown_delay=0 fires stop_channel which
     * deletes channel_stream:{id}, and the reconnect cold-resolves to the
     * channel's DEFAULT stream instead of the just-switched one.
     *
     * Serialised via [reprimeMutex]. [bypassCooldown] lets a user-initiated
     * switch always run even if a recent auto-reload fired. Returns true if
     * the re-prime actually ran.
     */
    suspend fun reprimeWithKeepalive(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
        bypassCooldown: Boolean = false,
        keepaliveHoldMs: Long = 5_000L,
    ): Boolean = reprimeMutex.withLock {
        reprimeInFlight = true
        try {
            val connHolder = java.util.concurrent.atomic.AtomicReference<java.net.HttpURLConnection?>(null)
            val connected = CompletableDeferred<Boolean>()
            val keepAlive = holderScope.launch {
                try {
                    val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 4_000
                        readTimeout = 8_000
                        requestMethod = "GET"
                        httpHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                        setRequestProperty("User-Agent", "AerioTV-switch-keepalive")
                    }
                    connHolder.set(c)
                    c.inputStream.use { ins ->
                        val buf = ByteArray(32 * 1024)
                        if (ins.read(buf) >= 0 && !connected.isCompleted) connected.complete(true)
                        while (isActive) { if (ins.read(buf) < 0) break }
                    }
                } catch (_: Throwable) {
                    // best-effort; re-prime proceeds regardless
                } finally {
                    if (!connected.isCompleted) connected.complete(false)
                    runCatching { connHolder.get()?.disconnect() }
                }
            }
            withTimeoutOrNull(4_000L) { connected.await() }
            withContext(Dispatchers.Main) { playUrl(url, title, subtitle, artworkUri) }
            delay(keepaliveHoldMs)
            keepAlive.cancel()
            runCatching { connHolder.get()?.disconnect() }
            true
        } finally {
            reprimeInFlight = false
        }
    }

    private fun httpDataSourceFactory(): DataSource.Factory {
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
        // Apply Dispatcharr API-key / custom User-Agent. Headers are
        // applied verbatim; the User-Agent header (if present) replaces
        // the default.
        if (httpHeaders.isNotEmpty()) {
            factory.setDefaultRequestProperties(httpHeaders)
            httpHeaders.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value
                ?.let(factory::setUserAgent)
        }
        return factory
    }

    private fun isRawTsUrl(url: String): Boolean {
        if (url.endsWith(".ts", ignoreCase = true)) return true
        // Dispatcharr / Xtream proxy URLs that have no file extension
        // but ARE raw MPEG-TS. The path shape is the strongest signal:
        //   /proxy/ts/stream/<uuid>
        //   /live/<user>/<pass>/<id>.ts
        //   /stream/<id>.ts
        if (url.contains("/proxy/ts/", ignoreCase = true)) return true
        if (url.contains("/live/", ignoreCase = true) && !url.contains(".m3u8")) return true
        return false
    }

    /**
     * Composable-unmount hook. Does NOT release ExoPlayer; the
     * persistent-view architecture keeps it alive across screen
     * transitions. Media3's setVideoSurface(null) cleanly releases
     * the surface binding without tearing down decode state.
     */
    fun detach() {
        val p = player ?: return
        p.setVideoSurface(null)
    }

    /** Stop playback without releasing the player. Used by the X-close
     *  and the mini's 3rd-Back dismiss. Equivalent of MPV's
     *  command("stop"). */
    fun stop() {
        val p = player ?: return
        currentChannelId = null
        p.stop()
        p.clearMediaItems()
    }

    fun setPaused(paused: Boolean) {
        player?.playWhenReady = !paused
    }

    fun isPaused(): Boolean = player?.playWhenReady?.not() ?: true

    /**
     * Total video frames rendered since the current player instance was created.
     * Returns -1 when the video decoder counters are not yet available (player
     * not yet initialised, or video renderer hasn't produced any output yet).
     *
     * Used by the freeze watchdog in PlayerScreen: when the video decoder stalls
     * (HEVC decoder hangs) but the EAC3 audio renderer keeps the media clock
     * ticking, [ExoPlayer.currentPosition] continues to advance and the plain
     * "position not moving" heuristic never fires. Watching the rendered-frame
     * counter catches that scenario because it only advances when the video
     * renderer actually pushes a frame to the surface.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val renderedVideoFrameCount: Int
        get() = player?.videoDecoderCounters?.renderedOutputBufferCount ?: -1

    /** Full teardown for the X-close button. Releases the codec,
     *  audio renderer, and DataSource. Next acquire creates fresh. */
    fun destroy() {
        val p = player ?: return
        player = null
        currentChannelId = null
        _reachedSteadyPlayback.value = false
        try {
            p.removeListener(LoggingPlayerListener)
            p.removeListener(SteadyPlaybackListener)
            p.release()
        } catch (t: Throwable) {
            Log.w(TAG, "ExoPlayer release failed", t)
        }
    }

    private object LoggingPlayerListener : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} (${error.errorCode})", error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val label = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.i(TAG, "ExoPlayer state -> $label")
        }
    }

    private inner class SteadyPlaybackListenerImpl : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) _reachedSteadyPlayback.value = true
        }
    }
    private val SteadyPlaybackListener = SteadyPlaybackListenerImpl()

    companion object {
        private const val TAG = "AerioExoPlayer"
    }
}
