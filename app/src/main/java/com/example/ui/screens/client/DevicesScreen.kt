package com.example.ui.screens.client

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.client.ClientUiState
import com.example.client.ConnectionHealth
import com.example.domain.model.PairedServer
import com.example.ui.components.StatusPulseBadge
import com.example.ui.components.VaultErrorBanner
import com.example.ui.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    state: ClientUiState,
    onBack: () -> Unit,
    onSelectServer: (String) -> Unit,
    onScanQr: () -> Unit,
    onRefreshLan: () -> Unit,
    onPairManual: (
        host: String,
        port: Int,
        relayUrl: String,
        deviceId: String,
        deviceName: String,
        pairingCode: String,
        pin: String,
        tlsFingerprint: String
    ) -> Unit,
    onRenameServer: (String, String) -> Unit,
    onDisconnectServer: (String) -> Unit,
    onRemoveServer: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var prefillHost by remember { mutableStateOf("") }
    var prefillPort by remember { mutableStateOf("8443") }
    var prefillDeviceId by remember { mutableStateOf("") }
    var prefillDeviceName by remember { mutableStateOf("Home Storage") }
    var prefillFingerprint by remember { mutableStateOf("") }

    var renameTarget by remember { mutableStateOf<PairedServer?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("My Devices") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshLan) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Scan LAN")
                    }
                    IconButton(
                        onClick = onScanQr,
                        modifier = Modifier.testTag("devices_scan_qr_button")
                    ) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Pair with QR Code")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    prefillHost = ""
                    prefillPort = "8443"
                    prefillDeviceId = ""
                    prefillDeviceName = "Home Storage"
                    prefillFingerprint = ""
                    showAddDialog = true
                },
                modifier = Modifier.testTag("add_device_fab")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Add Device")
            }
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.statusBannerError != null) {
                    item { VaultErrorBanner(message = state.statusBannerError) }
                }

                // LAN NSD Discovered Servers
                if (state.discoveredLanServers.isNotEmpty()) {
                    item {
                        Text(
                            "HomeVault Servers on Wi-Fi",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(state.discoveredLanServers, key = { it.deviceId }) { lan ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.WifiFind, contentDescription = null)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(lan.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("${lan.host}:${lan.port} • Online", style = MaterialTheme.typography.labelMedium)
                                }
                                Button(onClick = {
                                    prefillHost = lan.host
                                    prefillPort = lan.port.toString()
                                    prefillDeviceId = lan.deviceId
                                    prefillDeviceName = lan.deviceName
                                    prefillFingerprint = lan.tlsFingerprint
                                    showAddDialog = true
                                }) {
                                    Text("Pair")
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Paired Home Servers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = onScanQr) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan QR")
                        }
                    }
                }

                if (state.pairedServers.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No devices paired yet. Tap + or Scan QR to pair with your Home Server.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    items(state.pairedServers, key = { it.deviceId }) { server ->
                        val isActive = state.activeServer?.deviceId == server.deviceId
                        Card(
                            onClick = { onSelectServer(server.deviceId) },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isActive) {
                                            Icon(
                                                Icons.Rounded.CheckCircle,
                                                contentDescription = "Active",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(
                                            server.deviceName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    StatusPulseBadge(
                                        health = if (server.isOnline) ConnectionHealth.ONLINE else ConnectionHealth.OFFLINE,
                                        label = if (server.isOnline) "Online" else "Offline"
                                    )
                                }

                                Text(
                                    text = "${Formatters.formatBytes(server.usedBytes)} used • ${server.localIp}:${server.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = { renameTarget = server },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Rounded.Edit, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Rename")
                                    }
                                    OutlinedButton(
                                        onClick = { onDisconnectServer(server.deviceId) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Rounded.LinkOff, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Disconnect")
                                    }
                                    IconButton(onClick = { onRemoveServer(server.deviceId) }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Remove Device")
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    if (showAddDialog) {
        var host by remember(prefillHost) { mutableStateOf(prefillHost) }
        var port by remember(prefillPort) { mutableStateOf(prefillPort) }
        var relayUrl by remember { mutableStateOf("") }
        var pairingCode by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Pair Home Server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Local IP / Host (optional if Relay set)") },
                        placeholder = { Text("192.168.1.50") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = relayUrl,
                        onValueChange = { relayUrl = it },
                        label = { Text("Relay URL (for outside Wi-Fi)") },
                        placeholder = { Text("https://relay.yourdomain.com") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it },
                        label = { Text("6-Digit Pairing Code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.testTag("pair_code_input")
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text("Server PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.testTag("pair_pin_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAddDialog = false
                        onPairManual(
                            host,
                            port.toIntOrNull() ?: 8443,
                            relayUrl,
                            prefillDeviceId,
                            prefillDeviceName,
                            pairingCode,
                            pin,
                            prefillFingerprint
                        )
                    },
                    modifier = Modifier.testTag("confirm_pair_button")
                ) {
                    Text("Pair")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }

    renameTarget?.let { server ->
        var newName by remember(server.deviceId) { mutableStateOf(server.deviceName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Device") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Device Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRenameServer(server.deviceId, newName)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }
}
