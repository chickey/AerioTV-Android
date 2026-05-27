package com.aeriotv.android.core.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aeriotv.android.MainActivity
import com.aeriotv.android.R
import com.aeriotv.android.core.network.PlaylistFetcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the held MPV instance alive after the user
 * backs out of the fullscreen player. Without a foreground service Android
 * eventually frees the process and the audio cuts.
 *
 * Uses `foregroundServiceType=mediaPlayback` already declared in the manifest
 * for the DVR recording service — same type fits this use case.
 *
 * Notification surfaces a play/pause action that toggles MPV pause via the
 * [MPVPlayerHolder] singleton, shows the channel logo as the large icon, and
 * routes tap → MainActivity (SINGLE_TOP, so it resumes the running stream).
 */
@AndroidEntryPoint
class PlaybackService : Service() {

    @Inject lateinit var holder: MPVPlayerHolder
    @Inject lateinit var fetcher: PlaylistFetcher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "AerioTV"
                val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
                val logoUrl = intent.getStringExtra(EXTRA_LOGO)
                ensureChannel()
                // Show immediately (with the app icon) to satisfy the
                // startForeground deadline, then swap in the channel logo once
                // it's fetched. A failed / absent logo just keeps the app icon.
                startForegroundCompat(buildNotification(title, subtitle, null))
                if (!logoUrl.isNullOrBlank()) {
                    scope.launch {
                        val bmp = runCatching {
                            val bytes = fetcher.fetchBytes(logoUrl)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()
                        if (bmp != null) {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIF_ID, buildNotification(title, subtitle, bmp))
                        }
                    }
                }
            }
            ACTION_TOGGLE_PAUSE -> {
                val nowPaused = holder.isPaused()
                holder.setPaused(!nowPaused)
            }
            ACTION_STOP -> {
                // CRITICAL: do NOT call holder.destroy() here. This service
                // entry point fires from BOTH:
                //   1. The mini-player Dismiss button (user wants MPV gone).
                //   2. Every PlayerScreen mount (LaunchedEffect(Unit) ->
                //      PlaybackService.stop, telling the bg service to drop
                //      its notification because we're foreground again).
                // Case 2 wants to keep MPV alive -- we just turned video on
                // and called playFile microseconds earlier; destroying MPV
                // here is exactly the "stream loads chrome but never plays"
                // regression. Case 1 already calls mpvHolder.destroy()
                // explicitly in MiniPlayerRow.onDismiss before invoking
                // this action, so MPV teardown is owned by the caller, not
                // by ACTION_STOP. ACTION_STOP is just "remove notification +
                // exit foreground service" from now on.
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing notification while AerioTV plays audio in the background."
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, subtitle: String, largeIcon: Bitmap?): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            // SINGLE_TOP (not CLEAR_TOP) so tapping the notification RESUMES the
            // existing activity via onNewIntent -- the player + its stream are
            // still composed in the background -- instead of recreating it.
            // CLEAR_TOP on a standard/singleTop activity tears the task down and
            // relaunches fresh, which is what reopened the app instead of jumping
            // back into the running stream.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseToggle = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle.ifBlank { "Playing in background" })
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Show full content on the lock screen so audio-only background
            // playback has reachable controls there, not just in the shade.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Pause / Resume", pauseToggle)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
        if (largeIcon != null) builder.setLargeIcon(largeIcon)
        return builder.build()
    }

    companion object {
        const val ACTION_START = "com.aeriotv.android.PLAYBACK_START"
        const val ACTION_TOGGLE_PAUSE = "com.aeriotv.android.PLAYBACK_TOGGLE_PAUSE"
        const val ACTION_STOP = "com.aeriotv.android.PLAYBACK_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_LOGO = "logo"
        private const val CHANNEL_ID = "aeriotv_background_playback"
        private const val NOTIF_ID = 0xAF

        fun startBackground(context: Context, title: String, subtitle: String, logoUrl: String? = null) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
                .putExtra(EXTRA_LOGO, logoUrl)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
