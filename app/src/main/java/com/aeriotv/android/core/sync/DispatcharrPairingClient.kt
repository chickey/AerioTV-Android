package com.aeriotv.android.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class DispatcharrPairingClient @Inject constructor() {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 10_000
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun startPairing(
        baseUrl: String,
        request: DispatcharrPairingStartRequest,
    ): DispatcharrPairingStartResponse {
        val response = client.post("${baseUrl.trimEnd('/')}/api/plugins/aeriotv/pairing/start") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header("User-Agent", "AerioTV-Android")
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Pairing start failed: HTTP ${response.status.value}")
        }
        return response.body()
    }

    suspend fun pairingStatus(
        baseUrl: String,
        pairingId: String,
    ): DispatcharrPairingStatusResponse {
        val response = client.get("${baseUrl.trimEnd('/')}/api/plugins/aeriotv/pairing/$pairingId") {
            accept(ContentType.Application.Json)
            header("User-Agent", "AerioTV-Android")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Pairing status failed: HTTP ${response.status.value}")
        }
        return response.body()
    }
}
