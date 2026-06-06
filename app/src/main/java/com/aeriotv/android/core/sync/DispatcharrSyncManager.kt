package com.aeriotv.android.core.sync

import android.os.Build
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.FavoriteChannelEntity
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import com.aeriotv.android.core.preferences.AppPreferences
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

@Singleton
class DispatcharrSyncManager @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val favoriteDao: FavoriteChannelDao,
    private val watchProgressDao: WatchProgressDao,
    private val appPreferences: AppPreferences,
    private val secureTokenStore: SecureTokenStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 15_000
        }
        install(ContentNegotiation) { json(json) }
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Last revision seen from the server, echoed back on push for conflict detection. */
    @Volatile
    private var lastRevision: String? = null

    suspend fun pull(): Boolean = runCatching {
        val (baseUrl, token) = pairedDispatcharr()
        _status.value = Status.Working("Pulling from Dispatcharr")
        val remote = getRemote(baseUrl, token)
        lastRevision = remote.revision
        apply(remote.sync)
        appPreferences.setSyncLastPullAt(System.currentTimeMillis())
        _status.value = Status.Success("Pulled Dispatcharr sync data")
        true
    }.getOrElse {
        if (it is TokenRevokedException) secureTokenStore.clearDeviceToken()
        _status.value = Status.Error(it.message ?: it::class.simpleName.orEmpty())
        false
    }

    suspend fun push(): Boolean = runCatching {
        val (baseUrl, token) = pairedDispatcharr()
        _status.value = Status.Working("Pushing to Dispatcharr")
        putRemote(baseUrl, token)
        appPreferences.setSyncLastPushAt(System.currentTimeMillis())
        _status.value = Status.Success("Pushed Dispatcharr sync data")
        true
    }.getOrElse {
        if (it is TokenRevokedException) secureTokenStore.clearDeviceToken()
        _status.value = Status.Error(it.message ?: it::class.simpleName.orEmpty())
        false
    }

    suspend fun syncNow(): Boolean {
        val pulled = pull()
        val pushed = if (pulled) push() else false
        return pulled && pushed
    }

    private suspend fun pairedDispatcharr(): Pair<String, String> {
        val playlist = playlistDao.firstActive()
            ?: throw IllegalStateException("No active Dispatcharr playlist is configured.")
        val sourceType = SourceType.entries.firstOrNull { it.name == playlist.sourceType }
        if (sourceType != SourceType.DispatcharrApiKey && sourceType != SourceType.DispatcharrUserPass) {
            throw IllegalStateException("The active playlist is not Dispatcharr-backed.")
        }
        // Prefer the encrypted device token; fall back to the playlist's API key
        // for manual setups that never went through pairing.
        val token = secureTokenStore.deviceToken()
            ?: playlist.apiKey?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("The active Dispatcharr playlist has no sync token/API key.")
        return playlist.urlString.trimEnd('/') to token
    }

    private suspend fun getRemote(baseUrl: String, token: String): DispatcharrSyncEnvelope {
        val response = client.get("$baseUrl/api/plugins/aeriotv/sync") {
            accept(ContentType.Application.Json)
            applyAuth(token)
        }
        if (response.status.value == 401) {
            throw TokenRevokedException()
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Dispatcharr sync pull failed: HTTP ${response.status.value}")
        }
        return response.body()
    }

    /**
     * Pushes the local document with optimistic concurrency. On a [409] conflict
     * the server returns its current state; we merge it locally and retry once
     * against the fresh revision before giving up.
     */
    private suspend fun putRemote(baseUrl: String, token: String) {
        repeat(2) { attempt ->
            val response = client.put("$baseUrl/api/plugins/aeriotv/sync") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                applyAuth(token)
                setBody(DispatcharrSyncPushRequest(baseRevision = lastRevision, sync = buildLocalDocument()))
            }
            when {
                response.status.value == 401 -> throw TokenRevokedException()
                response.status.value == 409 -> {
                    // Stale write: adopt the server's state, then retry.
                    val current = response.body<DispatcharrSyncEnvelope>()
                    lastRevision = current.revision
                    apply(current.sync)
                    if (attempt == 1) {
                        throw IllegalStateException("Dispatcharr sync push conflict could not be resolved.")
                    }
                }
                response.status.isSuccess() -> {
                    lastRevision = response.body<DispatcharrSyncEnvelope>().revision
                    return
                }
                else -> throw IllegalStateException("Dispatcharr sync push failed: HTTP ${response.status.value}")
            }
        }
    }

    private suspend fun buildLocalDocument(): DispatcharrSyncDocument {
        val progress = watchProgressDao.allOnce().associate { row ->
            row.videoId to DispatcharrWatchProgress(
                positionMs = row.positionMs,
                durationMs = row.durationMs,
                updatedAt = isoUtc(row.updatedAt),
            )
        }
        return DispatcharrSyncDocument(
            updatedAt = isoUtc(System.currentTimeMillis()),
            updatedByDeviceId = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            settings = appPreferences.snapshotSyncablePreferences(),
            favourites = favoriteDao.allOnce().map { it.channelId },
            hiddenGroups = appPreferences.hiddenGroupsOnce().toList(),
            recentChannels = appPreferences.recentChannelIdsOnce(),
            watchProgress = progress,
        )
    }

    private suspend fun apply(document: DispatcharrSyncDocument) {
        appPreferences.applySyncedPreferences(document.settings)
        if (document.hiddenGroups.isNotEmpty()) {
            appPreferences.setHiddenGroups(document.hiddenGroups.toSet())
        }
        if (document.recentChannels.isNotEmpty()) {
            appPreferences.setRecentChannelIds(document.recentChannels)
        }
        document.favourites.forEachIndexed { index, channelId ->
            favoriteDao.upsert(
                FavoriteChannelEntity(
                    channelId = channelId,
                    channelName = channelId,
                    displayOrder = index.toLong(),
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
        document.watchProgress.forEach { (videoId, remote) ->
            val updatedAt = parseIsoUtc(remote.updatedAt)
                .getOrDefault(System.currentTimeMillis())
            val local = watchProgressDao.getOnce(videoId)
            if (local == null || updatedAt > local.updatedAt) {
                watchProgressDao.upsert(
                    (local ?: WatchProgressEntity(
                        videoId = videoId,
                        title = videoId,
                        posterUrl = null,
                        positionMs = remote.positionMs,
                        durationMs = remote.durationMs,
                        updatedAt = updatedAt,
                    )).copy(
                        positionMs = remote.positionMs,
                        durationMs = remote.durationMs,
                        updatedAt = updatedAt,
                    ),
                )
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth(token: String) {
        header("Authorization", "Bearer $token")
        header("X-API-Key", token)
    }

    private fun isoUtc(millis: Long): String =
        ISO_UTC.get()!!.format(Date(millis))

    private fun parseIsoUtc(value: String): Result<Long> = runCatching {
        ISO_UTC.get()!!.parse(value)?.time ?: throw IllegalArgumentException("Invalid timestamp")
    }

    sealed interface Status {
        data object Idle : Status
        data class Working(val message: String) : Status
        data class Success(val message: String) : Status
        data class Error(val message: String) : Status
    }

    /** Thrown when the plugin rejects the device token (revoked or unknown). */
    class TokenRevokedException :
        IllegalStateException("This device's Dispatcharr access was revoked. Pair again to reconnect.")

    companion object {
        private val ISO_UTC = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }
}
