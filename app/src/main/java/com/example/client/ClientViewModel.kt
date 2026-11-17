package com.example.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.database.AppSettings
import com.example.database.PairedServerDao
import com.example.database.RecentFileDao
import com.example.database.SettingsStore
import com.example.domain.model.ConnectionRoute
import com.example.domain.model.DiscoveredLanServer
import com.example.domain.model.FileCategory
import com.example.domain.model.PairedServer
import com.example.domain.model.PairingPayload
import com.example.domain.model.RemoteFileItem
import com.example.domain.model.StorageInfo
import com.example.domain.model.TransferItem
import com.example.network.ConnectionRouter
import com.example.security.SecureVaultLogger
import com.example.server.NsdDiscoveryService
import com.example.transfer.TransferManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream

enum class ConnectionHealth {
    ONLINE,
    RECONNECTING,
    OFFLINE
}

data class ClientUiState(
    val settings: AppSettings = AppSettings(),
    val pairedServers: List<PairedServer> = emptyList(),
    val activeServer: PairedServer? = null,
    val activeRoute: ConnectionRoute = ConnectionRoute.OFFLINE,
    val connectionHealth: ConnectionHealth = ConnectionHealth.OFFLINE,
    val latencyMs: Long = 0L,
    val discoveredLanServers: List<DiscoveredLanServer> = emptyList(),
    val storageInfo: StorageInfo? = null,
    val currentPath: String = "",
    val files: List<RemoteFileItem> = emptyList(),
    val hasMoreFiles: Boolean = false,
    val isLoadingFiles: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val searchQuery: String = "",
    val searchCategory: FileCategory? = null,
    val searchResults: List<RemoteFileItem> = emptyList(),
    val isSearching: Boolean = false,
    val recentFiles: List<RemoteFileItem> = emptyList(),
    val transfers: List<TransferItem> = emptyList(),
    val statusBannerError: String? = null,
    val infoMessage: String? = null,
    val isPairing: Boolean = false
)

class ClientViewModel(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val pairedServerDao: PairedServerDao,
    private val recentFileDao: RecentFileDao,
    private val clientApi: HomeVaultClientApi,
    private val transferManager: TransferManager,
    private val nsdService: NsdDiscoveryService
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val connectionRouter = ConnectionRouter()

    private val _localState = MutableStateFlow(LocalMutableState())
    private var reconnectJob: Job? = null
    private var searchJob: Job? = null

    private data class LocalMutableState(
        val activeRoute: ConnectionRoute = ConnectionRoute.OFFLINE,
        val connectionHealth: ConnectionHealth = ConnectionHealth.OFFLINE,
        val latencyMs: Long = 0L,
        val storageInfo: StorageInfo? = null,
        val currentPath: String = "",
        val files: List<RemoteFileItem> = emptyList(),
        val hasMoreFiles: Boolean = false,
        val isLoadingFiles: Boolean = false,
        val selectedPaths: Set<String> = emptySet(),
        val searchQuery: String = "",
        val searchCategory: FileCategory? = null,
        val searchResults: List<RemoteFileItem> = emptyList(),
        val isSearching: Boolean = false,
        val statusBannerError: String? = null,
        val infoMessage: String? = null,
        val isPairing: Boolean = false
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val recentFilesFlow = settingsStore.settingsFlow.flatMapLatest { settings ->
        if (settings.activeDeviceId.isBlank()) flowOf(emptyList())
        else recentFileDao.observeRecentForDevice(settings.activeDeviceId).map { list ->
            list.map { it.toDomain() }
        }
    }

    val uiState: StateFlow<ClientUiState> = combine(
        settingsStore.settingsFlow,
        pairedServerDao.observeAll().map { it.map { e -> e.toDomain() } },
        nsdService.discoveredServers,
        recentFilesFlow,
        transferManager.transfersFlow,
        _localState
    ) { arr ->
        val settings = arr[0] as AppSettings
        @Suppress("UNCHECKED_CAST")
        val servers = arr[1] as List<PairedServer>
        @Suppress("UNCHECKED_CAST")
        val discovered = arr[2] as List<DiscoveredLanServer>
        @Suppress("UNCHECKED_CAST")
        val recent = arr[3] as List<RemoteFileItem>
        @Suppress("UNCHECKED_CAST")
        val transfers = arr[4] as List<TransferItem>
        val local = arr[5] as LocalMutableState

        val active = servers.firstOrNull { it.deviceId == settings.activeDeviceId } ?: servers.firstOrNull()
        ClientUiState(
            settings = settings,
            pairedServers = servers,
            activeServer = active,
            activeRoute = local.activeRoute,
            connectionHealth = local.connectionHealth,
            latencyMs = local.latencyMs,
            discoveredLanServers = discovered,
            storageInfo = local.storageInfo,
            currentPath = local.currentPath,
            files = local.files,
            hasMoreFiles = local.hasMoreFiles,
            isLoadingFiles = local.isLoadingFiles,
            selectedPaths = local.selectedPaths,
            searchQuery = local.searchQuery,
            searchCategory = local.searchCategory,
            searchResults = local.searchResults,
            isSearching = local.isSearching,
            recentFiles = recent,
            transfers = transfers,
            statusBannerError = local.statusBannerError,
            infoMessage = local.infoMessage,
            isPairing = local.isPairing
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ClientUiState())

    init {
        nsdService.startDiscovery()
        viewModelScope.launch {
            val servers = pairedServerDao.getAll()
            val settings = settingsStore.settingsFlow.first()
            val targetId = settings.activeDeviceId.ifBlank { servers.firstOrNull()?.deviceId.orEmpty() }
            if (targetId.isNotBlank()) {
                settingsStore.setActiveDeviceId(targetId)
                refreshConnectionAndFiles(targetId)
            }
        }
    }

    fun startLanScan() {
        nsdService.startDiscovery()
    }

    fun pairWithQrJson(qrRaw: String, pin: String) {
        viewModelScope.launch {
            val payload = runCatching { json.decodeFromString<PairingPayload>(qrRaw.trim()) }.getOrNull()
            if (payload == null) {
                setError("Invalid QR pairing code.")
                return@launch
            }
            pairWithDetails(
                host = payload.localIp,
                port = payload.port,
                relayUrl = payload.relayUrl,
                deviceId = payload.deviceId,
                deviceName = payload.deviceName,
                pairingCode = payload.pairingCode,
                pin = pin,
                tlsFingerprint = payload.tlsFingerprint
            )
        }
    }

    fun pairWithDetails(
        host: String,
        port: Int,
        relayUrl: String,
        deviceId: String,
        deviceName: String,
        pairingCode: String,
        pin: String,
        tlsFingerprint: String = ""
    ) {
        viewModelScope.launch {
            _localState.value = _localState.value.copy(isPairing = true, statusBannerError = null)
            try {
                val clientId = settingsStore.getOrCreateClientId()
                val payload = PairingPayload(
                    deviceId = deviceId.ifBlank { "manual" },
                    deviceName = deviceName.ifBlank { "Home Storage" },
                    localIp = host.trim(),
                    port = port,
                    relayUrl = relayUrl.trim(),
                    pairingCode = pairingCode.trim(),
                    tlsFingerprint = tlsFingerprint,
                    expiresAtEpochMs = System.currentTimeMillis() + 300_000L
                )
                val paired = clientApi.pairWithServer(
                    payload = payload,
                    pin = pin,
                    clientId = clientId,
                    clientName = android.os.Build.MODEL
                )
                settingsStore.setActiveDeviceId(paired.deviceId)
                _localState.value = _localState.value.copy(
                    isPairing = false,
                    infoMessage = "Paired with ${paired.deviceName}"
                )
                refreshConnectionAndFiles(paired.deviceId)
            } catch (e: VaultException) {
                _localState.value = _localState.value.copy(isPairing = false, statusBannerError = e.userMessage)
            } catch (e: Exception) {
                _localState.value = _localState.value.copy(
                    isPairing = false,
                    statusBannerError = e.message ?: "Pairing failed."
                )
            }
        }
    }

    fun selectServer(deviceId: String) {
        viewModelScope.launch {
            settingsStore.setActiveDeviceId(deviceId)
            _localState.value = _localState.value.copy(
                currentPath = "",
                selectedPaths = emptySet(),
                files = emptyList(),
                storageInfo = null
            )
            refreshConnectionAndFiles(deviceId)
        }
    }

    fun renameServer(deviceId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            pairedServerDao.rename(deviceId, newName.trim())
        }
    }

    fun disconnectServer(deviceId: String) {
        viewModelScope.launch {
            val clientId = settingsStore.getOrCreateClientId()
            clientApi.logout(deviceId, clientId)
            pairedServerDao.updateTokens(deviceId, "", "", "", 0L)
            pairedServerDao.updateStatus(deviceId, false, ConnectionRoute.OFFLINE.name, System.currentTimeMillis(), 0L, 0L, 0)
            _localState.value = _localState.value.copy(
                connectionHealth = ConnectionHealth.OFFLINE,
                activeRoute = ConnectionRoute.OFFLINE,
                infoMessage = "Disconnected from device"
            )
        }
    }

    fun removeServer(deviceId: String) {
        viewModelScope.launch {
            pairedServerDao.deleteById(deviceId)
            val remaining = pairedServerDao.getAll()
            settingsStore.setActiveDeviceId(remaining.firstOrNull()?.deviceId ?: "")
        }
    }

    fun refreshConnectionAndFiles(explicitDeviceId: String? = null) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val deviceId = explicitDeviceId ?: uiState.value.activeServer?.deviceId ?: return@launch
            val clientId = settingsStore.getOrCreateClientId()
            val settings = settingsStore.settingsFlow.first()

            _localState.value = _localState.value.copy(
                connectionHealth = ConnectionHealth.RECONNECTING,
                statusBannerError = null
            )

            try {
                connectionRouter.withExponentialBackoff(maxAttempts = if (settings.autoReconnect) 3 else 1) {
                    val (_, endpoint) = clientApi.fetchDeviceInfo(deviceId, clientId)
                    val storage = clientApi.fetchStorageInfo(deviceId, clientId)
                    _localState.value = _localState.value.copy(
                        activeRoute = endpoint.route,
                        connectionHealth = ConnectionHealth.ONLINE,
                        latencyMs = endpoint.latencyMs,
                        storageInfo = storage,
                        statusBannerError = null
                    )
                    loadDirectory(_localState.value.currentPath, reset = true)
                }
            } catch (e: VaultException) {
                pairedServerDao.updateStatus(
                    deviceId,
                    online = false,
                    route = ConnectionRoute.OFFLINE.name,
                    lastConnectedMs = System.currentTimeMillis(),
                    usedBytes = uiState.value.activeServer?.usedBytes ?: 0L,
                    totalBytes = uiState.value.activeServer?.totalBytes ?: 0L,
                    fileCount = uiState.value.activeServer?.fileCount ?: 0
                )
                _localState.value = _localState.value.copy(
                    connectionHealth = ConnectionHealth.OFFLINE,
                    activeRoute = ConnectionRoute.OFFLINE,
                    statusBannerError = e.userMessage
                )
            }
        }
    }

    fun loadDirectory(path: String, reset: Boolean = true) {
        viewModelScope.launch {
            val deviceId = uiState.value.activeServer?.deviceId ?: return@launch
            val clientId = settingsStore.getOrCreateClientId()
            val offset = if (reset) 0 else _localState.value.files.size
            _localState.value = _localState.value.copy(
                isLoadingFiles = true,
                currentPath = path,
                selectedPaths = if (reset) emptySet() else _localState.value.selectedPaths
            )
            try {
                val resp = clientApi.listFiles(deviceId, clientId, path, offset = offset, limit = 100)
                val merged = if (reset) resp.items else _localState.value.files + resp.items
                _localState.value = _localState.value.copy(
                    files = merged,
                    hasMoreFiles = resp.hasMore,
                    isLoadingFiles = false,
                    statusBannerError = null
                )
            } catch (e: VaultException) {
                _localState.value = _localState.value.copy(
                    isLoadingFiles = false,
                    statusBannerError = e.userMessage
                )
            }
        }
    }

    fun navigateUp(): Boolean {
        val cur = _localState.value.currentPath
        if (cur.isEmpty()) return false
        val parent = if (cur.contains('/')) cur.substringBeforeLast('/') else ""
        loadDirectory(parent, reset = true)
        return true
    }

    fun toggleSelection(path: String) {
        val current = _localState.value.selectedPaths
        val next = if (path in current) current - path else current + path
        _localState.value = _localState.value.copy(selectedPaths = next)
    }

    fun selectAllVisible() {
        _localState.value = _localState.value.copy(
            selectedPaths = _localState.value.files.map { it.path }.toSet()
        )
    }

    fun clearSelection() {
        _localState.value = _localState.value.copy(selectedPaths = emptySet())
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val deviceId = uiState.value.activeServer?.deviceId ?: return@launch
            val clientId = settingsStore.getOrCreateClientId()
            try {
                clientApi.createFolder(deviceId, clientId, _localState.value.currentPath, name.trim())
                loadDirectory(_localState.value.currentPath, reset = true)
                setInfo("Folder created")
            } catch (e: VaultException) {
                setError(e.userMessage)
            }
        }
    }

    fun renameSelected(path: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            val deviceId = uiState.value.activeServer?.deviceId ?: return@launch
            val clientId = settingsStore.getOrCreateClientId()
            try {
                clientApi.renameFile(deviceId, clientId, path, newName.trim())
                clearSelection()
                loadDirectory(_localState.value.currentPath, reset = true)
                setInfo("Renamed successfully")
            } catch (e: VaultException) {
                setError(e.userMessage)
            }
        }
    }

    fun deletePaths(paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val deviceId = uiState.value.activeServer?.deviceId ?: return@launch
            val clientId = settingsStore.getOrCreateClientId()
            try {
                clientApi.deleteFiles(deviceId, clientId, paths)
                clearSelection()
                loadDirectory(_localState.value.currentPath, reset = true)
                setInfo("Deleted ${paths.size} item(s)")
            } catch (e: VaultException) {
                setError(e.userMessage)
            }
        }
    }

    fun copyPaths(paths: List<String>, destinationFolder: String) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val deviceId = uiState.value.activeServer?.deviceId ?: return@launch
            val clientId = settingsStore.getOrCreateClientId()
            try {
                clientApi.copyFiles(deviceId, clientId, paths, destinationFolder)
                clearSelection()
                loadDirectory(_localState.value.currentPath, reset = true)
                setInfo("Copied ${paths.size} item(s)")
            } catch (e: VaultException) {
                setError(e.userMessage)
            }
        }
    }

    fun movePaths(paths: List<String>, destinationFolder: String) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val deviceId = uiState.value.activeServer?.deviceId ?: return@launch
            val clientId = settingsStore.getOrCreateClientId()
            try {
                clientApi.moveFiles(deviceId, clientId, paths, destinationFolder)
                clearSelection()
                loadDirectory(_localState.value.currentPath, reset = true)
                setInfo("Moved ${paths.size} item(s)")
            } catch (e: VaultException) {
                setError(e.userMessage)
            }
        }
    }

    fun uploadUris(uris: List<Uri>, targetFolder: String = _localState.value.currentPath) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val deviceId = uiState.value.activeServer?.deviceId ?: run {
                setError("No Home Storage server connected.")
                return@launch
            }
            for (uri in uris) {
                transferManager.enqueueUpload(deviceId, uri, targetFolder)
            }
            setInfo("Queued ${uris.size} upload(s)")
        }
    }

    fun downloadFileToUri(file: RemoteFileItem, destinationUri: Uri) {
        viewModelScope.launch {
            val deviceId = uiState.value.activeServer?.deviceId ?: return@launch
            transferManager.enqueueDownload(deviceId, file, destinationUri)
            setInfo("Queued download: ${file.name}")
        }
    }

    fun pauseTransfer(id: String) = transferManager.pauseTransfer(id)
    fun resumeTransfer(id: String) = transferManager.resumeTransfer(id)
    fun cancelTransfer(id: String) = transferManager.cancelTransfer(id)
    fun clearTransferHistory() {
        viewModelScope.launch { transferManager.clearHistory() }
    }

    fun updateSearch(query: String, category: FileCategory? = _localState.value.searchCategory) {
        _localState.value = _localState.value.copy(searchQuery = query, searchCategory = category)
        searchJob?.cancel()
        if (query.isBlank() && category == null) {
            _localState.value = _localState.value.copy(searchResults = emptyList(), isSearching = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(300L)
            val deviceId = uiState.value.activeServer?.deviceId ?: return@launch
            val clientId = settingsStore.getOrCreateClientId()
            _localState.value = _localState.value.copy(isSearching = true)
            try {
                val results = clientApi.searchFiles(deviceId, clientId, query, category)
                _localState.value = _localState.value.copy(searchResults = results, isSearching = false)
            } catch (e: VaultException) {
                _localState.value = _localState.value.copy(isSearching = false, statusBannerError = e.userMessage)
            }
        }
    }

    suspend fun loadThumbnail(path: String): ByteArray? {
        val deviceId = uiState.value.activeServer?.deviceId ?: return null
        val clientId = settingsStore.getOrCreateClientId()
        return clientApi.fetchThumbnailBytes(deviceId, clientId, path)
    }

    suspend fun fetchFileBytesForPreview(remotePath: String, maxBytes: Long = 8 * 1024 * 1024): File? {
        val deviceId = uiState.value.activeServer?.deviceId ?: return null
        val clientId = settingsStore.getOrCreateClientId()
        return try {
            val previewDir = File(context.cacheDir, "previews").apply { mkdirs() }
            val safeName = remotePath.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val target = File(previewDir, safeName)
            clientApi.openDownloadResponse(deviceId, clientId, remotePath).use { resp ->
                val len = resp.body?.contentLength() ?: -1L
                if (len > maxBytes) return null
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(target).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var copied = 0L
                        while (copied < maxBytes) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            output.write(buf, 0, r)
                            copied += r
                        }
                    }
                }
            }
            target
        } catch (e: Exception) {
            SecureVaultLogger.w("Preview", "Preview download failed: ${e.message}")
            null
        }
    }

    suspend fun getMediaStreamSpec(remotePath: String): Triple<OkHttpClient, String, Map<String, String>>? {
        val deviceId = uiState.value.activeServer?.deviceId ?: return null
        return runCatching { clientApi.buildAuthedStreamSpec(deviceId, remotePath) }.getOrNull()
    }

    fun shareRemoteFile(remoteFile: RemoteFileItem) {
        viewModelScope.launch {
            val previewDir = File(context.cacheDir, "previews")
            val safeName = remoteFile.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cached = File(previewDir, safeName)
            val file = if (cached.exists() && cached.length() == remoteFile.sizeBytes) {
                cached
            } else {
                fetchFileBytesForPreview(remoteFile.path, maxBytes = 64 * 1024 * 1024)
            }
            if (file == null) {
                setError("Unable to prepare file for sharing.")
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = remoteFile.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share ${remoteFile.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    fun openWithAnotherApp(remoteFile: RemoteFileItem) {
        viewModelScope.launch {
            val file = fetchFileBytesForPreview(remoteFile.path, maxBytes = 64 * 1024 * 1024)
            if (file == null) {
                setError("Download file first to open externally.")
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, remoteFile.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(viewIntent) }
                .onFailure { setError("No app installed to open ${remoteFile.name}.") }
        }
    }

    fun clearMessages() {
        _localState.value = _localState.value.copy(statusBannerError = null, infoMessage = null)
    }

    private fun setError(msg: String) {
        _localState.value = _localState.value.copy(statusBannerError = msg)
    }

    private fun setInfo(msg: String) {
        _localState.value = _localState.value.copy(infoMessage = msg)
    }

    override fun onCleared() {
        nsdService.stopDiscovery()
        super.onCleared()
    }

    class Factory(
        private val context: Context,
        private val settingsStore: SettingsStore,
        private val pairedServerDao: PairedServerDao,
        private val recentFileDao: RecentFileDao,
        private val clientApi: HomeVaultClientApi,
        private val transferManager: TransferManager,
        private val nsdService: NsdDiscoveryService
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ClientViewModel(
                context, settingsStore, pairedServerDao, recentFileDao, clientApi, transferManager, nsdService
            ) as T
        }
    }
}
