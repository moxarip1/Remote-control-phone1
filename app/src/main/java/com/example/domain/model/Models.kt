package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class AppMode {
    UNSET,
    CLIENT,
    SERVER
}

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Serializable
enum class ConnectionRoute {
    LAN,
    DIRECT,
    RELAY,
    OFFLINE
}

@Serializable
enum class FileCategory {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    APK,
    TEXT,
    OTHER
}

@Serializable
data class RemoteFileItem(
    val id: String,
    val name: String,
    val path: String,
    val parentPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mimeType: String,
    val lastModifiedEpochMs: Long,
    val hasThumbnail: Boolean = false,
    val category: FileCategory = FileCategory.OTHER
)

@Serializable
data class FileListResponse(
    val currentPath: String,
    val items: List<RemoteFileItem>,
    val totalCount: Int,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean
)

@Serializable
data class FileTypeUsage(
    val category: FileCategory,
    val bytesUsed: Long,
    val fileCount: Int
)

@Serializable
data class StorageInfo(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val fileCount: Int,
    val folderCount: Int,
    val largestFiles: List<RemoteFileItem> = emptyList(),
    val typeBreakdown: List<FileTypeUsage> = emptyList()
)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val modelName: String,
    val appVersion: String,
    val localIp: String,
    val port: Int,
    val tlsFingerprint: String,
    val storagePathLabel: String,
    val serverOnline: Boolean,
    val relayConnected: Boolean,
    val relayUrl: String,
    val uptimeSeconds: Long,
    val connectedClients: Int,
    val lastActivityEpochMs: Long,
    val todayTransfers: Int
)

@Serializable
data class PairingPayload(
    val version: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val localIp: String,
    val port: Int,
    val directUrl: String = "",
    val relayUrl: String = "",
    val pairingCode: String,
    val tlsFingerprint: String,
    val expiresAtEpochMs: Long
)

@Serializable
data class PairRequest(
    val pairingCode: String,
    val pin: String,
    val clientId: String,
    val clientName: String,
    val nonce: String,
    val timestampMs: Long
)

@Serializable
data class LoginRequest(
    val deviceId: String,
    val clientId: String,
    val clientName: String,
    val pin: String,
    val nonce: String,
    val timestampMs: Long
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
    val clientId: String,
    val nonce: String,
    val timestampMs: Long
)

@Serializable
data class AuthSessionResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val sessionSecretBase64: String,
    val deviceInfo: DeviceInfo
)

@Serializable
data class CreateFolderRequest(
    val parentPath: String,
    val folderName: String
)

@Serializable
data class DeleteFilesRequest(
    val paths: List<String>
)

@Serializable
data class RenameFileRequest(
    val path: String,
    val newName: String
)

@Serializable
data class MoveOrCopyRequest(
    val sourcePaths: List<String>,
    val destinationFolderPath: String
)

@Serializable
data class UploadInitRequest(
    val targetFolderPath: String,
    val fileName: String,
    val totalBytes: Long,
    val chunkSize: Int,
    val expectedSha256: String,
    val mimeType: String = "application/octet-stream"
)

@Serializable
data class UploadInitResponse(
    val uploadId: String,
    val receivedBytes: Long,
    val chunkSize: Int
)

@Serializable
data class UploadCompleteRequest(
    val uploadId: String,
    val expectedSha256: String
)

@Serializable
data class ApiActionResponse(
    val success: Boolean,
    val message: String,
    val affectedPath: String? = null
)

@Serializable
data class ApiErrorResponse(
    val code: String,
    val message: String
)

@Serializable
enum class TransferDirection {
    UPLOAD,
    DOWNLOAD
}

@Serializable
enum class TransferStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TransferItem(
    val id: String,
    val deviceId: String,
    val fileName: String,
    val remotePath: String,
    val localUri: String,
    val direction: TransferDirection,
    val totalBytes: Long,
    val bytesTransferred: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val expectedSha256: String,
    val status: TransferStatus,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

data class PairedServer(
    val deviceId: String,
    val deviceName: String,
    val localIp: String,
    val port: Int,
    val directUrl: String,
    val relayUrl: String,
    val tlsFingerprint: String,
    val lastRoute: ConnectionRoute,
    val isOnline: Boolean,
    val lastConnectedEpochMs: Long,
    val usedBytes: Long,
    val totalBytes: Long,
    val fileCount: Int
)

data class DiscoveredLanServer(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val tlsFingerprint: String
)
