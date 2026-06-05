package com.aeriotv.android.core.sync

import kotlinx.serialization.Serializable

@Serializable
data class DispatcharrPairingCapabilities(
    val service: String,
    val pluginVersion: String,
    val protocolVersion: Int = 1,
    val dispatcharrVersion: String? = null,
    val pairingSupported: Boolean,
    val syncSupported: Boolean,
    val syncRevisioned: Boolean = false,
    val pairingEndpoint: String,
)

@Serializable
data class DispatcharrPairingStartRequest(
    val deviceName: String,
    val deviceType: String = "fire_tv",
    val appVersion: String,
    val capabilities: List<String>,
)

@Serializable
data class DispatcharrPairingStartResponse(
    val pairingId: String,
    val code: String,
    val expiresAt: String,
    val pollIntervalSeconds: Int,
)

@Serializable
data class DispatcharrPairingStatusResponse(
    val status: String,
    val serverBaseUrl: String? = null,
    val deviceId: String? = null,
    val deviceToken: String? = null,
    val profileId: Int? = null,
    val revision: String? = null,
    val sync: DispatcharrSyncDocument? = null,
)

/**
 * Wire shape of `GET /api/plugins/aeriotv/sync` and the success/conflict body of
 * `PUT`. [revision] is an opaque optimistic-concurrency token the app echoes back
 * as [DispatcharrSyncPushRequest.baseRevision] on the next push.
 */
@Serializable
data class DispatcharrSyncEnvelope(
    val revision: String? = null,
    val sync: DispatcharrSyncDocument,
)

@Serializable
data class DispatcharrSyncPushRequest(
    val baseRevision: String? = null,
    val sync: DispatcharrSyncDocument,
)

@Serializable
data class DispatcharrSyncDocument(
    val schemaVersion: Int = 1,
    val updatedAt: String,
    val updatedByDeviceId: String? = null,
    val settings: Map<String, String> = emptyMap(),
    val favourites: List<String> = emptyList(),
    val hiddenGroups: List<String> = emptyList(),
    val recentChannels: List<String> = emptyList(),
    val watchProgress: Map<String, DispatcharrWatchProgress> = emptyMap(),
)

@Serializable
data class DispatcharrWatchProgress(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: String,
)

object MockDispatcharrPairingClient {
    fun capabilities() = DispatcharrPairingCapabilities(
        service = "dispatcharr-aeriotv",
        pluginVersion = "mock-1.0",
        dispatcharrVersion = "mock",
        pairingSupported = true,
        syncSupported = true,
        pairingEndpoint = "/api/plugins/aeriotv/pairing/start",
    )

    fun startPairing() = DispatcharrPairingStartResponse(
        pairingId = "mock-firetv-living-room",
        code = "4821",
        expiresAt = "mock",
        pollIntervalSeconds = 2,
    )

    fun approved() = DispatcharrPairingStatusResponse(
        status = "approved",
        serverBaseUrl = "http://tvserver.local:9191",
        deviceId = "firetv-living-room",
        deviceToken = "mock_device_token",
        profileId = 1,
        sync = DispatcharrSyncDocument(
            updatedAt = "mock",
            updatedByDeviceId = "firetv-living-room",
        ),
    )
}
