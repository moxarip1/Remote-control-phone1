package com.example.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object Formatters {
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = bytes / 1024.0.pow(digitGroups.toDouble())
        return if (digitGroups == 0) {
            "$bytes B"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[digitGroups])
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 B/s"
        return "${formatBytes(bytesPerSec)}/s"
    }

    fun formatEta(seconds: Long): String {
        if (seconds <= 0L) return "--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun formatRelativeTime(epochMs: Long): String {
        if (epochMs <= 0L) return "Never"
        val diff = System.currentTimeMillis() - epochMs
        return when {
            diff < 15_000L -> "Just now"
            diff < 60_000L -> "${diff / 1000}s ago"
            diff < 3_600_000L -> "${diff / 60_000}m ago"
            diff < 86_400_000L -> "${diff / 3_600_000}h ago"
            else -> SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(epochMs))
        }
    }

    fun formatDateTime(epochMs: Long): String {
        if (epochMs <= 0L) return "--"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))
    }
}
