package com.example.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.BuildConfig
import com.example.database.AppSettings
import com.example.domain.model.AppMode
import com.example.domain.model.ThemeMode
import com.example.ui.theme.VaultCoral

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    serverIsRunning: Boolean,
    onBack: (() -> Unit)? = null,
    onThemeChanged: (ThemeMode) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onAutoReconnectChanged: (Boolean) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onUpdateServerConfig: (deviceName: String?, port: Int?, relayUrl: String?, directUrl: String?, startOnBoot: Boolean?) -> Unit,
    onUpdateServerFolder: (Uri, String) -> Unit,
    onChangePin: (String) -> Unit,
    onRevokeAllSessions: () -> Unit,
    onRegeneratePairingCode: () -> Unit,
    onLogoutAllClients: () -> Unit,
    onDisconnectActiveDevice: () -> Unit,
    onUpdateTransferSettings: (maxConcurrent: Int?, wifiOnly: Boolean?, retryFailed: Boolean?) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onSwitchAppMode: () -> Unit
) {
    val context = LocalContext.current
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showEditServerDialog by remember { mutableStateOf(false) }
    var statusToast by remember { mutableStateOf<String?>(null) }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected Folder"
            onUpdateServerFolder(uri, name)
            statusToast = "Storage folder updated to $name"
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
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
                statusToast?.let { msg ->
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(msg, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // General
                item {
                    SettingsSectionCard("General") {
                        Text("Theme", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { onThemeChanged(mode) },
                                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }
                        Text("Language", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("en" to "English", "ar" to "العربية").forEach { (code, label) ->
                                FilterChip(
                                    selected = settings.languageCode == code,
                                    onClick = { onLanguageChanged(code) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        SettingsSwitchRow(
                            title = "Auto-reconnect",
                            subtitle = "Automatically recover connection with exponential backoff",
                            checked = settings.autoReconnect,
                            onCheckedChange = onAutoReconnectChanged
                        )
                        OutlinedButton(
                            onClick = onSwitchAppMode,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("switch_app_mode_button")
                        ) {
                            Text("Switch App Mode (Current: ${settings.appMode.name})")
                        }
                    }
                }

                // Server Settings
                item {
                    SettingsSectionCard("Server") {
                        SettingsSwitchRow(
                            title = "Run Home Server",
                            subtitle = if (serverIsRunning) "Foreground service active" else "Server stopped",
                            checked = serverIsRunning,
                            onCheckedChange = { if (it) onStartServer() else onStopServer() }
                        )
                        SettingsSwitchRow(
                            title = "Start server automatically on boot",
                            subtitle = "Requires Boot Completed permission",
                            checked = settings.startOnBoot,
                            onCheckedChange = { onUpdateServerConfig(null, null, null, null, it) }
                        )
                        Text(
                            "Name: ${settings.serverDeviceName} • Port: ${settings.serverPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Storage: ${settings.serverStorageLabel.ifBlank { "App Private Vault" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showEditServerDialog = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("Edit Server & Relay") }
                            OutlinedButton(
                                onClick = { folderLauncher.launch(null) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Storage Folder") }
                        }
                    }
                }

                // Security
                item {
                    SettingsSectionCard("Security") {
                        Text(
                            "Encryption: TLS 1.2/1.3 + SHA-256 Pinning + Android Keystore AES-256-GCM",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showChangePinDialog = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("Change PIN") }
                            OutlinedButton(
                                onClick = {
                                    onRegeneratePairingCode()
                                    statusToast = "Pairing code rotated"
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("New Pair Code") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onRevokeAllSessions()
                                    statusToast = "All active sessions revoked"
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Revoke Sessions") }
                            if (settings.appMode == AppMode.CLIENT && settings.activeDeviceId.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        onDisconnectActiveDevice()
                                        statusToast = "Disconnected from active server"
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Disconnect") }
                            }
                        }
                        Button(
                            onClick = {
                                onLogoutAllClients()
                                statusToast = "Logged out and unpaired all clients"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = VaultCoral),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Logout All Clients")
                        }
                    }
                }

                // Transfers
                item {
                    SettingsSectionCard("Transfers") {
                        Text("Max concurrent transfers: ${settings.maxConcurrentTransfers}", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2, 3, 4).forEach { n ->
                                FilterChip(
                                    selected = settings.maxConcurrentTransfers == n,
                                    onClick = { onUpdateTransferSettings(n, null, null) },
                                    label = { Text("$n") }
                                )
                            }
                        }
                        SettingsSwitchRow(
                            title = "Wi-Fi only transfers",
                            subtitle = "Pause large uploads/downloads when on mobile data",
                            checked = settings.wifiOnlyTransfers,
                            onCheckedChange = { onUpdateTransferSettings(null, it, null) }
                        )
                        SettingsSwitchRow(
                            title = "Retry failed transfers automatically",
                            subtitle = "Verify SHA-256 checksum and resume partial chunks",
                            checked = settings.retryFailedTransfers,
                            onCheckedChange = { onUpdateTransferSettings(null, null, it) }
                        )
                    }
                }

                // Battery
                item {
                    SettingsSectionCard("Battery & Background Operation") {
                        Text(
                            "HomeVault uses a compliant Android Foreground Service (dataSync). On OEM phones, set Battery usage to 'Unrestricted' for uninterrupted 24/7 access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = onOpenBatterySettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Open Battery Optimization Settings")
                        }
                    }
                }

                // About & Diagnostics
                item {
                    SettingsSectionCard("About & Diagnostics") {
                        Text("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
                        Text("Device ID: ${settings.serverDeviceId.ifBlank { settings.clientId }}", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "Open Source Libraries: Jetpack Compose, Material 3, OkHttp, Media3 ExoPlayer, Room, DataStore, ZXing Core, Kotlinx Serialization",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    if (showChangePinDialog) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
            title = { Text("Change Server PIN") },
            text = {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("New PIN (min 4 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (pin.length >= 4) {
                        onChangePin(pin)
                        showChangePinDialog = false
                        statusToast = "PIN updated and existing sessions rotated"
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showChangePinDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showEditServerDialog) {
        var name by remember { mutableStateOf(settings.serverDeviceName) }
        var port by remember { mutableStateOf(settings.serverPort.toString()) }
        var relay by remember { mutableStateOf(settings.relayUrl) }
        var direct by remember { mutableStateOf(settings.directEndpointUrl) }
        AlertDialog(
            onDismissRequest = { showEditServerDialog = false },
            title = { Text("Server & Relay Configuration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Device Name") }, singleLine = true)
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(value = relay, onValueChange = { relay = it }, label = { Text("Relay URL") }, singleLine = true)
                    OutlinedTextField(value = direct, onValueChange = { direct = it }, label = { Text("Direct URL (Optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateServerConfig(name, port.toIntOrNull(), relay, direct, null)
                    showEditServerDialog = false
                    statusToast = "Server settings saved (restart server if port changed)"
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditServerDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
