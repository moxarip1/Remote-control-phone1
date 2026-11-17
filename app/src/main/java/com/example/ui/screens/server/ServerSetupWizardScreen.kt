package com.example.ui.screens.server

import android.Manifest
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.server.ServerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSetupWizardScreen(
    state: ServerUiState,
    onBack: () -> Unit,
    onFinishSetup: (
        deviceName: String,
        storageUri: Uri?,
        storageLabel: String,
        pin: String,
        port: Int,
        relayUrl: String,
        startOnBoot: Boolean
    ) -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(1) }
    var deviceName by remember { mutableStateOf(state.settings.serverDeviceName.ifBlank { "Home Storage" }) }
    var selectedUri by remember {
        mutableStateOf(
            state.settings.serverStorageUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        )
    }
    var storageLabel by remember {
        mutableStateOf(state.settings.serverStorageLabel.ifBlank { "App Private Vault (or choose SAF folder)" })
    }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf(state.settings.serverPort.toString()) }
    var relayUrl by remember { mutableStateOf(state.settings.relayUrl) }
    var startOnBoot by remember { mutableStateOf(state.settings.startOnBoot) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            val doc = DocumentFile.fromTreeUri(context, uri)
            storageLabel = doc?.name ?: uri.lastPathSegment ?: "Selected Folder"
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onFinishSetup(
            deviceName,
            selectedUri,
            storageLabel,
            pin,
            portText.toIntOrNull() ?: 8443,
            relayUrl,
            startOnBoot
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Home Server Setup (Step $step/5)") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (step > 1) step-- else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LinearProgressIndicator(
                    progress = { step / 5f },
                    modifier = Modifier.fillMaxWidth()
                )

                when (step) {
                    1 -> {
                        StepHeader(
                            icon = Icons.Rounded.Storage,
                            title = "Name your Home Server",
                            subtitle = "Give this home storage phone an identifiable name on your devices list."
                        )
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it },
                            label = { Text("Device Name") },
                            placeholder = { Text("Home Storage") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wizard_device_name_input")
                        )
                    }

                    2 -> {
                        StepHeader(
                            icon = Icons.Rounded.FolderOpen,
                            title = "Choose Storage Folder",
                            subtitle = "HomeVault uses Android Storage Access Framework (SAF) with persistent access so only the folder you pick is shared."
                        )
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Selected Folder", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = storageLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { folderLauncher.launch(null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wizard_pick_folder_button")
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select Folder with SAF")
                        }
                    }

                    3 -> {
                        StepHeader(
                            icon = Icons.Rounded.Lock,
                            title = "Set a Strong Security PIN",
                            subtitle = "Your PIN is hashed with PBKDF2-HMAC-SHA256 and never stored in plaintext. Required for pairing and login."
                        )
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it; errorMsg = null },
                            label = { Text("Server PIN / Password (min 4 chars)") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wizard_pin_input")
                        )
                        OutlinedTextField(
                            value = confirmPin,
                            onValueChange = { confirmPin = it; errorMsg = null },
                            label = { Text("Confirm PIN / Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wizard_confirm_pin_input")
                        )
                    }

                    4 -> {
                        StepHeader(
                            icon = Icons.Rounded.QrCode2,
                            title = "Network & Relay Configuration",
                            subtitle = "Works on local Wi-Fi automatically. Add your self-hosted Relay URL for mobile data access outside home."
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("HTTPS TLS Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = relayUrl,
                            onValueChange = { relayUrl = it },
                            label = { Text("Self-Hosted Relay URL (Optional)") },
                            placeholder = { Text("https://relay.yourdomain.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Start server automatically on boot", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Resumes HomeVault after power outage or reboot",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = startOnBoot, onCheckedChange = { startOnBoot = it })
                        }
                    }

                    5 -> {
                        StepHeader(
                            icon = Icons.Rounded.CheckCircle,
                            title = "Ready to Launch",
                            subtitle = "Starting the server generates a TLS identity, starts the Foreground Service, and creates a live Pairing Code & QR."
                        )
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Device: $deviceName", style = MaterialTheme.typography.titleMedium)
                                Text("Folder: $storageLabel", style = MaterialTheme.typography.bodyMedium)
                                Text("TLS Port: ${portText.ifBlank { "8443" }}", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    "Relay: ${relayUrl.ifBlank { "LAN / Direct only" }}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }

                errorMsg?.let { msg ->
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (step > 1) {
                        OutlinedButton(
                            onClick = { step-- },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Previous")
                        }
                    }
                    Button(
                        onClick = {
                            when (step) {
                                1 -> {
                                    if (deviceName.isBlank()) {
                                        errorMsg = "Please enter a device name."
                                    } else {
                                        errorMsg = null
                                        step = 2
                                    }
                                }
                                2 -> step = 3
                                3 -> {
                                    when {
                                        pin.length < 4 && !state.settings.hasPinConfigured ->
                                            errorMsg = "PIN must be at least 4 characters."
                                        pin != confirmPin ->
                                            errorMsg = "PINs do not match."
                                        else -> {
                                            errorMsg = null
                                            step = 4
                                        }
                                    }
                                }
                                4 -> step = 5
                                5 -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        onFinishSetup(
                                            deviceName,
                                            selectedUri,
                                            storageLabel,
                                            pin,
                                            portText.toIntOrNull() ?: 8443,
                                            relayUrl,
                                            startOnBoot
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("wizard_next_or_start_button")
                    ) {
                        if (step == 5) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Start Server")
                        } else {
                            Text("Next")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
