package com.example.server

import android.os.Build
import com.example.domain.model.ApiActionResponse
import com.example.domain.model.ApiErrorResponse
import com.example.domain.model.AuthSessionResponse
import com.example.domain.model.CreateFolderRequest
import com.example.domain.model.DeleteFilesRequest
import com.example.domain.model.DeviceInfo
import com.example.domain.model.FileCategory
import com.example.domain.model.LoginRequest
import com.example.domain.model.MoveOrCopyRequest
import com.example.domain.model.PairRequest
import com.example.domain.model.RefreshRequest
import com.example.domain.model.RenameFileRequest
import com.example.domain.model.UploadCompleteRequest
import com.example.domain.model.UploadInitRequest
import com.example.domain.model.UploadInitResponse
import com.example.security.CryptoManager
import com.example.security.PathTraversalException
import com.example.security.RateLimiter
import com.example.security.SecureVaultLogger
import com.example.security.SelfSignedCertGenerator
import com.example.security.TlsServerIdentity
import com.example.storage.StorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket

data class HttpRequestData(
    val method: String,
    val path: String,
    val queryParams: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteAddress: String
)

sealed class HttpResponseData {
    abstract val statusCode: Int
    abstract val headers: Map<String, String>

    data class Bytes(
        override val statusCode: Int,
        val contentType: String = "application/json",
        val body: ByteArray = ByteArray(0),
        override val headers: Map<String, String> = emptyMap()
    ) : HttpResponseData()

    data class Stream(
        override val statusCode: Int,
        val contentType: String,
        val contentLength: Long,
        val inputStream: InputStream,
        val skipBytes: Long = 0L,
        val bytesToSend: Long = contentLength,
        override val headers: Map<String, String> = emptyMap()
    ) : HttpResponseData()
}

class HomeVaultHttpServer(
    private val deviceId: String,
    private val deviceNameProvider: () -> String,
    private val storageProviderFactory: () -> StorageProvider?,
    private val pinVerifier: suspend (String) -> Boolean,
    private val sessionManager: SessionManager = SessionManager(),
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val cryptoManager: CryptoManager = CryptoManager(),
    private val relayUrlProvider: () -> String = { "" },
    private val relayConnectedProvider: () -> Boolean = { false },
    private val onActivity: (eventType: String, clientName: String, detail: String) -> Unit = { _, _, _ -> }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    @Volatile
    var tlsIdentity: TlsServerIdentity? = null
        private set

    @Volatile
    var actualPort: Int = 0
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    private var startedAtMs: Long = 0L

    @Volatile
    var lastActivityMs: Long = System.currentTimeMillis()
        private set

    private val todayTransfers = AtomicInteger(0)

    fun start(port: Int = 8443, useTls: Boolean = true): Int {
        if (isRunning) return actualPort
        if (useTls) {
            if (tlsIdentity == null) {
                tlsIdentity = SelfSignedCertGenerator.generateTlsIdentity("HomeVault-$deviceId")
            }
            val sslSocket = tlsIdentity!!.sslContext.serverSocketFactory
                .createServerSocket(port) as SSLServerSocket
            sslSocket.enabledProtocols = sslSocket.supportedProtocols
                .filter { it.startsWith("TLSv1.2") || it.startsWith("TLSv1.3") }
                .ifEmpty { sslSocket.enabledProtocols.toList() }
                .toTypedArray()
            serverSocket = sslSocket
        } else {
            serverSocket = ServerSocket(port)
        }

        actualPort = serverSocket!!.localPort
        startedAtMs = System.currentTimeMillis()
        isRunning = true

        serverJob = scope.launch {
            SecureVaultLogger.d("Server", "Listening on port $actualPort (tls=$useTls)")
            while (isRunning) {
                val clientSocket = try {
                    serverSocket?.accept() ?: break
                } catch (_: Exception) {
                    if (!isRunning) break
                    continue
                }
                launch { handleSocket(clientSocket) }
            }
        }
        return actualPort
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        actualPort = 0
    }

    fun getDeviceInfo(): DeviceInfo {
        val storage = storageProviderFactory()
        val now = System.currentTimeMillis()
        return DeviceInfo(
            deviceId = deviceId,
            deviceName = deviceNameProvider(),
            modelName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            appVersion = "1.0.0",
            localIp = resolveLocalIpv4(),
            port = actualPort,
            tlsFingerprint = tlsIdentity?.sha256Fingerprint ?: "",
            storagePathLabel = storage?.storageLabel ?: "Not Configured",
            serverOnline = isRunning,
            relayConnected = relayConnectedProvider(),
            relayUrl = relayUrlProvider(),
            uptimeSeconds = if (isRunning) (now - startedAtMs) / 1000L else 0L,
            connectedClients = sessionManager.activeClientCount(),
            lastActivityEpochMs = lastActivityMs,
            todayTransfers = todayTransfers.get()
        )
    }

    private fun handleSocket(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 30_000
            val remoteIp = sock.inetAddress?.hostAddress ?: "unknown"
            var output: OutputStream? = null
            try {
                val input = BufferedInputStream(sock.getInputStream(), 16 * 1024)
                output = sock.getOutputStream()
                val request = parseHttpRequest(input, remoteIp) ?: return
                val response = runBlocking { dispatch(request) }
                writeHttpResponse(output, response)
            } catch (e: Exception) {
                SecureVaultLogger.w("Server", "Request handling error: ${e.message}")
                val out = output
                if (out != null) {
                    val err = jsonError(500, "INTERNAL_ERROR", "Server encountered an unexpected error.")
                    runCatching { writeHttpResponse(out, err) }
                }
            }
        }
    }

    suspend fun dispatch(req: HttpRequestData): HttpResponseData {
        val clientKey = req.remoteAddress
        if (!rateLimiter.allowRequest(clientKey)) {
            return jsonError(429, "RATE_LIMITED", "Too many requests. Please slow down.")
        }

        // Unauthenticated health probe (exposes NO files, tokens, or secrets)
        if (req.method == "GET" && req.path == "/api/health") {
            val payload = mapOf(
                "status" to "ok",
                "deviceId" to deviceId,
                "deviceName" to deviceNameProvider()
            )
            return jsonOk(payload)
        }

        // Auth endpoints
        when {
            req.method == "POST" && req.path == "/api/auth/pair" -> return handlePair(req)
            req.method == "POST" && req.path == "/api/auth/login" -> return handleLogin(req)
            req.method == "POST" && req.path == "/api/auth/refresh" -> return handleRefresh(req)
        }

        // All remaining endpoints require Bearer access token + anti-replay headers
        val authHeader = req.headers["authorization"]
        val token = authHeader?.removePrefix("Bearer ")?.trim()
        val session = sessionManager.validateAccessToken(token)
            ?: return jsonError(401, "UNAUTHORIZED", "Session expired or invalid. Please authenticate.")

        val nonce = req.headers["x-hv-nonce"]
        val ts = req.headers["x-hv-timestamp"]?.toLongOrNull()
        if (!sessionManager.verifyReplayProtection(nonce, ts)) {
            return jsonError(403, "REPLAY_REJECTED", "Request replay or expired timestamp rejected.")
        }

        lastActivityMs = System.currentTimeMillis()

        if (req.method == "POST" && req.path == "/api/auth/logout") {
            sessionManager.revokeAccessToken(session.accessToken)
            onActivity("AUTH", session.clientName, "Client logged out")
            return jsonOk(ApiActionResponse(true, "Logged out successfully"))
        }

        if (req.method == "GET" && req.path == "/api/device/info") {
            return jsonOk(getDeviceInfo())
        }

        val storage = storageProviderFactory()
            ?: return jsonError(503, "STORAGE_UNAVAILABLE", "Home storage folder is not configured.")

        return try {
            when {
                req.method == "GET" && req.path == "/api/storage/info" -> {
                    jsonOk(storage.getStorageInfo())
                }
                req.method == "GET" && req.path == "/api/files/list" -> {
                    val path = req.queryParams["path"] ?: ""
                    val offset = req.queryParams["offset"]?.toIntOrNull() ?: 0
                    val limit = req.queryParams["limit"]?.toIntOrNull() ?: 100
                    jsonOk(storage.listFiles(path, offset, limit))
                }
                req.method == "GET" && req.path == "/api/files/info" -> {
                    val path = req.queryParams["path"] ?: ""
                    val info = storage.getFileInfo(path)
                        ?: return jsonError(404, "NOT_FOUND", "File not found.")
                    jsonOk(info)
                }
                req.method == "GET" && req.path == "/api/files/thumbnail" -> {
                    val path = req.queryParams["path"] ?: ""
                    val bytes = storage.getThumbnailBytes(path)
                        ?: return jsonError(404, "NO_THUMBNAIL", "Thumbnail not available.")
                    HttpResponseData.Bytes(200, "image/jpeg", bytes)
                }
                req.method == "POST" && req.path == "/api/files/create-folder" -> {
                    val body = decodeBody<CreateFolderRequest>(req)
                        ?: return jsonError(400, "BAD_REQUEST", "Invalid folder request.")
                    val created = storage.createFolder(body.parentPath, body.folderName)
                    onActivity("FILE", session.clientName, "Created folder ${created.path}")
                    jsonOk(created)
                }
                req.method == "POST" && req.path == "/api/files/delete" -> {
                    val body = decodeBody<DeleteFilesRequest>(req)
                        ?: return jsonError(400, "BAD_REQUEST", "Invalid delete request.")
                    val count = storage.deletePaths(body.paths)
                    onActivity("FILE", session.clientName, "Deleted $count item(s)")
                    jsonOk(ApiActionResponse(true, "Deleted $count item(s)"))
                }
                req.method == "POST" && req.path == "/api/files/rename" -> {
                    val body = decodeBody<RenameFileRequest>(req)
                        ?: return jsonError(400, "BAD_REQUEST", "Invalid rename request.")
                    val renamed = storage.rename(body.path, body.newName)
                    onActivity("FILE", session.clientName, "Renamed to ${renamed.name}")
                    jsonOk(renamed)
                }
                req.method == "POST" && req.path == "/api/files/copy" -> {
                    val body = decodeBody<MoveOrCopyRequest>(req)
                        ?: return jsonError(400, "BAD_REQUEST", "Invalid copy request.")
                    val count = storage.copy(body.sourcePaths, body.destinationFolderPath)
                    onActivity("FILE", session.clientName, "Copied $count item(s)")
                    jsonOk(ApiActionResponse(true, "Copied $count item(s)"))
                }
                req.method == "POST" && req.path == "/api/files/move" -> {
                    val body = decodeBody<MoveOrCopyRequest>(req)
                        ?: return jsonError(400, "BAD_REQUEST", "Invalid move request.")
                    val count = storage.move(body.sourcePaths, body.destinationFolderPath)
                    onActivity("FILE", session.clientName, "Moved $count item(s)")
                    jsonOk(ApiActionResponse(true, "Moved $count item(s)"))
                }
                req.method == "GET" && req.path == "/api/search" -> {
                    val query = req.queryParams["q"] ?: ""
                    val cat = req.queryParams["type"]?.let {
                        runCatching { FileCategory.valueOf(it.uppercase()) }.getOrNull()
                    }
                    jsonOk(storage.search(query, cat))
                }
                req.method == "POST" && req.path == "/api/files/upload/init" -> {
                    val body = decodeBody<UploadInitRequest>(req)
                        ?: return jsonError(400, "BAD_REQUEST", "Invalid upload init request.")
                    val uploadId = UUID.randomUUID().toString()
                    val existing = storage.initUpload(
                        uploadId = uploadId,
                        targetFolderPath = body.targetFolderPath,
                        fileName = body.fileName,
                        totalBytes = body.totalBytes,
                        expectedSha256 = body.expectedSha256,
                        mimeType = body.mimeType
                    )
                    jsonOk(UploadInitResponse(uploadId, existing, body.chunkSize))
                }
                req.method == "PUT" && req.path == "/api/files/upload/chunk" -> {
                    val uploadId = req.queryParams["uploadId"]
                        ?: return jsonError(400, "BAD_REQUEST", "Missing uploadId.")
                    val offset = req.queryParams["offset"]?.toLongOrNull() ?: 0L
                    val totalReceived = storage.appendUploadChunk(uploadId, offset, req.body)
                    jsonOk(ApiActionResponse(true, "Chunk stored ($totalReceived bytes)"))
                }
                req.method == "POST" && req.path == "/api/files/upload/complete" -> {
                    val body = decodeBody<UploadCompleteRequest>(req)
                        ?: return jsonError(400, "BAD_REQUEST", "Invalid upload complete request.")
                    val item = storage.completeUpload(body.uploadId, body.expectedSha256)
                    todayTransfers.incrementAndGet()
                    onActivity("TRANSFER", session.clientName, "Uploaded ${item.name}")
                    jsonOk(item)
                }
                req.method == "DELETE" && req.path == "/api/files/upload/cancel" -> {
                    val uploadId = req.queryParams["uploadId"]
                        ?: return jsonError(400, "BAD_REQUEST", "Missing uploadId.")
                    storage.cancelUpload(uploadId)
                    jsonOk(ApiActionResponse(true, "Upload cancelled"))
                }
                req.method == "GET" && req.path == "/api/files/download" -> {
                    handleDownload(req, storage, session)
                }
                else -> jsonError(404, "ENDPOINT_NOT_FOUND", "Unknown endpoint: ${req.path}")
            }
        } catch (e: PathTraversalException) {
            onActivity("SECURITY", session.clientName, "Blocked path traversal attempt")
            jsonError(403, "PATH_FORBIDDEN", e.message ?: "Access outside storage root is forbidden.")
        } catch (e: SecurityException) {
            jsonError(422, "CHECKSUM_FAILED", e.message ?: "Integrity verification failed.")
        } catch (e: IllegalArgumentException) {
            jsonError(400, "BAD_REQUEST", e.message ?: "Invalid request parameters.")
        } catch (e: IllegalStateException) {
            jsonError(409, "CONFLICT", e.message ?: "Operation conflict.")
        }
    }

    private suspend fun handlePair(req: HttpRequestData): HttpResponseData {
        val lockKey = "auth:${req.remoteAddress}"
        if (rateLimiter.isAuthLocked(lockKey)) {
            return jsonError(429, "AUTH_LOCKED", "Too many failed attempts. Try again shortly.")
        }
        val body = decodeBody<PairRequest>(req)
            ?: return jsonError(400, "BAD_REQUEST", "Malformed pairing request.")
        if (!sessionManager.verifyReplayProtection(body.nonce, body.timestampMs)) {
            return jsonError(403, "REPLAY_REJECTED", "Expired or replayed pairing request.")
        }
        val pinValid = pinVerifier(body.pin)
        if (!pinValid || !sessionManager.verifyAndConsumePairingCode(body.pairingCode)) {
            rateLimiter.recordAuthFailure(lockKey)
            onActivity("SECURITY", body.clientName, "Failed pairing attempt")
            return jsonError(403, "INVALID_PAIRING", "Incorrect PIN or pairing code.")
        }
        rateLimiter.clearAuthFailures(lockKey)
        val session = sessionManager.issueSession(body.clientId, body.clientName)
        lastActivityMs = System.currentTimeMillis()
        onActivity("AUTH", body.clientName, "Paired successfully")
        return jsonOk(
            AuthSessionResponse(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                expiresAtEpochMs = session.accessExpiresAtMs,
                sessionSecretBase64 = session.sessionSecretBase64,
                deviceInfo = getDeviceInfo()
            )
        )
    }

    private suspend fun handleLogin(req: HttpRequestData): HttpResponseData {
        val lockKey = "auth:${req.remoteAddress}"
        if (rateLimiter.isAuthLocked(lockKey)) {
            return jsonError(429, "AUTH_LOCKED", "Too many failed attempts. Try again shortly.")
        }
        val body = decodeBody<LoginRequest>(req)
            ?: return jsonError(400, "BAD_REQUEST", "Malformed login request.")
        if (!sessionManager.verifyReplayProtection(body.nonce, body.timestampMs)) {
            return jsonError(403, "REPLAY_REJECTED", "Expired or replayed login request.")
        }
        if (body.deviceId != deviceId || !pinVerifier(body.pin)) {
            rateLimiter.recordAuthFailure(lockKey)
            onActivity("SECURITY", body.clientName, "Failed login attempt")
            return jsonError(403, "INVALID_CREDENTIALS", "Authentication rejected.")
        }
        rateLimiter.clearAuthFailures(lockKey)
        val session = sessionManager.issueSession(body.clientId, body.clientName)
        lastActivityMs = System.currentTimeMillis()
        onActivity("AUTH", body.clientName, "Authenticated session started")
        return jsonOk(
            AuthSessionResponse(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                expiresAtEpochMs = session.accessExpiresAtMs,
                sessionSecretBase64 = session.sessionSecretBase64,
                deviceInfo = getDeviceInfo()
            )
        )
    }

    private fun handleRefresh(req: HttpRequestData): HttpResponseData {
        val body = decodeBody<RefreshRequest>(req)
            ?: return jsonError(400, "BAD_REQUEST", "Malformed refresh request.")
        if (!sessionManager.verifyReplayProtection(body.nonce, body.timestampMs)) {
            return jsonError(403, "REPLAY_REJECTED", "Expired or replayed refresh request.")
        }
        val refreshed = sessionManager.refreshSession(body.refreshToken, body.clientId)
            ?: return jsonError(401, "INVALID_REFRESH", "Refresh token expired or revoked.")
        return jsonOk(
            AuthSessionResponse(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken,
                expiresAtEpochMs = refreshed.accessExpiresAtMs,
                sessionSecretBase64 = refreshed.sessionSecretBase64,
                deviceInfo = getDeviceInfo()
            )
        )
    }

    private suspend fun handleDownload(
        req: HttpRequestData,
        storage: StorageProvider,
        session: ActiveSession
    ): HttpResponseData {
        val path = req.queryParams["path"]
            ?: return jsonError(400, "BAD_REQUEST", "Missing file path.")
        val info = storage.getFileInfo(path)
            ?: return jsonError(404, "NOT_FOUND", "File does not exist.")
        if (info.isDirectory) {
            return jsonError(400, "IS_DIRECTORY", "Cannot download a directory directly.")
        }
        val stream = storage.openReadStream(path)
            ?: return jsonError(404, "NOT_FOUND", "Unable to open file stream.")

        val totalSize = info.sizeBytes
        val sha256 = storage.computeFileSha256(path) ?: ""
        val rangeHeader = req.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
            val parts = rangeSpec.split('-', limit = 2)
            val start = parts[0].toLongOrNull()?.coerceIn(0L, (totalSize - 1).coerceAtLeast(0L)) ?: 0L
            val end = parts.getOrNull(1)?.toLongOrNull()?.coerceIn(start, (totalSize - 1).coerceAtLeast(0L))
                ?: (totalSize - 1).coerceAtLeast(0L)
            val length = if (totalSize == 0L) 0L else (end - start + 1)
            if (start == 0L) {
                todayTransfers.incrementAndGet()
                onActivity("TRANSFER", session.clientName, "Downloading ${info.name}")
            }
            return HttpResponseData.Stream(
                statusCode = 206,
                contentType = info.mimeType,
                contentLength = totalSize,
                inputStream = stream,
                skipBytes = start,
                bytesToSend = length,
                headers = mapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes $start-$end/$totalSize",
                    "X-File-Sha256" to sha256
                )
            )
        }

        todayTransfers.incrementAndGet()
        onActivity("TRANSFER", session.clientName, "Downloading ${info.name}")
        return HttpResponseData.Stream(
            statusCode = 200,
            contentType = info.mimeType,
            contentLength = totalSize,
            inputStream = stream,
            skipBytes = 0L,
            bytesToSend = totalSize,
            headers = mapOf(
                "Accept-Ranges" to "bytes",
                "X-File-Sha256" to sha256
            )
        )
    }

    private inline fun <reified T> decodeBody(req: HttpRequestData): T? {
        return try {
            val raw = String(req.body, StandardCharsets.UTF_8)
            json.decodeFromString<T>(raw)
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <reified T> jsonOk(payload: T): HttpResponseData.Bytes {
        val text = json.encodeToString(payload)
        return HttpResponseData.Bytes(200, "application/json", text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun jsonError(code: Int, errorCode: String, message: String): HttpResponseData.Bytes {
        val text = json.encodeToString(ApiErrorResponse(errorCode, message))
        return HttpResponseData.Bytes(code, "application/json", text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun parseHttpRequest(input: BufferedInputStream, remoteIp: String): HttpRequestData? {
        val headerBytes = ByteArrayOutputStream()
        var prev = -1
        var matched = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            headerBytes.write(b)
            if ((matched == 0 || matched == 2) && b == '\r'.code) {
                matched++
            } else if ((matched == 1 || matched == 3) && b == '\n'.code) {
                matched++
                if (matched == 4) break
            } else {
                matched = if (b == '\r'.code) 1 else 0
            }
            if (headerBytes.size() > 32 * 1024) return null
            prev = b
        }

        val headerStr = String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1)
        val lines = headerStr.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val requestLine = lines.first().split(' ')
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val rawTarget = requestLine[1]
        val qIdx = rawTarget.indexOf('?')
        val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val queryString = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""
        val queryParams = parseQueryString(queryString)

        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val body = if (contentLength > 0) {
            val maxBody = 16 * 1024 * 1024
            if (contentLength > maxBody) return null
            val buf = ByteArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val r = input.read(buf, readTotal, contentLength - readTotal)
                if (r <= 0) break
                readTotal += r
            }
            if (readTotal == contentLength) buf else buf.copyOf(readTotal)
        } else {
            ByteArray(0)
        }

        return HttpRequestData(method, path, queryParams, headers, body, remoteIp)
    }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                map[k] = v
            }
        }
        return map
    }

    private fun writeHttpResponse(output: OutputStream, response: HttpResponseData) {
        val statusText = when (response.statusCode) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            422 -> "Unprocessable Entity"
            429 -> "Too Many Requests"
            503 -> "Service Unavailable"
            else -> "Internal Server Error"
        }

        val sb = StringBuilder()
        sb.append("HTTP/1.1 ${response.statusCode} $statusText\r\n")
        sb.append("Connection: close\r\n")
        when (response) {
            is HttpResponseData.Bytes -> {
                sb.append("Content-Type: ${response.contentType}\r\n")
                sb.append("Content-Length: ${response.body.size}\r\n")
                response.headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
                sb.append("\r\n")
                output.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
                output.write(response.body)
                output.flush()
            }
            is HttpResponseData.Stream -> {
                sb.append("Content-Type: ${response.contentType}\r\n")
                sb.append("Content-Length: ${response.bytesToSend}\r\n")
                response.headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
                sb.append("\r\n")
                output.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))

                response.inputStream.use { input ->
                    var skipped = 0L
                    while (skipped < response.skipBytes) {
                        val s = input.skip(response.skipBytes - skipped)
                        if (s <= 0) {
                            if (input.read() == -1) break
                            skipped += 1
                        } else {
                            skipped += s
                        }
                    }
                    val buf = ByteArray(64 * 1024)
                    var remaining = response.bytesToSend
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val read = input.read(buf, 0, toRead)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        remaining -= read
                    }
                    output.flush()
                }
            }
        }
    }

    companion object {
        fun resolveLocalIpv4(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress ?: continue
                        }
                    }
                }
                "127.0.0.1"
            } catch (_: Exception) {
                "127.0.0.1"
            }
        }
    }
}
