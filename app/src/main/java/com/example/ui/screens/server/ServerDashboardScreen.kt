package com.example.ui.screens.server

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.client.ConnectionHealth
import com.example.server.ServerUiState
import com.example.ui.components.StatusPulseBadge
import com.example.ui.components.StorageUsageBar
import com.example.ui.components.VaultErrorBanner
import com.example.ui.theme.VaultCoral
import com.example.ui.theme.VaultEmerald
import com.example.ui.util.Formatters
import com.example.ui.util.QrCodeGenerator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDashboardScreen(
    state: ServerUiState,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRegeneratePairingCode: () -> Unit,
    onOpenFilesWizard: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    var showPairingDialog by remember { mutableStateOf(false) }

    val runtime = state.runtime
    val info = runtime.deviceInfo
    val storage = runtime.storageInfo
    val isOnline = runtime.isRunning

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.settings.serverDeviceName.uppercase(),
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("server_settings_icon_button")
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Server Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (runtime.errorMessage != null) {
                    item {
                        VaultErrorBanner(
                            message = runtime.errorMessage,
                            onRetry = onStartServer
                        )
                    }
                }

                // Hero status card
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPulseBadge(
                                    health = if (isOnline) ConnectionHealth.ONLINE else ConnectionHealth.OFFLINE,
                                    label = if (isOnline) "SERVER ONLINE" else "SERVER OFFLINE"
                                )
                                Button(
                                    onClick = { if (isOnline) onStopServer() else onStartServer() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isOnline) VaultCoral else VaultEmerald
                                    ),
                                    modifier = Modifier.testTag("server_toggle_button")
                                ) {
                                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isOnline) "Stop" else "Start")
                                }
                            }

                            if (storage != null) {
                                Text(
                                    text = "Storage • ${Formatters.formatBytes(storage.usedBytes)} / ${Formatters.formatBytes(storage.totalBytes)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                StorageUsageBar(usedBytes = storage.usedBytes, totalBytes = storage.totalBytes)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${Formatters.formatBytes(storage.freeBytes)} available",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${storage.fileCount} files",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                StatMiniCard(
                                    label = "Connected devices",
                                    value = "${info?.connectedClients ?: 0}",
                                    modifier = Modifier.weight(1f)
                                )
                                StatMiniCard(
                                    label = "Today transfers",
                                    value = "${state.todayTransfersCount}",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Quick actions matching prompt section 32
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onOpenFilesWizard,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("server_change_folder_button")
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Folder")
                        }
                        Button(
                            onClick = { showPairingDialog = true },
                            enabled = isOnline,
                            modifier = Modifier
                                .weight(1.3f)
                                .testTag("pair_new_device_button")
                        ) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pair New Device")
                        }
                        OutlinedButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("server_settings_button")
                        ) {
                            Text("Settings")
                        }
                    }
                }

                // Connection Details Card
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Server Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            DetailRow("Device Name", info?.deviceName ?: state.settings.serverDeviceName)
                            DetailRow("Device ID", info?.deviceId ?: state.settings.serverDeviceId.ifBlank { "Pending" })
                            DetailRow("Local IP", "${info?.localIp ?: "--"}:${info?.port ?: state.settings.serverPort}")
                            DetailRow("Storage Path", state.settings.serverStorageLabel.ifBlank { info?.storagePathLabel ?: "App Vault" })
                            DetailRow(
                                "Relay Tunnel",
                                when {
                                    state.settings.relayUrl.isBlank() -> "Not configured (LAN / Direct)"
                                    info?.relayConnected == true -> "Connected"
                                    else -> "Connecting..."
                                }
                            )
                            DetailRow("Last Activity", Formatters.formatRelativeTime(info?.lastActivityEpochMs ?: 0L))
                        }
                    }
                }

                // Battery Optimization Guidance
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.BatteryChargingFull,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Keep Server Alive 24/7", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "Disable battery optimization for HomeVault Remote so Android does not pause background transfers when the screen is off.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = onOpenBatterySettings) {
                                Text("Configure")
                            }
                        }
                    }
                }

                // Recent Server Activity
                if (state.activities.isNotEmpty()) {
                    item {
                        Text(
                            "Recent Activity",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(state.activities.take(10), key = { it.id }) { act ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(act.detail, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${act.eventType} • ${act.clientName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    Formatters.formatRelativeTime(act.timestampEpochMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    if (showPairingDialog) {
        val qrBitmap = remember(state.pairingQrJson) {
            QrCodeGenerator.generateQrBitmap(state.pairingQrJson, 512)
        }
        AlertDialog(
            onDismissRequest = { showPairingDialog = false },
            title = { Text("Pair New Client Device") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(210.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(10.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Encrypted pairing QR code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Text(
                        text = "Pairing Code: ${runtime.pairingCode.ifBlank { "------" }}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Local: ${info?.localIp ?: "--"}:${info?.port ?: 8443}\nPIN is never embedded in the QR code — enter your PIN on the client phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showPairingDialog = false }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = onRegeneratePairingCode) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Regenerate")
                }
            }
        )
    }
}

@Composable
private fun StatMiniCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}
