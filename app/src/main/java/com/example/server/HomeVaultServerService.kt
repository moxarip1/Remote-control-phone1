package com.example.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.HomeVaultApplication
import com.example.MainActivity
import com.example.R
import com.example.database.ServerActivityEntity
import com.example.domain.model.DeviceInfo
import com.example.domain.model.StorageInfo
import com.example.security.SecureVaultLogger
import com.example.storage.LocalDirectoryStorageProvider
import com.example.storage.SafStorageProvider
import com.example.storage.StorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class ServerRuntimeSnapshot(
    val isRunning: Boolean = false,
    val deviceInfo: DeviceInfo? = null,
    val storageInfo: StorageInfo? = null,
    val pairingCode: String = "",
    val pairingExpiresAtMs: Long = 0L,
    val errorMessage: String? = null
)

object ServerRuntimeBus {
    private val _state = MutableStateFlow(ServerRuntimeSnapshot())
    val state: StateFlow<ServerRuntimeSnapshot> = _state.asStateFlow()

    @Volatile
    var activeHttpServer: HomeVaultHttpServer? = null

    @Volatile
    var activeSessionManager: SessionManager? = null

    fun update(snapshot: ServerRuntimeSnapshot) {
        _state.value = snapshot
    }
}

class HomeVaultServerService : Service() {

    companion object {
        const val ACTION_START = "com.example.server.ACTION_START"
        const val ACTION_STOP = "com.example.server.ACTION_STOP"
        private const val CHANNEL_ID = "homevault_server_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, HomeVaultServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, HomeVaultServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var statusLoopJob: Job? = null
    private var httpServer: HomeVaultHttpServer? = null
    private var relayTunnel: RelayTunnelClient? = null
    private var nsdService: NsdDiscoveryService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServerAndSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("Starting HomeVault server...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        startServerEngine()
        return START_STICKY
    }

    private fun startServerEngine() {
        if (httpServer?.isRunning == true) return
        val container = (application as HomeVaultApplication).container

        serviceScope.launch {
            try {
                val settings = container.settingsStore.settingsFlow.first()
                val deviceId = container.settingsStore.getOrCreateServerDeviceId()
                val sessionManager = container.serverSessionManager

                val storageFactory: () -> StorageProvider? = {
                    val uriStr = settings.serverStorageUri
                    if (uriStr.isNotBlank()) {
                        SafStorageProvider(applicationContext, Uri.parse(uriStr), container.cryptoManager)
                    } else {
                        val fallbackDir = File(filesDir, "HomeVaultDefault").apply { mkdirs() }
                        LocalDirectoryStorageProvider(fallbackDir, container.cryptoManager)
                    }
                }

                val server = HomeVaultHttpServer(
                    deviceId = deviceId,
                    deviceNameProvider = { settings.serverDeviceName },
                    storageProviderFactory = storageFactory,
                    pinVerifier = { pin -> container.settingsStore.verifyPin(pin) },
                    sessionManager = sessionManager,
                    cryptoManager = container.cryptoManager,
                    relayUrlProvider = { settings.relayUrl },
                    relayConnectedProvider = { relayTunnel?.isConnected?.value == true },
                    onActivity = { eventType, clientName, detail ->
                        serviceScope.launch {
                            container.database.serverActivityDao().insert(
                                ServerActivityEntity(
                                    timestampEpochMs = System.currentTimeMillis(),
                                    eventType = eventType,
                                    clientName = clientName,
                                    detail = detail
                                )
                            )
                        }
                    }
                )

                val boundPort = server.start(port = settings.serverPort, useTls = true)
                httpServer = server
                ServerRuntimeBus.activeHttpServer = server
                ServerRuntimeBus.activeSessionManager = sessionManager

                container.settingsStore.updateServerConfig(shouldBeRunning = true)

                val info = server.getDeviceInfo()
                val nsd = NsdDiscoveryService(applicationContext)
                nsd.registerServer(deviceId, info.deviceName, boundPort, info.tlsFingerprint)
                nsdService = nsd

                if (settings.relayUrl.isNotBlank()) {
                    val tunnel = RelayTunnelClient(deviceId, server)
                    tunnel.start(settings.relayUrl)
                    relayTunnel = tunnel
                }

                updateNotification("${info.deviceName} • Online on port $boundPort")

                statusLoopJob?.cancel()
                statusLoopJob = serviceScope.launch {
                    while (server.isRunning) {
                        val currentSettings = container.settingsStore.settingsFlow.first()
                        val storageInfo = runCatching { storageFactory()?.getStorageInfo() }.getOrNull()
                        val pairing = sessionManager.generateOrGetPairingCode(false)
                        ServerRuntimeBus.update(
                            ServerRuntimeSnapshot(
                                isRunning = true,
                                deviceInfo = server.getDeviceInfo().copy(deviceName = currentSettings.serverDeviceName),
                                storageInfo = storageInfo,
                                pairingCode = pairing.code,
                                pairingExpiresAtMs = pairing.expiresAtMs,
                                errorMessage = null
                            )
                        )
                        delay(4000L)
                    }
                }
            } catch (e: Exception) {
                SecureVaultLogger.e("ServerService", "Failed to start server", e)
                ServerRuntimeBus.update(
                    ServerRuntimeSnapshot(
                        isRunning = false,
                        errorMessage = e.message ?: "Unable to start HomeVault server."
                    )
                )
                stopSelf()
            }
        }
    }

    private fun stopServerAndSelf() {
        val container = (application as HomeVaultApplication).container
        serviceScope.launch {
            container.settingsStore.updateServerConfig(shouldBeRunning = false)
        }
        statusLoopJob?.cancel()
        relayTunnel?.stop()
        relayTunnel = null
        nsdService?.unregisterServer()
        nsdService = null
        httpServer?.stop()
        httpServer = null
        ServerRuntimeBus.activeHttpServer = null
        ServerRuntimeBus.update(ServerRuntimeSnapshot(isRunning = false))
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeVault::ServerWakeLock")?.apply {
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_server),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps HomeVault personal storage server reachable"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HomeVaultServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_server_running))
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_action_open), openIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    override fun onDestroy() {
        statusLoopJob?.cancel()
        relayTunnel?.stop()
        nsdService?.unregisterServer()
        httpServer?.stop()
        ServerRuntimeBus.activeHttpServer = null
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        val pendingResult = goAsync()
        val app = context.applicationContext as? HomeVaultApplication
        if (app == null) {
            pendingResult.finish()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = app.container.settingsStore.settingsFlow.first()
                if (settings.startOnBoot && settings.hasPinConfigured) {
                    HomeVaultServerService.start(context.applicationContext)
                }
            } catch (e: Exception) {
                SecureVaultLogger.w("BootReceiver", "Could not auto-start server on boot: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
