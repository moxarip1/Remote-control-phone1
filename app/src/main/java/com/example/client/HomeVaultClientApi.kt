package com.example.client

import com.example.database.PairedServerDao
import com.example.database.PairedServerEntity
import com.example.domain.model.ApiActionResponse
import com.example.domain.model.ApiErrorResponse
import com.example.domain.model.AuthSessionResponse
import com.example.domain.model.ConnectionRoute
import com.example.domain.model.CreateFolderRequest
import com.example.domain.model.DeleteFilesRequest
import com.example.domain.model.DeviceInfo
import com.example.domain.model.FileCategory
import com.example.domain.model.FileListResponse
import com.example.domain.model.LoginRequest
import com.example.domain.model.MoveOrCopyRequest
import com.example.domain.model.PairRequest
import com.example.domain.model.PairingPayload
import com.example.domain.model.RefreshRequest
import com.example.domain.model.RemoteFileItem
import com.example.domain.model.RenameFileRequest
import com.example.domain.model.StorageInfo
import com.example.domain.model.UploadCompleteRequest
import com.example.domain.model.UploadInitRequest
import com.example.domain.model.UploadInitResponse
import com.example.network.ConnectionRouter
import com.example.network.HomeVaultTlsClient
import com.example.security.CryptoManager
import com.example.security.SecureVaultLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder

class VaultException(
    val userMessage: String,
    val httpCode: Int = 0,
    cause: Throwable? = null
) : Exception(userMessage, cause)

data class ResolvedEndpoint(
    val deviceId: String,
    val baseUrl: String,
    val route: ConnectionRoute,
    val client: OkHttpClient,
    val latencyMs: Long
)

class HomeVaultClientApi(
    private val pairedServerDao: PairedServerDao,
    private val cryptoManager: CryptoManager,
    private val router: ConnectionRouter = ConnectionRouter()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val refreshMutex = Mutex()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val binaryMediaType = "application/octet-stream".toMediaType()

    suspend fun pairWithServer(
        payload: PairingPayload,
        pin: String,
        clientId: String,
        clientName: String
    ): PairedServerEntity = withContext(Dispatchers.IO) {
        val candidateUrl = when {
            payload.localIp.isNotBlank() && payload.port > 0 -> "https://${payload.localIp}:${payload.port}"
            payload.directUrl.isNotBlank() -> payload.directUrl.trimEnd('/')
            payload.relayUrl.isNotBlank() -> "${payload.relayUrl.trimEnd('/')}/relay/${payload.deviceId}"
            else -> throw VaultException("No reachable server address in pairing details.")
        }

        val usePinned = candidateUrl.startsWith("https://") &&
            (payload.localIp.isNotBlank() || payload.directUrl.isNotBlank()) &&
            payload.tlsFingerprint.isNotBlank()

        val client = if (usePinned) {
            HomeVaultTlsClient.buildPinnedClient(payload.tlsFingerprint)
        } else {
            HomeVaultTlsClient.buildStandardClient()
        }

        val reqBody = PairRequest(
            pairingCode = payload.pairingCode.trim(),
            pin = pin,
            clientId = clientId,
            clientName = clientName,
            nonce = cryptoManager.generateNonce(),
            timestampMs = System.currentTimeMillis()
        )
        val request = Request.Builder()
            .url("$candidateUrl/api/auth/pair")
            .post(json.encodeToString(reqBody).toRequestBody(jsonMediaType))
            .build()

        val sessionResp = executeJson<AuthSessionResponse>(client, request)
        val entity = PairedServerEntity(
            deviceId = sessionResp.deviceInfo.deviceId,
            deviceName = payload.deviceName.ifBlank { sessionResp.deviceInfo.deviceName },
            localIp = payload.localIp.ifBlank { sessionResp.deviceInfo.localIp },
            port = if (payload.port > 0) payload.port else sessionResp.deviceInfo.port,
            directUrl = payload.directUrl,
            relayUrl = payload.relayUrl.ifBlank { sessionResp.deviceInfo.relayUrl },
            tlsFingerprint = payload.tlsFingerprint.ifBlank { sessionResp.deviceInfo.tlsFingerprint },
            encryptedAccessToken = cryptoManager.encryptString(sessionResp.accessToken),
            encryptedRefreshToken = cryptoManager.encryptString(sessionResp.refreshToken),
            encryptedSessionSecret = cryptoManager.encryptString(sessionResp.sessionSecretBase64),
            tokenExpiresAtEpochMs = sessionResp.expiresAtEpochMs,
            lastRoute = if (candidateUrl.contains("/relay/")) ConnectionRoute.RELAY.name else ConnectionRoute.LAN.name,
            isOnline = true,
            lastConnectedEpochMs = System.currentTimeMillis(),
            usedBytes = 0L,
            totalBytes = 0L,
            fileCount = 0
        )
        pairedServerDao.upsert(entity)
        entity
    }

    suspend fun loginWithPin(
        deviceId: String,
        pin: String,
        clientId: String,
        clientName: String
    ): PairedServerEntity = withContext(Dispatchers.IO) {
        val server = pairedServerDao.getById(deviceId)
            ?: throw VaultException("Device not found. Please pair again.")
        val endpoint = resolveEndpoint(server)
        val reqBody = LoginRequest(
            deviceId = deviceId,
            clientId = clientId,
            clientName = clientName,
            pin = pin,
            nonce = cryptoManager.generateNonce(),
            timestampMs = System.currentTimeMillis()
        )
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/api/auth/login")
            .post(json.encodeToString(reqBody).toRequestBody(jsonMediaType))
            .build()

        val sessionResp = executeJson<AuthSessionResponse>(endpoint.client, request)
        pairedServerDao.updateTokens(
            deviceId = deviceId,
            accessToken = cryptoManager.encryptString(sessionResp.accessToken),
            refreshToken = cryptoManager.encryptString(sessionResp.refreshToken),
            sessionSecret = cryptoManager.encryptString(sessionResp.sessionSecretBase64),
            expiresAtMs = sessionResp.expiresAtEpochMs
        )
        pairedServerDao.getById(deviceId)!!
    }

    suspend fun resolveEndpoint(
        serverEntity: PairedServerEntity,
        lanHostOverride: String? = null,
        lanPortOverride: Int? = null
    ): ResolvedEndpoint {
        val domain = serverEntity.toDomain()
        val probed = router.resolveBestRoute(domain, lanHostOverride, lanPortOverride)
            ?: throw VaultException("Connection lost. Retrying...")
        val client = if (probed.usePinnedTls) {
            HomeVaultTlsClient.buildPinnedClient(serverEntity.tlsFingerprint)
        } else {
            HomeVaultTlsClient.buildStandardClient()
        }
        return ResolvedEndpoint(
            deviceId = serverEntity.deviceId,
            baseUrl = probed.baseUrl,
            route = probed.route,
            client = client,
            latencyMs = probed.latencyMs
        )
    }

    suspend fun fetchDeviceInfo(deviceId: String, clientId: String): Pair<DeviceInfo, ResolvedEndpoint> {
        val endpoint = getEndpointOrThrow(deviceId)
        val info = authedGet<DeviceInfo>(deviceId, clientId, endpoint, "/api/device/info")
        return info to endpoint
    }

    suspend fun fetchStorageInfo(deviceId: String, clientId: String): StorageInfo {
        val endpoint = getEndpointOrThrow(deviceId)
        val info = authedGet<StorageInfo>(deviceId, clientId, endpoint, "/api/storage/info")
        pairedServerDao.updateStatus(
            deviceId = deviceId,
            online = true,
            route = endpoint.route.name,
            lastConnectedMs = System.currentTimeMillis(),
            usedBytes = info.usedBytes,
            totalBytes = info.totalBytes,
            fileCount = info.fileCount
        )
        return info
    }

    suspend fun listFiles(
        deviceId: String,
        clientId: String,
        path: String,
        offset: Int = 0,
        limit: Int = 100
    ): FileListResponse {
        val endpoint = getEndpointOrThrow(deviceId)
        val encoded = URLEncoder.encode(path, "UTF-8")
        return authedGet(
            deviceId,
            clientId,
            endpoint,
            "/api/files/list?path=$encoded&offset=$offset&limit=$limit"
        )
    }

    suspend fun createFolder(
        deviceId: String,
        clientId: String,
        parentPath: String,
        folderName: String
    ): RemoteFileItem {
        val endpoint = getEndpointOrThrow(deviceId)
        return authedPost(
            deviceId,
            clientId,
            endpoint,
            "/api/files/create-folder",
            CreateFolderRequest(parentPath, folderName)
        )
    }

    suspend fun deleteFiles(deviceId: String, clientId: String, paths: List<String>): ApiActionResponse {
        val endpoint = getEndpointOrThrow(deviceId)
        return authedPost(deviceId, clientId, endpoint, "/api/files/delete", DeleteFilesRequest(paths))
    }

    suspend fun renameFile(
        deviceId: String,
        clientId: String,
        path: String,
        newName: String
    ): RemoteFileItem {
        val endpoint = getEndpointOrThrow(deviceId)
        return authedPost(deviceId, clientId, endpoint, "/api/files/rename", RenameFileRequest(path, newName))
    }

    suspend fun copyFiles(
        deviceId: String,
        clientId: String,
        sourcePaths: List<String>,
        destinationFolderPath: String
    ): ApiActionResponse {
        val endpoint = getEndpointOrThrow(deviceId)
        return authedPost(
            deviceId,
            clientId,
            endpoint,
            "/api/files/copy",
            MoveOrCopyRequest(sourcePaths, destinationFolderPath)
        )
    }

    suspend fun moveFiles(
        deviceId: String,
        clientId: String,
        sourcePaths: List<String>,
        destinationFolderPath: String
    ): ApiActionResponse {
        val endpoint = getEndpointOrThrow(deviceId)
        return authedPost(
            deviceId,
            clientId,
            endpoint,
            "/api/files/move",
            MoveOrCopyRequest(sourcePaths, destinationFolderPath)
        )
    }

    suspend fun searchFiles(
        deviceId: String,
        clientId: String,
        query: String,
        category: FileCategory? = null
    ): List<RemoteFileItem> {
        val endpoint = getEndpointOrThrow(deviceId)
        val qEnc = URLEncoder.encode(query, "UTF-8")
        val typeParam = category?.let { "&type=${it.name}" } ?: ""
        return authedGet(deviceId, clientId, endpoint, "/api/search?q=$qEnc$typeParam")
    }

    suspend fun fetchThumbnailBytes(deviceId: String, clientId: String, path: String): ByteArray? {
        return try {
            val endpoint = getEndpointOrThrow(deviceId)
            val encoded = URLEncoder.encode(path, "UTF-8")
            executeAuthedRaw(deviceId, clientId, endpoint) { accessToken ->
                Request.Builder()
                    .url("${endpoint.baseUrl}/api/files/thumbnail?path=$encoded")
                    .header("Authorization", "Bearer $accessToken")
                    .header("X-HV-Nonce", cryptoManager.generateNonce())
                    .header("X-HV-Timestamp", System.currentTimeMillis().toString())
                    .get()
                    .build()
            }.use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun initUpload(
        deviceId: String,
        clientId: String,
        request: UploadInitRequest
    ): UploadInitResponse {
        val endpoint = getEndpointOrThrow(deviceId)
        return authedPost(deviceId, clientId, endpoint, "/api/files/upload/init", request)
    }

    suspend fun uploadChunk(
        deviceId: String,
        clientId: String,
        uploadId: String,
        offsetBytes: Long,
        chunkBytes: ByteArray
    ) {
        val endpoint = getEndpointOrThrow(deviceId)
        val uEnc = URLEncoder.encode(uploadId, "UTF-8")
        executeAuthedRaw(deviceId, clientId, endpoint) { token ->
            Request.Builder()
                .url("${endpoint.baseUrl}/api/files/upload/chunk?uploadId=$uEnc&offset=$offsetBytes")
                .header("Authorization", "Bearer $token")
                .header("X-HV-Nonce", cryptoManager.generateNonce())
                .header("X-HV-Timestamp", System.currentTimeMillis().toString())
                .put(chunkBytes.toRequestBody(binaryMediaType))
                .build()
        }.use { resp ->
            if (!resp.isSuccessful) throw mapHttpError(resp)
        }
    }

    suspend fun completeUpload(
        deviceId: String,
        clientId: String,
        uploadId: String,
        expectedSha256: String
    ): RemoteFileItem {
        val endpoint = getEndpointOrThrow(deviceId)
        return authedPost(
            deviceId,
            clientId,
            endpoint,
            "/api/files/upload/complete",
            UploadCompleteRequest(uploadId, expectedSha256)
        )
    }

    suspend fun openDownloadResponse(
        deviceId: String,
        clientId: String,
        remotePath: String,
        startByteOffset: Long = 0L
    ): Response {
        val endpoint = getEndpointOrThrow(deviceId)
        val pEnc = URLEncoder.encode(remotePath, "UTF-8")
        val resp = executeAuthedRaw(deviceId, clientId, endpoint) { token ->
            val builder = Request.Builder()
                .url("${endpoint.baseUrl}/api/files/download?path=$pEnc")
                .header("Authorization", "Bearer $token")
                .header("X-HV-Nonce", cryptoManager.generateNonce())
                .header("X-HV-Timestamp", System.currentTimeMillis().toString())
                .get()
            if (startByteOffset > 0L) {
                builder.header("Range", "bytes=$startByteOffset-")
            }
            builder.build()
        }
        if (!resp.isSuccessful) {
            val err = mapHttpError(resp)
            resp.close()
            throw err
        }
        return resp
    }

    suspend fun buildAuthedStreamSpec(
        deviceId: String,
        remotePath: String
    ): Triple<OkHttpClient, String, Map<String, String>> {
        val entity = pairedServerDao.getById(deviceId)
            ?: throw VaultException("Device not found.")
        val endpoint = resolveEndpoint(entity)
        val token = cryptoManager.decryptString(entity.encryptedAccessToken)
        val pEnc = URLEncoder.encode(remotePath, "UTF-8")
        val url = "${endpoint.baseUrl}/api/files/download?path=$pEnc"
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "X-HV-Nonce" to cryptoManager.generateNonce(),
            "X-HV-Timestamp" to System.currentTimeMillis().toString()
        )
        return Triple(endpoint.client, url, headers)
    }

    suspend fun logout(deviceId: String, clientId: String) {
        runCatching {
            val endpoint = getEndpointOrThrow(deviceId)
            authedPost<Map<String, String>, ApiActionResponse>(
                deviceId,
                clientId,
                endpoint,
                "/api/auth/logout",
                emptyMap()
            )
        }
    }

    private suspend fun getEndpointOrThrow(deviceId: String): ResolvedEndpoint {
        val entity = pairedServerDao.getById(deviceId)
            ?: throw VaultException("Access denied. Please reconnect the Home Storage.")
        return resolveEndpoint(entity)
    }

    private suspend inline fun <reified R> authedGet(
        deviceId: String,
        clientId: String,
        endpoint: ResolvedEndpoint,
        pathAndQuery: String
    ): R {
        executeAuthedRaw(deviceId, clientId, endpoint) { accessToken ->
            Request.Builder()
                .url("${endpoint.baseUrl}$pathAndQuery")
                .header("Authorization", "Bearer $accessToken")
                .header("X-HV-Nonce", cryptoManager.generateNonce())
                .header("X-HV-Timestamp", System.currentTimeMillis().toString())
                .get()
                .build()
        }.use { resp ->
            return parseOrThrow(resp)
        }
    }

    private suspend inline fun <reified B, reified R> authedPost(
        deviceId: String,
        clientId: String,
        endpoint: ResolvedEndpoint,
        path: String,
        bodyObj: B
    ): R {
        val rawBody = json.encodeToString(bodyObj)
        executeAuthedRaw(deviceId, clientId, endpoint) { accessToken ->
            Request.Builder()
                .url("${endpoint.baseUrl}$path")
                .header("Authorization", "Bearer $accessToken")
                .header("X-HV-Nonce", cryptoManager.generateNonce())
                .header("X-HV-Timestamp", System.currentTimeMillis().toString())
                .post(rawBody.toRequestBody(jsonMediaType))
                .build()
        }.use { resp ->
            return parseOrThrow(resp)
        }
    }

    private suspend fun executeAuthedRaw(
        deviceId: String,
        clientId: String,
        endpoint: ResolvedEndpoint,
        requestBuilder: (accessToken: String) -> Request
    ): Response = withContext(Dispatchers.IO) {
        val entity = pairedServerDao.getById(deviceId)
            ?: throw VaultException("Access denied. Please reconnect the Home Storage.")
        var token = cryptoManager.decryptString(entity.encryptedAccessToken)
        if (token.isEmpty()) {
            token = refreshAccessToken(deviceId, clientId, endpoint)
        }

        val firstResp = try {
            endpoint.client.newCall(requestBuilder(token)).execute()
        } catch (e: IOException) {
            throw VaultException("Connection lost. Retrying...", cause = e)
        }

        if (firstResp.code == 401) {
            firstResp.close()
            val newToken = refreshAccessToken(deviceId, clientId, endpoint)
            return@withContext try {
                endpoint.client.newCall(requestBuilder(newToken)).execute()
            } catch (e: IOException) {
                throw VaultException("Connection lost. Retrying...", cause = e)
            }
        }
        firstResp
    }

    private suspend fun refreshAccessToken(
        deviceId: String,
        clientId: String,
        endpoint: ResolvedEndpoint
    ): String = refreshMutex.withLock {
        val entity = pairedServerDao.getById(deviceId)
            ?: throw VaultException("Access denied. Please reconnect the Home Storage.", 401)
        val refreshToken = cryptoManager.decryptString(entity.encryptedRefreshToken)
        if (refreshToken.isEmpty()) {
            throw VaultException("Access denied. Please reconnect the Home Storage.", 401)
        }
        val reqBody = RefreshRequest(
            refreshToken = refreshToken,
            clientId = clientId,
            nonce = cryptoManager.generateNonce(),
            timestampMs = System.currentTimeMillis()
        )
        val req = Request.Builder()
            .url("${endpoint.baseUrl}/api/auth/refresh")
            .post(json.encodeToString(reqBody).toRequestBody(jsonMediaType))
            .build()
        val sessionResp = executeJson<AuthSessionResponse>(endpoint.client, req)
        pairedServerDao.updateTokens(
            deviceId = deviceId,
            accessToken = cryptoManager.encryptString(sessionResp.accessToken),
            refreshToken = cryptoManager.encryptString(sessionResp.refreshToken),
            sessionSecret = cryptoManager.encryptString(sessionResp.sessionSecretBase64),
            expiresAtMs = sessionResp.expiresAtEpochMs
        )
        SecureVaultLogger.d("ClientApi", "Refreshed short-lived access token for $deviceId")
        sessionResp.accessToken
    }

    private inline fun <reified T> executeJson(client: OkHttpClient, request: Request): T {
        return try {
            client.newCall(request).execute().use { resp ->
                parseOrThrow(resp)
            }
        } catch (e: VaultException) {
            throw e
        } catch (e: IOException) {
            throw VaultException("Connection lost. Retrying...", cause = e)
        }
    }

    private inline fun <reified T> parseOrThrow(resp: Response): T {
        val raw = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            throw mapHttpErrorCode(resp.code, raw)
        }
        return try {
            json.decodeFromString<T>(raw)
        } catch (e: Exception) {
            throw VaultException("Unexpected response from Home Storage.", resp.code, e)
        }
    }

    private fun mapHttpError(resp: Response): VaultException {
        val raw = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
        return mapHttpErrorCode(resp.code, raw)
    }

    private fun mapHttpErrorCode(code: Int, rawBody: String): VaultException {
        val parsed = runCatching { json.decodeFromString<ApiErrorResponse>(rawBody) }.getOrNull()
        val friendly = when (code) {
            401, 403 -> parsed?.message?.takeIf { it.isNotBlank() }
                ?: "Access denied. Please reconnect the Home Storage."
            404 -> "Requested file or folder was not found."
            422 -> "Transfer integrity check failed. Retrying..."
            429 -> "Too many failed attempts. Please wait a moment."
            503 -> "Home Storage folder is unavailable."
            else -> parsed?.message ?: "Unable to complete request (code $code)."
        }
        return VaultException(friendly, code)
    }
}
