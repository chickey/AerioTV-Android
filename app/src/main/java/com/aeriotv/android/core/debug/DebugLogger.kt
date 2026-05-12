package com.aeriotv.android.core.debug

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * File-backed diagnostic logger. Mirrors iOS DebugLogger
 * (Aerio/Shared/DebugLogger.swift): the user toggles logging on from
 * Settings -> Developer, every `debugLog(tag, message)` call (and every
 * routed `Log.i` / `Log.w` from this app's packages) is written into a
 * plain-text file inside the app's private filesDir, the file rotates
 * automatically at 10 MB, the user can view / share / clear it from the
 * same screen.
 *
 * Why a singleton and not just `android.util.Log`: the OS logcat is
 * volatile — Android wipes it on reboot and trims aggressively while the
 * device is foregrounded. Bug reports need a persistent artifact the user
 * can attach to a GitHub issue without paired-device tooling.
 *
 * Threading: the writer runs on a single coroutine pulling from a
 * Channel<String>, so callers never block on disk I/O. The toggle is
 * stored as an AtomicBoolean to skip the queue allocation cheaply when
 * logging is disabled.
 */
@Singleton
class DebugLogger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabled = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (line in queue) {
                writeLine(line)
            }
        }
    }

    /** Top-level on/off; flipped by Settings -> Developer -> Debug Logging. */
    fun isEnabled(): Boolean = enabled.get()

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (value) {
            // Mark the moment logging came on so a future bug report has a
            // visible start-of-session anchor.
            log("DebugLogger", Level.INFO, "Debug logging ENABLED")
        }
    }

    enum class Level(val tag: String) { VERBOSE("V"), DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    fun log(tag: String, level: Level, message: String, throwable: Throwable? = null) {
        // Always echo to logcat so devices on `adb logcat` still see it
        // even when file-logging is off. This is the same dual-emit
        // behavior iOS uses (DebugLogger.swift line 56).
        when (level) {
            Level.VERBOSE -> Log.v(tag, message, throwable)
            Level.DEBUG -> Log.d(tag, message, throwable)
            Level.INFO -> Log.i(tag, message, throwable)
            Level.WARN -> Log.w(tag, message, throwable)
            Level.ERROR -> Log.e(tag, message, throwable)
        }
        if (!enabled.get()) return
        val ts = TIMESTAMP_FMT.format(Date())
        val combined = buildString {
            append(ts).append(' ').append(level.tag).append('/').append(tag).append(": ").append(message)
            if (throwable != null) {
                appendLine()
                throwable.stackTraceToString().lines().forEach { appendLine("    $it") }
            }
        }
        queue.trySend(combined)
    }

    fun clearLogs() {
        scope.launch {
            runCatching {
                logFile().writeText("")
                archiveFile().delete()
            }
        }
    }

    /** Absolute path the user-facing UI uses for the "View / Share" rows. */
    fun logFile(): File {
        val dir = File(context.filesDir, LOGS_DIR).apply { mkdirs() }
        return File(dir, LOG_FILE)
    }

    fun archiveFile(): File = File(File(context.filesDir, LOGS_DIR), ARCHIVE_FILE)

    /** Size of the live log file in bytes. Returns 0 when the file is missing. */
    fun logSizeBytes(): Long {
        val f = logFile()
        return if (f.exists()) f.length() else 0L
    }

    private fun writeLine(line: String) {
        val f = logFile()
        runCatching {
            if (f.length() > MAX_BYTES) {
                runCatching {
                    archiveFile().delete()
                    f.renameTo(archiveFile())
                }
            }
            PrintWriter(f.outputStream().bufferedWriter().also {
                f.appendText("")  // ensure file exists for append
            }).use { /* noop */ }
            f.appendText(line + System.lineSeparator())
        }.onFailure { t ->
            Log.w(TAG, "Failed to append log line: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "DebugLogger"
        const val LOGS_DIR = "logs"
        const val LOG_FILE = "aerio_debug_logs.txt"
        const val ARCHIVE_FILE = "aerio_debug_logs_archive.txt"
        const val MAX_BYTES = 10L * 1024 * 1024  // 10 MB, matches iOS rotation threshold

        // Locale.US + literal pattern is intentional — log timestamps need to
        // be locale-independent so bug-report submitters from different
        // regions produce files that parse identically. iOS uses ISO 8601
        // for the same reason.
        val TIMESTAMP_FMT: SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
