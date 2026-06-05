package com.aeriotv.android.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class DispatcharrDiscoveryResult(
    val baseUrl: String,
    val capabilities: DispatcharrPairingCapabilities,
)

@Singleton
class DispatcharrDiscoveryClient @Inject constructor(
    private val nsdLocator: NsdDispatcharrLocator,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 1_200
            connectTimeoutMillis = 450
            socketTimeoutMillis = 900
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun discover(): DispatcharrDiscoveryResult? = withContext(Dispatchers.IO) {
        val quick = quickCandidates()
            .map { async { probe(it) } }
            .awaitAll()
            .filterNotNull()
            .firstOrNull()
        if (quick != null) return@withContext quick

        // mDNS/NSD: ask the LAN who advertises Dispatcharr before brute-forcing
        // the subnet. Best-effort; an empty result just falls through.
        val mdns = nsdLocator.locate()
            .map { async { probe(it) } }
            .awaitAll()
            .filterNotNull()
            .firstOrNull()
        if (mdns != null) return@withContext mdns

        // Conservative subnet probe: port 9191 only, limited in batches, and
        // only RFC1918/site-local IPv4 addresses from active interfaces.
        for (batch in subnetCandidates().chunked(16)) {
            val found = batch
                .map { async { probe(it) } }
                .awaitAll()
                .filterNotNull()
                .firstOrNull()
            if (found != null) return@withContext found
        }
        null
    }

    suspend fun probe(baseUrl: String): DispatcharrDiscoveryResult? {
        val normalised = normaliseBaseUrl(baseUrl)
        return try {
            val response = client.get("$normalised/api/plugins/aeriotv/capabilities") {
                accept(ContentType.Application.Json)
            }
            if (!response.status.isSuccess()) return null
            val capabilities: DispatcharrPairingCapabilities = response.body()
            if (!capabilities.isAerioTvCompatible()) return null
            DispatcharrDiscoveryResult(
                baseUrl = normalised,
                capabilities = capabilities,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun quickCandidates(): List<String> = listOf(
        "http://tvserver.local:9191",
        "http://dispatcharr.local:9191",
        "http://dispatcharr:9191",
        "http://tvserver:9191",
    )

    private fun subnetCandidates(): List<String> {
        val hosts = mutableSetOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            for (address in networkInterface.inetAddresses.toList()) {
                val inet4 = address as? Inet4Address ?: continue
                if (!inet4.isSiteLocalAddress) continue
                val hostAddress = inet4.hostAddress ?: continue
                val octets = hostAddress.split(".")
                if (octets.size != 4) continue
                val prefix = "${octets[0]}.${octets[1]}.${octets[2]}"
                val ownHost = octets[3].toIntOrNull()
                for (host in 1..254) {
                    if (host == ownHost) continue
                    hosts += "http://$prefix.$host:9191"
                }
            }
        }
        return hosts.toList()
    }

    private fun normaliseBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }
}

private fun DispatcharrPairingCapabilities.isAerioTvCompatible(): Boolean =
    service == "dispatcharr-aeriotv" && pairingSupported
