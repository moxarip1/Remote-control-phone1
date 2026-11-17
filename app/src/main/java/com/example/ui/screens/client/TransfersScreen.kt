package com.example.ui.screens.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.client.ClientUiState
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStatus
import com.example.ui.theme.VaultAmber
import com.example.ui.theme.VaultCoral
import com.example.ui.theme.VaultEmerald
import com.example.ui.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    state: ClientUiState,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Transfers") },
            actions = {
                if (state.transfers.any {
                        it.status in setOf(
                            TransferStatus.COMPLETED,
                            TransferStatus.CANCELLED,
                            TransferStatus.FAILED
                        )
                    }
                ) {
                    IconButton(
                        onClick = onClearHistory,
                        modifier = Modifier.testTag("clear_transfers_button")
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear history")
                    }
                }
            }
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (state.transfers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No active or recent transfers.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.transfers, key = { it.id }) { item ->
                        TransferCard(
                            item = item,
                            onPause = { onPause(item.id) },
                            onResume = { onResume(item.id) },
                            onCancel = { onCancel(item.id) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TransferCard(
    item: TransferItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val pct = if (item.totalBytes > 0) {
        ((item.bytesTransferred.toDouble() / item.totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
    } else if (item.status == TransferStatus.COMPLETED) 100 else 0

    val statusColor = when (item.status) {
        TransferStatus.COMPLETED -> VaultEmerald
        TransferStatus.FAILED, TransferStatus.CANCELLED -> VaultCoral
        TransferStatus.PAUSED -> VaultAmber
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (item.direction == TransferDirection.UPLOAD) Icons.Rounded.CloudUpload
                    else Icons.Rounded.CloudDownload,
                    contentDescription = item.direction.name,
                    tint = statusColor
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when (item.status) {
                            TransferStatus.COMPLETED -> "Completed ✓ • ${Formatters.formatBytes(item.totalBytes)}"
                            TransferStatus.VERIFYING -> "Verifying SHA-256 checksum..."
                            TransferStatus.FAILED -> item.errorMessage ?: "Transfer failed"
                            TransferStatus.PAUSED -> item.errorMessage ?: "Paused ($pct%)"
                            TransferStatus.CANCELLED -> "Cancelled"
                            else -> "${Formatters.formatBytes(item.bytesTransferred)} / ${Formatters.formatBytes(item.totalBytes)} • $pct%"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
                if (item.status == TransferStatus.COMPLETED) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Completed", tint = VaultEmerald)
                }
            }

            if (item.status in setOf(
                    TransferStatus.RUNNING,
                    TransferStatus.QUEUED,
                    TransferStatus.PAUSED,
                    TransferStatus.VERIFYING
                )
            ) {
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = Formatters.formatSpeed(item.speedBytesPerSec),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "ETA ${Formatters.formatEta(item.etaSeconds)}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item.status == TransferStatus.PAUSED) {
                        FilledTonalButton(onClick = onResume, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume")
                        }
                    } else {
                        FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Pause, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause")
                        }
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Cancel, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel")
                    }
                }
            } else if (item.status == TransferStatus.FAILED) {
                FilledTonalButton(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Retry Transfer")
                }
            }
        }
    }
}
