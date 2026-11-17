package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.ConnectionRoute
import com.example.domain.model.FileCategory
import com.example.domain.model.PairedServer
import com.example.domain.model.RemoteFileItem
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStatus

@Entity(tableName = "paired_servers")
data class PairedServerEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val localIp: String,
    val port: Int,
    val directUrl: String,
    val relayUrl: String,
    val tlsFingerprint: String,
    val encryptedAccessToken: String,
    val encryptedRefreshToken: String,
    val encryptedSessionSecret: String,
    val tokenExpiresAtEpochMs: Long,
    val lastRoute: String,
    val isOnline: Boolean,
    val lastConnectedEpochMs: Long,
    val usedBytes: Long,
    val totalBytes: Long,
    val fileCount: Int
) {
    fun toDomain(): PairedServer = PairedServer(
        deviceId = deviceId,
        deviceName = deviceName,
        localIp = localIp,
        port = port,
        directUrl = directUrl,
        relayUrl = relayUrl,
        tlsFingerprint = tlsFingerprint,
        lastRoute = runCatching { ConnectionRoute.valueOf(lastRoute) }.getOrDefault(ConnectionRoute.OFFLINE),
        isOnline = isOnline,
        lastConnectedEpochMs = lastConnectedEpochMs,
        usedBytes = usedBytes,
        totalBytes = totalBytes,
        fileCount = fileCount
    )
}

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val fileName: String,
    val remotePath: String,
    val localUri: String,
    val direction: String,
    val totalBytes: Long,
    val bytesTransferred: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val expectedSha256: String,
    val status: String,
    val errorMessage: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    fun toDomain(): TransferItem = TransferItem(
        id = id,
        deviceId = deviceId,
        fileName = fileName,
        remotePath = remotePath,
        localUri = localUri,
        direction = TransferDirection.valueOf(direction),
        totalBytes = totalBytes,
        bytesTransferred = bytesTransferred,
        speedBytesPerSec = speedBytesPerSec,
        etaSeconds = etaSeconds,
        expectedSha256 = expectedSha256,
        status = TransferStatus.valueOf(status),
        errorMessage = errorMessage,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs
    )
}

@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val name: String,
    val path: String,
    val parentPath: String,
    val sizeBytes: Long,
    val mimeType: String,
    val category: String,
    val lastModifiedEpochMs: Long,
    val accessedAtEpochMs: Long
) {
    fun toDomain(): RemoteFileItem = RemoteFileItem(
        id = id,
        name = name,
        path = path,
        parentPath = parentPath,
        isDirectory = false,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        lastModifiedEpochMs = lastModifiedEpochMs,
        hasThumbnail = category == FileCategory.IMAGE.name || category == FileCategory.VIDEO.name,
        category = runCatching { FileCategory.valueOf(category) }.getOrDefault(FileCategory.OTHER)
    )
}

@Entity(tableName = "server_activity")
data class ServerActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long,
    val eventType: String,
    val clientName: String,
    val detail: String
)
