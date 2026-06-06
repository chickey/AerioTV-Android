package com.aeriotv.android.core.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.aeriotv.android.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AppUpdateManager"

/**
 * Manages the full self-update lifecycle for sideloaded Fire TV builds:
 *
 *   Idle → Checking → UpdateAvailable → Downloading → ReadyToInstall
 *                   ↘ UpToDate
 *                   ↘ Error
 *
 * [checkOnStartup] runs once per process lifetime (guarded by [startupCheckDone]).
 * [checkNow] is the manual path called from Settings.
 *
 * The dialog in MainActivity collects [state] and renders the appropriate UI.
 * [UpToDate] and [Error] from startup checks resolve to [Idle] silently; from
 * manual checks they stay visible so the Settings row can show feedback.
 */
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: GithubUpdateChecker,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpdateAvailable(val info: UpdateInfo) : State
        data object UpToDate : State
        data class Downloading(val info: UpdateInfo, val progressPercent: Int) : State
        data class ReadyToInstall(val apkFile: File, val info: UpdateInfo) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True once a startup check has been initiated this process lifetime. */
    @Volatile private var startupCheckDone = false

    /**
     * Called once on app startup. No-ops on subsequent calls so activity
     * recreation (rotation, back-stack) never re-prompts the user.
     * Silently resolves to [State.Idle] when already up to date or on error
     * (the user didn't ask for a check, so no UI feedback is needed).
     */
    suspend fun checkOnStartup() {
        if (!BuildConfig.GITHUB_UPDATES_ENABLED) return
        if (startupCheckDone) return
        startupCheckDone = true
        performCheck(showFeedback = false)
    }

    /**
     * Manual check triggered from Settings. Always runs and leaves
     * [State.UpToDate] or [State.Error] visible so the Settings row can
     * show meaningful feedback. The caller is responsible for resetting
     * to [State.Idle] after the feedback period (see [UpdateViewModel]).
     */
    suspend fun checkNow() {
        if (!BuildConfig.GITHUB_UPDATES_ENABLED) return
        performCheck(showFeedback = true)
    }

    /**
     * Download the release APK to the app's private cache directory.
     * Progress is reported via [State.Downloading.progressPercent] (0–100).
     * On completion transitions to [State.ReadyToInstall].
     */
    suspend fun startDownload(info: UpdateInfo) {
        val outFile = File(context.cacheDir, "AerioTV-update.apk")
        runCatching { outFile.delete() }
        _state.value = State.Downloading(info, 0)

        runCatching {
            val client = HttpClient(OkHttp) {
                install(HttpTimeout) {
                    // APK is ~50–80 MB; allow up to 10 min on slow connections.
                    requestTimeoutMillis = 10 * 60_000L
                    connectTimeoutMillis = 30_000
                    socketTimeoutMillis = 60_000
                }
            }
            client.use { http ->
                val response = http.get(downloadUrl()) {
                    header("User-Agent", "AerioTV-Android/${BuildConfig.VERSION_NAME}")
                }
                val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                val channel = response.bodyAsChannel()
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                var received = 0L
                outFile.outputStream().buffered().use { out ->
                    while (!channel.isClosedForRead) {
                        val n = channel.readAvailable(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        received += n
                        if (contentLength > 0) {
                            val pct = (received * 100L / contentLength).toInt().coerceIn(0, 99)
                            _state.value = State.Downloading(info, pct)
                        }
                    }
                }
            }
            Log.i(TAG, "APK downloaded to ${outFile.absolutePath} (${outFile.length()} bytes)")
            _state.value = State.ReadyToInstall(outFile, info)
        }.onFailure { e ->
            Log.e(TAG, "APK download failed", e)
            runCatching { outFile.delete() }
            _state.value = State.Error("Download failed: ${e.message}")
        }
    }

    /**
     * Launch the system PackageInstaller for [apkFile].
     * The user already granted install-from-unknown-sources when they first
     * sideloaded AerioTV, so the system installer dialog appears immediately.
     */
    fun install(apkFile: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }.onFailure { e ->
            Log.e(TAG, "Could not launch installer", e)
            _state.value = State.Error("Could not launch installer: ${e.message}")
        }
    }

    /** Dismiss any active prompt and return to [State.Idle]. */
    fun dismiss() {
        _state.value = State.Idle
    }

    // -------------------------------------------------------------------------

    private suspend fun performCheck(showFeedback: Boolean) {
        _state.value = State.Checking
        runCatching { checker.checkForUpdate() }
            .onSuccess { info ->
                _state.value = if (info != null) {
                    State.UpdateAvailable(info)
                } else {
                    if (showFeedback) State.UpToDate else { State.Idle; return }
                }
            }
            .onFailure { e ->
                Log.w(TAG, "Update check failed", e)
                _state.value = if (showFeedback) {
                    State.Error(e.message ?: "Could not check for updates.")
                } else {
                    State.Idle
                }
            }
    }

    private fun downloadUrl() =
        "https://github.com/${BuildConfig.GITHUB_REPO}/releases/latest/download/${BuildConfig.GITHUB_APK_ASSET}"
}
