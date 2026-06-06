package com.aeriotv.android.core.debug

import android.content.ComponentCallbacks2
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide fan-out of [android.content.ComponentCallbacks2.onTrimMemory]
 * levels to anyone who can shed memory on demand (in-memory EPG, parsed
 * playlists, large bitmap caches, etc).
 *
 * Pattern mirrors [ReminderBannerBus]: a @Singleton Hilt-injected event bus
 * that the Application emits into and ViewModels observe.
 *
 * Why a bus instead of letting Application call into each shedder directly:
 *   1. ViewModels are scoped to the nav graph and may not exist when the
 *      pressure fires; the SharedFlow buffers the most recent level so a VM
 *      created seconds later still sees the signal (replay = 1).
 *   2. Avoids a cycle of Hilt entry points between Application and each
 *      feature module.
 *
 * Levels are passed through verbatim from onTrimMemory so consumers can
 * decide their own threshold (some only care about RUNNING_CRITICAL /
 * COMPLETE, others might shed at MODERATE).
 */
@Singleton
class MemoryPressureBus @Inject constructor() {

    /**
     * Replay=1 + DROP_OLDEST: a late subscriber gets the most recent signal,
     * and a rapid-fire burst (some OEMs fire trim N times in 100ms during
     * a low-memory killer pass) doesn't queue up duplicate work for shedders.
     */
    private val _level = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val level: SharedFlow<Int> = _level.asSharedFlow()

    fun emit(level: Int) {
        // tryEmit on a buffered SharedFlow with DROP_OLDEST always succeeds;
        // no need to suspend the caller (onTrimMemory is main-thread).
        _level.tryEmit(level)
    }

    companion object {
        /** Shorthand for callers that don't want to import ComponentCallbacks2. */
        fun isCritical(level: Int): Boolean =
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL

        /**
         * Whether a trim level warrants shedding large derived caches (EPG map,
         * parsed playlists). This is NOT the same as [isCritical].
         *
         * The onTrimMemory levels do not form a single severity scale: the
         * foreground "running" levels (RUNNING_MODERATE=5, RUNNING_LOW=10,
         * RUNNING_CRITICAL=15) signal real device-memory pressure while the app
         * is in use, whereas the higher-numbered background levels
         * (UI_HIDDEN=20, BACKGROUND=40, MODERATE=60, COMPLETE=80) simply report
         * where the process sits in the LRU once it is no longer visible and
         * fire as a routine part of every background transition.
         *
         * A naive `level >= RUNNING_CRITICAL (15)` therefore matches UI_HIDDEN
         * too, so the app shed its EPG every time it was briefly hidden. We
         * deliberately skip UI_HIDDEN (a light, frequent signal) and shed only
         * on genuine foreground pressure (RUNNING_CRITICAL) or deeper background
         * levels (BACKGROUND and beyond) where freeing memory actually improves
         * survival. The shed is always paired with foreground re-hydration from
         * the disk cache, so it is invisible to the user either way.
         */
        fun shouldShedCaches(level: Int): Boolean =
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
    }
}
