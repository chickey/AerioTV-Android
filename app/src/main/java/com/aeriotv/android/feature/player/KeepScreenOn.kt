package com.aeriotv.android.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Marks the host Activity's window as "keep screen on" for the duration of
 * the calling composable. When the composable leaves composition (user backs
 * out of the player, switches tabs, etc.) the flag is cleared and normal
 * screen-timeout rules resume.
 *
 * Ports iOS `IdleTimerRefCount.increment()` / `decrement()` from
 * Aerio/Shared/IdleTimerRefCount.swift, which iOS calls on playback start
 * and stop. Same intent: don't let the display dim/sleep while the user
 * is watching video. Without this, the system screen-timeout fires after
 * its configured idle window (often 30s-2min on Samsung / OEM defaults)
 * mid-stream, the panel sleeps, and the user has to wake the phone to keep
 * watching -- exactly the symptom the user reported.
 *
 * Implementation note: the canonical Android pattern is
 * `Window.addFlags(FLAG_KEEP_SCREEN_ON)` rather than a `PowerManager.WakeLock`.
 * The flag approach is honored by the system without any permission, doesn't
 * touch the audio/CPU subsystems, and automatically tracks Activity
 * lifecycle (cleared when the Activity goes to background by the OS).
 * Multiple composables can request the flag concurrently and the system
 * coalesces them; on dispose this composable removes its own request, but
 * if another player composable is still mounted (e.g. mini-player + main
 * player swap) the flag stays in place via Android's internal counter.
 */
@Composable
fun KeepScreenOnWhilePlaying() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivityCompat()
        ScreenOnRequests.acquire(activity)
        onDispose {
            ScreenOnRequests.release(activity)
        }
    }
}

private object ScreenOnRequests {
    private var count = 0

    @Synchronized
    fun acquire(activity: Activity?) {
        if (activity == null) return
        count += 1
        activity.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Synchronized
    fun release(activity: Activity?) {
        if (activity == null) return
        count = (count - 1).coerceAtLeast(0)
        if (count == 0) {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * Unwrap an Activity out of a (possibly themed) Context chain. Mirrors the
 * `findActivity` helper already used by the PiP plumbing -- declared here
 * with a different name to avoid clashing with the core/pip variant when
 * both are imported in the same file (Kotlin's resolution would pick one
 * arbitrarily and confuse the caller). Functionally identical.
 */
private fun Context.findActivityCompat(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
