package com.example.ui.screens.client

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.client.ClientUiState
import com.example.client.ConnectionHealth
import com.example.domain.model.ConnectionRoute
import com.example.domain.model.RemoteFileItem
import com.example.ui.components.StatusPulseBadge
import com.example.ui.components.StorageUsageBar
import com.example.ui.components.VaultErrorBanner
import com.example.ui.components.fileCategoryIcon
import com.example.ui.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientHomeScreen(
    state: ClientUiState,
    onRefresh: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    onUploadUris: (List<Uri>) -> Unit,
    onPreviewFile: (RemoteFileItem) -> Unit
) {
    var showUploadPickerDialog by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) onUploadUris(uris)
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onUploadUris(uris)
    }

    val activeServer = state.activeServer
    val storage = state.storageInfo
    val usedBytes = storage?.usedBytes ?: activeServer?.usedBytes ?: 0L
    val totalBytes = storage?.totalBytes ?: activeServer?.totalBytes ?: 0L
    val freeBytes = storage?.freeBytes ?: (totalBytes - usedBytes).coerceAtLeast(0L)
    val fileCount = storage?.fileCount ?: activeServer?.fileCount ?: 0

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("HomeVault Remote", fontWeight = FontWeight.Bold)
                    if (activeServer != null) {
                        Text(
                            text = "${activeServer.deviceName} • ${state.activeRoute.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            actions = {
                IconButton(
                    onClick = onOpenDevices,
                    modifier = Modifier.testTag("home_devices_button")
                ) {
                    Icon(Icons.Rounded.Devices, contentDescription = "My Devices")
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.testTag("home_refresh_button")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Reconnect")
                }
            }
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (state.statusBannerError != null) {
                    item {
                        VaultErrorBanner(
                            message = state.statusBannerError,
                            onRetry = onRefresh
                        )
                    }
                }

                if (activeServer == null) {
                    item {
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.img_empty_vault),
                                    contentDescription = "No paired home storage",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(RoundedCornerShape(16.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Text(
                                    "No Home Storage Paired",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Pair with your old home Android phone via QR code, pairing code, or automatic Wi-Fi discovery.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = onOpenDevices,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("pair_first_device_button")
                                ) {
                                    Icon(Icons.Rounded.AddLink, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Add Home Server")
                                }
                            }
                        }
                    }
                } else {
                    // Main Home Storage Status Card
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
                                    Column {
                                        Text(
                                            text = activeServer.deviceName.uppercase(),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Last connected: ${Formatters.formatRelativeTime(activeServer.lastConnectedEpochMs)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    StatusPulseBadge(
                                        health = state.connectionHealth,
                                        label = when (state.connectionHealth) {
                                            ConnectionHealth.ONLINE -> "ONLINE"
                                            ConnectionHealth.RECONNECTING -> "RETRYING"
                                            ConnectionHealth.OFFLINE -> "OFFLINE"
                                        }
                                    )
                                }

                                Text(
                                    text = "${Formatters.formatBytes(usedBytes)} / ${Formatters.formatBytes(totalBytes)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                StorageUsageBar(usedBytes = usedBytes, totalBytes = totalBytes)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    MetricPill("Free", Formatters.formatBytes(freeBytes))
                                    MetricPill("Files", "$fileCount")
                                    MetricPill(
                                        "Link",
                                        when (state.activeRoute) {
                                            ConnectionRoute.LAN -> "LAN ${state.latencyMs}ms"
                                            ConnectionRoute.DIRECT -> "Direct ${state.latencyMs}ms"
                                            ConnectionRoute.RELAY -> "Relay ${state.latencyMs}ms"
                                            ConnectionRoute.OFFLINE -> "Offline"
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Primary Quick Actions (Files, Upload, Download, Search, Transfers, Settings)
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                QuickActionCard(
                                    icon = Icons.Rounded.Folder,
                                    label = "Files",
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_action_files"),
                                    onClick = onOpenFiles
                                )
                                QuickActionCard(
                                    icon = Icons.Rounded.CloudUpload,
                                    label = "Upload",
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_action_upload"),
                                    onClick = { showUploadPickerDialog = true }
                                )
                                QuickActionCard(
                                    icon = Icons.Rounded.CloudDownload,
                                    label = "Download",
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_action_download"),
                                    onClick = onOpenFiles
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                QuickActionCard(
                                    icon = Icons.Rounded.Search,
                                    label = "Search",
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_action_search"),
                                    onClick = onOpenSearch
                                )
                                QuickActionCard(
                                    icon = Icons.Rounded.SwapVert,
                                    label = "Transfers",
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_action_transfers"),
                                    onClick = onOpenTransfers
                                )
                                QuickActionCard(
                                    icon = Icons.Rounded.Settings,
                                    label = "Settings",
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_action_settings"),
                                    onClick = onOpenSettings
                                )
                            }
                        }
                    }

                    // Recent files section
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Recent files",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = onOpenFiles) {
                                Text("Browse all")
                            }
                        }
                    }

                    val recentToShow = if (state.recentFiles.isNotEmpty()) {
                        state.recentFiles
                    } else {
                        state.files.filter { !it.isDirectory }.take(6)
                    }

                    if (recentToShow.isEmpty()) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Open Files or Upload photos and documents to start using your personal HomeVault.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    } else {
                        items(recentToShow, key = { it.path }) { item ->
                            Card(
                                onClick = { onPreviewFile(item) },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = fileCategoryIcon(false, item.category, item.mimeType),
                                        contentDescription = item.name,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${Formatters.formatBytes(item.sizeBytes)} • ${Formatters.formatRelativeTime(item.lastModifiedEpochMs)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    if (showUploadPickerDialog) {
        AlertDialog(
            onDismissRequest = { showUploadPickerDialog = false },
            title = { Text("Upload to Home Storage") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = {
                            showUploadPickerDialog = false
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Photos & Videos (Photo Picker)")
                    }
                    OutlinedButton(
                        onClick = {
                            showUploadPickerDialog = false
                            documentPickerLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.CloudUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Documents, APKs & Any Files")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUploadPickerDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
