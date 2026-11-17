package com.example.server

import android.util.Base64
import com.example.security.SecureVaultLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

@Serializable
data class RelayEnvelope(
    val type: String, // "register", "registered", "tunnel_request", "tunnel_response", "ping", "pong"
    val requestId: String = "",
    val deviceId: String = "",
    val method: String = "",
    val path: String = "",
    val query: String = "",
    val headers: Map<String, String> = emptyMap(),
    val bodyBase64: String = "",
    val statusCode: Int = 200
)

/**
 * Outbound WebSocket tunnel connecting the Home Server phone to the self-hosted Relay server
 * without requiring manual router port forwarding. All relayed requests are routed directly
 * into [HomeVaultHttpServer.dispatch] so authentication, replay checks, and path sanitization
 * remain enforced end-to-end.
 */
class RelayTunnelClient(
    private val deviceId: String,
    private val httpServer: HomeVaultHttpServer
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val okHttp = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null
    private var activeWebSocket: WebSocket? = null

    @Volatile
    private var shouldStayConnected = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun start(relayBaseUrl: String) {
        val normalized = relayBaseUrl.trim()
        if (normalized.isEmpty()) {
            stop()
            return
        }
        shouldStayConnected = true
        connectJob?.cancel()
        connectJob = scope.launch {
            var backoffMs = 1500L
            while (shouldStayConnected) {
                val wsUrl = toTunnelWebSocketUrl(normalized)
                SecureVaultLogger.d("Relay", "Connecting outbound tunnel to $wsUrl")
                connectOnce(wsUrl)
                if (!shouldStayConnected) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stop() {
        shouldStayConnected = false
        connectJob?.cancel()
        connectJob = null
        activeWebSocket?.close(1000, "Server stopped")
        activeWebSocket = null
        _isConnected.value = false
    }

    private suspend fun connectOnce(wsUrl: String) {
        val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        val request = Request.Builder().url(wsUrl).build()
        val socket = okHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isConnected.value = true
                val reg = RelayEnvelope(type = "register", deviceId = deviceId)
                webSocket.send(json.encodeToString(reg))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    handleIncomingMessage(webSocket, text)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                deferred.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                SecureVaultLogger.w("Relay", "Tunnel disconnected: ${t.message}")
                _isConnected.value = false
                deferred.complete(Unit)
            }
        })
        activeWebSocket = socket
        deferred.await()
    }

    private suspend fun handleIncomingMessage(ws: WebSocket, raw: String) {
        val env = runCatching { json.decodeFromString<RelayEnvelope>(raw) }.getOrNull() ?: return
        if (env.type == "ping") {
            ws.send(json.encodeToString(RelayEnvelope(type = "pong", deviceId = deviceId)))
            return
        }
        if (env.type != "tunnel_request") return

        val bodyBytes = if (env.bodyBase64.isNotEmpty()) {
            Base64.decode(env.bodyBase64, Base64.DEFAULT)
        } else {
            ByteArray(0)
        }
        val queryMap = parseQuery(env.query)
        val req = HttpRequestData(
            method = env.method.uppercase(),
            path = env.path,
            queryParams = queryMap,
            headers = env.headers.mapKeys { it.key.lowercase() },
            body = bodyBytes,
            remoteAddress = "relay:${env.requestId}"
        )

        val response = httpServer.dispatch(req)
        val respBytes: ByteArray
        val respHeaders: MutableMap<String, String>
        when (response) {
            is HttpResponseData.Bytes -> {
                respBytes = response.body
                respHeaders = response.headers.toMutableMap().apply {
                    put("Content-Type", response.contentType)
                }
            }
            is HttpResponseData.Stream -> {
                respHeaders = response.headers.toMutableMap().apply {
                    put("Content-Type", response.contentType)
                }
                val out = ByteArrayOutputStream()
                response.inputStream.use { input ->
                    var skipped = 0L
                    while (skipped < response.skipBytes) {
                        val s = input.skip(response.skipBytes - skipped)
                        if (s <= 0) {
                            if (input.read() == -1) break
                            skipped += 1
                        } else skipped += s
                    }
                    val buf = ByteArray(32 * 1024)
                    var rem = response.bytesToSend
                    while (rem > 0) {
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        rem -= r
                    }
                }
                respBytes = out.toByteArray()
            }
        }

        val reply = RelayEnvelope(
            type = "tunnel_response",
            requestId = env.requestId,
            deviceId = deviceId,
            statusCode = response.statusCode,
            headers = respHeaders,
            bodyBase64 = Base64.encodeToString(respBytes, Base64.NO_WRAP)
        )
        ws.send(json.encodeToString(reply))
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        return q.trimStart('?').split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                URLDecoder.decode(part.substring(0, idx), "UTF-8") to
                    URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            } else null
        }.toMap()
    }

    private fun toTunnelWebSocketUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        val wsBase = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
            else -> "wss://$trimmed"
        }
        return "$wsBase/ws/tunnel"
    }
}
