package com.example.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.database.AppSettings
import com.example.database.ServerActivityEntity
import com.example.database.SettingsStore
import com.example.domain.model.PairingPayload
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

data class ServerUiState(
    val settings: AppSettings = AppSettings(),
    val runtime: ServerRuntimeSnapshot = ServerRuntimeSnapshot(),
    val activities: List<ServerActivityEntity> = emptyList(),
    val todayTransfersCount: Int = 0,
    val pairingQrJson: String = ""
)

class ServerViewModel(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val sessionManager: SessionManager,
    private val activityDao: com.example.database.ServerActivityDao
) : ViewModel() {

    private val json = Json { encodeDefaults = true }

    private val startOfTodayMs: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val uiState: StateFlow<ServerUiState> = combine(
        settingsStore.settingsFlow,
        ServerRuntimeBus.state,
        activityDao.observeRecent(),
        activityDao.observeTodayTransferCount(startOfTodayMs)
    ) { settings, runtime, activities, todayCount ->
        val info = runtime.deviceInfo
        val qrJson = if (info != null && runtime.pairingCode.isNotBlank()) {
            json.encodeToString(
                PairingPayload(
                    deviceId = info.deviceId,
                    deviceName = info.deviceName,
                    localIp = info.localIp,
                    port = info.port,
                    directUrl = settings.directEndpointUrl,
                    relayUrl = settings.relayUrl,
                    pairingCode = runtime.pairingCode,
                    tlsFingerprint = info.tlsFingerprint,
                    expiresAtEpochMs = runtime.pairingExpiresAtMs
                )
            )
        } else ""

        ServerUiState(
            settings = settings,
            runtime = runtime,
            activities = activities,
            todayTransfersCount = todayCount,
            pairingQrJson = qrJson
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServerUiState())

    fun saveWizardSetup(
        deviceName: String,
        storageUri: Uri?,
        storageLabel: String,
        pin: String,
        port: Int,
        relayUrl: String,
        startOnBoot: Boolean
    ) {
        viewModelScope.launch {
            settingsStore.getOrCreateServerDeviceId()
            if (storageUri != null) {
                runCatching {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(storageUri, flags)
                }
            }
            if (pin.length >= 4) {
                settingsStore.savePin(pin)
            }
            settingsStore.updateServerConfig(
                deviceName = deviceName.trim().ifBlank { "Home Storage" },
                port = port.coerceIn(1024, 65535),
                storageUri = storageUri?.toString(),
                storageLabel = storageLabel,
                relayUrl = relayUrl.trim(),
                startOnBoot = startOnBoot
            )
            sessionManager.generateOrGetPairingCode(forceRegenerate = true)
            HomeVaultServerService.start(context)
        }
    }

    fun startServer() {
        HomeVaultServerService.start(context)
    }

    fun stopServer() {
        HomeVaultServerService.stop(context)
    }

    fun regeneratePairingCode() {
        val codeState = sessionManager.generateOrGetPairingCode(forceRegenerate = true)
        val current = ServerRuntimeBus.state.value
        ServerRuntimeBus.update(
            current.copy(
                pairingCode = codeState.code,
                pairingExpiresAtMs = codeState.expiresAtMs
            )
        )
    }

    fun revokeAllSessions() {
        sessionManager.revokeAllSessions()
    }

    fun logoutAllClients() {
        sessionManager.disconnectAllClients()
    }

    fun updatePin(newPin: String) {
        if (newPin.length < 4) return
        viewModelScope.launch {
            settingsStore.savePin(newPin)
            sessionManager.revokeAllSessions()
        }
    }

    fun updateServerSettings(
        deviceName: String? = null,
        port: Int? = null,
        relayUrl: String? = null,
        directUrl: String? = null,
        startOnBoot: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsStore.updateServerConfig(
                deviceName = deviceName,
                port = port,
                relayUrl = relayUrl,
                directEndpointUrl = directUrl,
                startOnBoot = startOnBoot
            )
        }
    }

    fun updateStorageFolder(uri: Uri, label: String) {
        viewModelScope.launch {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            settingsStore.updateServerConfig(
                storageUri = uri.toString(),
                storageLabel = label
            )
        }
    }

    fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    class Factory(
        private val context: Context,
        private val settingsStore: SettingsStore,
        private val sessionManager: SessionManager,
        private val activityDao: com.example.database.ServerActivityDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ServerViewModel(context, settingsStore, sessionManager, activityDao) as T
        }
    }
}
