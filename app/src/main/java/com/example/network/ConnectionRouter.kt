package com.example.network

import com.example.domain.model.ConnectionRoute
import com.example.domain.model.PairedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlin.math.min

data class RouteProbeResult(
    val route: ConnectionRoute,
    val baseUrl: String,
    val usePinnedTls: Boolean,
    val latencyMs: Long
)

/**
 * Resolves the optimal secure communication path automatically in priority order:
 * 1. LAN (mDNS/NSD or saved local IP over pinned HTTPS)
 * 2. Direct (user-configured DDNS/VPN/WireGuard URL)
 * 3. Relay (outbound WebSocket tunnel via modular self-hosted Relay server)
 */
class ConnectionRouter {

    suspend fun resolveBestRoute(
        server: PairedServer,
        discoveredLanHost: String? = null,
        discoveredLanPort: Int? = null
    ): RouteProbeResult? = withContext(Dispatchers.IO) {
        val lanHost = discoveredLanHost ?: server.localIp
        val lanPort = discoveredLanPort ?: server.port

        if (lanHost.isNotBlank() && lanPort > 0) {
            val lanUrl = "https://$lanHost:$lanPort"
            val rtt = probeHealth(lanUrl, server.tlsFingerprint, pinned = true)
            if (rtt != null) {
                return@withContext RouteProbeResult(ConnectionRoute.LAN, lanUrl, true, rtt)
            }
        }

        if (server.directUrl.isNotBlank()) {
            val direct = server.directUrl.trimEnd('/')
            val pinned = server.tlsFingerprint.isNotBlank()
            val rtt = probeHealth(direct, server.tlsFingerprint, pinned = pinned)
            if (rtt != null) {
                return@withContext RouteProbeResult(ConnectionRoute.DIRECT, direct, pinned, rtt)
            }
        }

        if (server.relayUrl.isNotBlank()) {
            val relayBase = "${server.relayUrl.trimEnd('/')}/relay/${server.deviceId}"
            val rtt = probeHealth(relayBase, "", pinned = false)
            if (rtt != null) {
                return@withContext RouteProbeResult(ConnectionRoute.RELAY, relayBase, false, rtt)
            }
        }

        null
    }

    private fun probeHealth(baseUrl: String, fingerprint: String, pinned: Boolean): Long? {
        return try {
            val client = if (pinned) {
                HomeVaultTlsClient.buildPinnedClient(fingerprint, connectTimeoutSec = 3, readTimeoutSec = 4)
            } else {
                HomeVaultTlsClient.buildStandardClient(connectTimeoutSec = 5, readTimeoutSec = 6)
            }
            val start = System.currentTimeMillis()
            val req = Request.Builder().url("$baseUrl/api/health").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    (System.currentTimeMillis() - start).coerceAtLeast(1L)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun <T> withExponentialBackoff(
        maxAttempts: Int = 5,
        initialDelayMs: Long = 1200L,
        maxDelayMs: Long = 24_000L,
        block: suspend (attempt: Int) -> T
    ): T {
        var delayMs = initialDelayMs
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                return block(attempt)
            } catch (t: Throwable) {
                lastError = t
                if (attempt == maxAttempts) break
                delay(delayMs)
                delayMs = min(delayMs * 2, maxDelayMs)
            }
        }
        throw lastError ?: IllegalStateException("Operation failed after $maxAttempts attempts.")
    }
}
