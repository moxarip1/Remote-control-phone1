package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.client.ConnectionHealth
import com.example.domain.model.FileCategory
import com.example.domain.model.FileTypeUsage
import com.example.ui.theme.VaultAmber
import com.example.ui.theme.VaultCoral
import com.example.ui.theme.VaultCyan
import com.example.ui.theme.VaultEmerald
import com.example.ui.theme.VaultIndigo

@Composable
fun StatusPulseBadge(
    health: ConnectionHealth,
    label: String,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = when (health) {
            ConnectionHealth.ONLINE -> VaultEmerald
            ConnectionHealth.RECONNECTING -> VaultAmber
            ConnectionHealth.OFFLINE -> VaultCoral
        },
        label = "status_color"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(if (health == ConnectionHealth.OFFLINE) 1f else scale)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun StorageUsageBar(
    usedBytes: Long,
    totalBytes: Long,
    modifier: Modifier = Modifier
) {
    val ratio = if (totalBytes > 0L) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val barColor = when {
        ratio > 0.9f -> VaultCoral
        ratio > 0.75f -> VaultAmber
        else -> MaterialTheme.colorScheme.primary
    }
    LinearProgressIndicator(
        progress = { ratio },
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(6.dp)),
        color = barColor,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeCap = StrokeCap.Round
    )
}

@Composable
fun StorageDonutChart(
    usedBytes: Long,
    totalBytes: Long,
    breakdown: List<FileTypeUsage>,
    modifier: Modifier = Modifier
) {
    val pct = if (totalBytes > 0L) ((usedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100) else 0
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(modifier = modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(172.dp)) {
            val strokeWidth = 22.dp.toPx()
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            if (totalBytes > 0L) {
                val totalUsedSweep = ((usedBytes.toFloat() / totalBytes.toFloat()) * 360f).coerceIn(4f, 360f)
                if (breakdown.isEmpty()) {
                    drawArc(
                        color = primaryColor,
                        startAngle = -90f,
                        sweepAngle = totalUsedSweep,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                    )
                } else {
                    val sumCatBytes = breakdown.sumOf { it.bytesUsed }.coerceAtLeast(1L)
                    var start = -90f
                    breakdown.forEach { item ->
                        val slice = (item.bytesUsed.toFloat() / sumCatBytes.toFloat()) * totalUsedSweep
                        if (slice > 1f) {
                            drawArc(
                                color = categoryColor(item.category),
                                startAngle = start,
                                sweepAngle = slice,
                                useCenter = false,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                            )
                            start += slice
                        }
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$pct%",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Used",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun categoryColor(category: FileCategory): Color = when (category) {
    FileCategory.IMAGE -> VaultCyan
    FileCategory.VIDEO -> VaultIndigo
    FileCategory.AUDIO -> VaultEmerald
    FileCategory.DOCUMENT -> VaultAmber
    FileCategory.ARCHIVE -> Color(0xFFEC4899)
    FileCategory.APK -> Color(0xFF84CC16)
    FileCategory.TEXT -> Color(0xFF38BDF8)
    FileCategory.OTHER -> Color(0xFF94A3B8)
}

fun fileCategoryIcon(isDirectory: Boolean, category: FileCategory, mimeType: String): ImageVector {
    if (isDirectory) return Icons.Rounded.Folder
    if (mimeType == "application/pdf") return Icons.Rounded.PictureAsPdf
    return when (category) {
        FileCategory.IMAGE -> Icons.Rounded.Image
        FileCategory.VIDEO -> Icons.Rounded.Movie
        FileCategory.AUDIO -> Icons.Rounded.AudioFile
        FileCategory.DOCUMENT -> Icons.Rounded.Description
        FileCategory.ARCHIVE -> Icons.Rounded.FolderZip
        FileCategory.APK -> Icons.Rounded.Android
        FileCategory.TEXT -> Icons.Rounded.Description
        FileCategory.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

@Composable
fun VaultErrorBanner(
    message: String,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VaultCoral.copy(alpha = 0.14f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = "Connection warning",
                tint = VaultCoral
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Retry",
                        tint = VaultCoral
                    )
                }
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
