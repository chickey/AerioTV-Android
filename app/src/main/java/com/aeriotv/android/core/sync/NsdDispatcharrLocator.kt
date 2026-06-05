package com.aeriotv.android.core.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves Dispatcharr base URLs advertised on the LAN via mDNS/NSD.
 *
 * AerioTV's preferred service type is `_aeriotv-dispatcharr._tcp`; we also look
 * at the generic `_dispatcharr._tcp` as a fallback. Discovery is best-effort:
 * any failure yields an empty list so the caller can fall back to hostname and
 * subnet probing. A Wi-Fi multicast lock is held for the discovery window so
 * Fire TV reliably receives the multicast responses.
 */
@Singleton
class NsdDispatcharrLocator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val serviceTypes = listOf(
        "_aeriotv-dispatcharr._tcp.",
        "_dispatcharr._tcp.",
    )

    /** Returns candidate base URLs (e.g. `http://192.168.0.10:9191`) to probe. */
    suspend fun locate(discoveryWindowMs: Long = 2_500L): List<String> {
        val nsdManager = runCatching {
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.getOrNull() ?: return emptyList()

        val multicastLock = runCatching {
            (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("aeriotv-nsd")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()

        return try {
            val urls = linkedSetOf<String>()
            for (type in serviceTypes) {
                urls += discoverType(nsdManager, type, discoveryWindowMs)
            }
            urls.toList()
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { multicastLock?.release() }
        }
    }

    private suspend fun discoverType(
        nsdManager: NsdManager,
        serviceType: String,
        windowMs: Long,
    ): List<String> {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                found.close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        val resolved = linkedSetOf<String>()
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {
            return emptyList()
        }
        try {
            // Collect and resolve services until the window elapses.
            withTimeoutOrNull(windowMs) {
                for (info in found) {
                    resolve(nsdManager, info)?.let { resolved += it }
                }
            }
        } finally {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            found.close()
        }
        return resolved.toList()
    }

    // NSD's classic resolveService is single-flight; serialise to avoid the
    // "listener already in use" failure on older Android.
    private val resolveMutex = Mutex()

    private suspend fun resolve(nsdManager: NsdManager, info: NsdServiceInfo): String? =
        resolveMutex.withLock {
            runCatching {
                withTimeout(1_200L) {
                    val deferred = CompletableDeferred<NsdServiceInfo?>()
                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            deferred.complete(null)
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            deferred.complete(serviceInfo)
                        }
                    }
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(info, resolveListener)
                    deferred.await()
                }
            }.getOrNull()?.let { toBaseUrl(it) }
        }

    private fun toBaseUrl(info: NsdServiceInfo): String? {
        @Suppress("DEPRECATION")
        val host = info.host ?: return null
        val address = when (host) {
            is Inet4Address -> host.hostAddress
            else -> host.hostAddress
        } ?: return null
        val port = info.port.takeIf { it > 0 } ?: 9191
        // IPv6 literals need bracketing; NSD usually hands back IPv4 on a LAN.
        val hostPart = if (address.contains(":")) "[$address]" else address
        return "http://$hostPart:$port"
    }
}
