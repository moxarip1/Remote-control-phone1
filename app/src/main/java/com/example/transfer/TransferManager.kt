package com.example.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import com.example.client.HomeVaultClientApi
import com.example.database.RecentFileDao
import com.example.database.RecentFileEntity
import com.example.database.SettingsStore
import com.example.database.TransferDao
import com.example.database.TransferEntity
import com.example.domain.model.FileCategory
import com.example.domain.model.RemoteFileItem
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferItem
import com.example.domain.model.TransferStatus
import com.example.domain.model.UploadInitRequest
import com.example.security.CryptoManager
import com.example.security.SecureVaultLogger
import com.example.storage.FileClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Streaming, chunked, resumable file transfer manager supporting large files without loading
 * them into RAM, SHA-256 verification before marking completion, Pause/Resume/Cancel, and
 * automatic retry on transient connection loss.
 */
class TransferManager(
    private val context: Context,
    private val clientApi: HomeVaultClientApi,
    private val transferDao: TransferDao,
    private val recentFileDao: RecentFileDao,
    private val settingsStore: SettingsStore,
    private val cryptoManager: CryptoManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pausedFlags = ConcurrentHashMap<String, Boolean>()

    private val chunkSizeBytes = 512 * 1024 // 512 KB streaming chunks

    val transfersFlow: Flow<List<TransferItem>> = transferDao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun enqueueUpload(
        deviceId: String,
        localUri: Uri,
        targetFolderPath: String
    ): String {
        val (fileName, fileSize) = queryUriMetadata(localUri)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = TransferEntity(
            id = id,
            deviceId = deviceId,
            fileName = fileName,
            remotePath = if (targetFolderPath.isEmpty()) fileName else "$targetFolderPath/$fileName",
            localUri = localUri.toString(),
            direction = TransferDirection.UPLOAD.name,
            totalBytes = fileSize,
            bytesTransferred = 0L,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            expectedSha256 = "",
            status = TransferStatus.QUEUED.name,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        transferDao.upsert(entity)
        launchTransferJob(id)
        return id
    }

    suspend fun enqueueDownload(
        deviceId: String,
        remoteFile: RemoteFileItem,
        destinationUri: Uri
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = TransferEntity(
            id = id,
            deviceId = deviceId,
            fileName = remoteFile.name,
            remotePath = remoteFile.path,
            localUri = destinationUri.toString(),
            direction = TransferDirection.DOWNLOAD.name,
            totalBytes = remoteFile.sizeBytes,
            bytesTransferred = 0L,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            expectedSha256 = "",
            status = TransferStatus.QUEUED.name,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        transferDao.upsert(entity)
        launchTransferJob(id)
        return id
    }

    fun pauseTransfer(transferId: String) {
        pausedFlags[transferId] = true
        activeJobs[transferId]?.cancel()
        scope.launch {
            val current = transferDao.getById(transferId) ?: return@launch
            transferDao.updateProgress(
                id = transferId,
                bytes = current.bytesTransferred,
                speed = 0L,
                eta = 0L,
                status = TransferStatus.PAUSED.name,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun resumeTransfer(transferId: String) {
        pausedFlags[transferId] = false
        launchTransferJob(transferId)
    }

    fun cancelTransfer(transferId: String) {
        pausedFlags[transferId] = true
        activeJobs.remove(transferId)?.cancel()
        scope.launch {
            val current = transferDao.getById(transferId) ?: return@launch
            transferDao.updateProgress(
                id = transferId,
                bytes = current.bytesTransferred,
                speed = 0L,
                eta = 0L,
                status = TransferStatus.CANCELLED.name,
                errorMessage = "Cancelled by user",
                updatedAt = System.currentTimeMillis()
            )
            stagingDownloadFile(transferId).delete()
        }
    }

    suspend fun clearHistory() {
        transferDao.clearHistory()
    }

    private fun launchTransferJob(transferId: String) {
        activeJobs[transferId]?.cancel()
        pausedFlags[transferId] = false
        val job = scope.launch {
            val settings = settingsStore.settingsFlow.first()
            val maxRetries = if (settings.retryFailedTransfers) 3 else 1
            var attempt = 0
            while (attempt < maxRetries) {
                attempt++
                val entity = transferDao.getById(transferId) ?: return@launch
                if (pausedFlags[transferId] == true ||
                    entity.status == TransferStatus.CANCELLED.name ||
                    entity.status == TransferStatus.COMPLETED.name
                ) {
                    return@launch
                }

                if (settings.wifiOnlyTransfers && !isWifiConnected()) {
                    transferDao.updateProgress(
                        id = transferId,
                        bytes = entity.bytesTransferred,
                        speed = 0L,
                        eta = 0L,
                        status = TransferStatus.PAUSED.name,
                        errorMessage = "Waiting for Wi-Fi connection",
                        updatedAt = System.currentTimeMillis()
                    )
                    return@launch
                }

                try {
                    if (entity.direction == TransferDirection.UPLOAD.name) {
                        executeChunkedUpload(entity)
                    } else {
                        executeResumableDownload(entity)
                    }
                    return@launch
                } catch (_: CancellationException) {
                    return@launch
                } catch (e: Exception) {
                    SecureVaultLogger.w("Transfer", "Transfer $transferId attempt $attempt failed: ${e.message}")
                    if (attempt >= maxRetries) {
                        val latest = transferDao.getById(transferId)
                        transferDao.updateProgress(
                            id = transferId,
                            bytes = latest?.bytesTransferred ?: 0L,
                            speed = 0L,
                            eta = 0L,
                            status = TransferStatus.FAILED.name,
                            errorMessage = e.message ?: "Transfer failed. Tap Retry to resume.",
                            updatedAt = System.currentTimeMillis()
                        )
                    } else {
                        delay(1500L * attempt)
                    }
                }
            }
        }
        activeJobs[transferId] = job
    }

    private suspend fun executeChunkedUpload(entity: TransferEntity) {
        val uri = Uri.parse(entity.localUri)
        val clientId = settingsStore.getOrCreateClientId()
        val sha256 = context.contentResolver.openInputStream(uri)?.use {
            cryptoManager.sha256Hex(it)
        } ?: throw IllegalStateException("Cannot read selected file.")

        val targetFolder = if (entity.remotePath.contains('/')) {
            entity.remotePath.substringBeforeLast('/')
        } else ""

        val initResp = clientApi.initUpload(
            deviceId = entity.deviceId,
            clientId = clientId,
            request = UploadInitRequest(
                targetFolderPath = targetFolder,
                fileName = entity.fileName,
                totalBytes = entity.totalBytes,
                chunkSize = chunkSizeBytes,
                expectedSha256 = sha256,
                mimeType = FileClassifier.inferMimeType(entity.fileName)
            )
        )

        var offset = initResp.receivedBytes.coerceAtMost(entity.totalBytes)
        val startTime = System.currentTimeMillis()
        var sessionBytes = 0L

        context.contentResolver.openInputStream(uri)?.use { input ->
            skipFully(input, offset)
            val buffer = ByteArray(chunkSizeBytes)
            while (offset < entity.totalBytes) {
                if (pausedFlags[entity.id] == true) return
                val read = readChunk(input, buffer)
                if (read <= 0) break
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                clientApi.uploadChunk(entity.deviceId, clientId, initResp.uploadId, offset, chunk)
                offset += read
                sessionBytes += read

                val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.2)
                val speed = (sessionBytes / elapsedSec).toLong()
                val remaining = (entity.totalBytes - offset).coerceAtLeast(0L)
                val eta = if (speed > 0) remaining / speed else 0L

                transferDao.updateProgress(
                    id = entity.id,
                    bytes = offset,
                    speed = speed,
                    eta = eta,
                    status = TransferStatus.RUNNING.name,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            }
        } ?: throw IllegalStateException("Unable to open local file stream.")

        transferDao.updateProgress(
            id = entity.id,
            bytes = entity.totalBytes,
            speed = 0L,
            eta = 0L,
            status = TransferStatus.VERIFYING.name,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )

        val uploadedItem = clientApi.completeUpload(entity.deviceId, clientId, initResp.uploadId, sha256)
        recordRecent(entity.deviceId, uploadedItem)
        transferDao.updateProgress(
            id = entity.id,
            bytes = entity.totalBytes,
            speed = 0L,
            eta = 0L,
            status = TransferStatus.COMPLETED.name,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun executeResumableDownload(entity: TransferEntity) {
        val clientId = settingsStore.getOrCreateClientId()
        val tempFile = stagingDownloadFile(entity.id)
        var existingBytes = if (tempFile.exists()) tempFile.length() else 0L
        if (existingBytes > entity.totalBytes && entity.totalBytes > 0) {
            tempFile.delete()
            existingBytes = 0L
        }

        val resp = clientApi.openDownloadResponse(
            deviceId = entity.deviceId,
            clientId = clientId,
            remotePath = entity.remotePath,
            startByteOffset = existingBytes
        )

        val serverExpectedSha = resp.header("X-File-Sha256").orEmpty().lowercase()
        val isPartial = resp.code == 206
        if (!isPartial && existingBytes > 0L) {
            tempFile.delete()
            existingBytes = 0L
        }

        val startTime = System.currentTimeMillis()
        var sessionBytes = 0L
        var totalWritten = existingBytes

        resp.use { response ->
            val bodyStream = response.body?.byteStream()
                ?: throw IllegalStateException("Empty download stream.")
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.seek(existingBytes)
                val buf = ByteArray(64 * 1024)
                var lastUiUpdate = 0L
                while (true) {
                    if (pausedFlags[entity.id] == true) return
                    val read = bodyStream.read(buf)
                    if (read <= 0) break
                    raf.write(buf, 0, read)
                    totalWritten += read
                    sessionBytes += read

                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate >= 250L) {
                        lastUiUpdate = now
                        val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.2)
                        val speed = (sessionBytes / elapsedSec).toLong()
                        val remaining = (entity.totalBytes - totalWritten).coerceAtLeast(0L)
                        val eta = if (speed > 0) remaining / speed else 0L
                        transferDao.updateProgress(
                            id = entity.id,
                            bytes = totalWritten,
                            speed = speed,
                            eta = eta,
                            status = TransferStatus.RUNNING.name,
                            errorMessage = null,
                            updatedAt = now
                        )
                    }
                }
            }
        }

        transferDao.updateProgress(
            id = entity.id,
            bytes = totalWritten,
            speed = 0L,
            eta = 0L,
            status = TransferStatus.VERIFYING.name,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )

        if (entity.totalBytes > 0 && tempFile.length() != entity.totalBytes) {
            throw IllegalStateException("Incomplete download (${tempFile.length()} / ${entity.totalBytes} bytes).")
        }

        if (serverExpectedSha.isNotBlank()) {
            val actualSha = tempFile.inputStream().use { cryptoManager.sha256Hex(it) }
            if (actualSha != serverExpectedSha) {
                tempFile.delete()
                throw SecurityException("Checksum verification failed. Retrying...")
            }
        }

        // Write verified file to the user-selected destination URI
        val destUri = Uri.parse(entity.localUri)
        if (destUri.scheme == "file") {
            val destFile = File(destUri.path!!)
            destFile.parentFile?.mkdirs()
            tempFile.inputStream().use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        } else {
            context.contentResolver.openOutputStream(destUri, "w")?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
                output.flush()
            } ?: throw IllegalStateException("Cannot write to chosen download location.")
        }

        tempFile.delete()
        transferDao.updateProgress(
            id = entity.id,
            bytes = totalWritten,
            speed = 0L,
            eta = 0L,
            status = TransferStatus.COMPLETED.name,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun recordRecent(deviceId: String, item: RemoteFileItem) {
        recentFileDao.upsert(
            RecentFileEntity(
                id = item.id,
                deviceId = deviceId,
                name = item.name,
                path = item.path,
                parentPath = item.parentPath,
                sizeBytes = item.sizeBytes,
                mimeType = item.mimeType,
                category = item.category.name,
                lastModifiedEpochMs = item.lastModifiedEpochMs,
                accessedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    private fun stagingDownloadFile(transferId: String): File {
        val dir = File(context.cacheDir, "hv_downloads_part").apply { mkdirs() }
        return File(dir, "$transferId.part")
    }

    private fun queryUriMetadata(uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            return f.name.ifBlank { "upload.bin" } to f.length()
        }
        var name = "upload_${System.currentTimeMillis()}"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        if (size <= 0L) {
            size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        }
        return name to size.coerceAtLeast(0L)
    }

    private fun skipFully(input: java.io.InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun readChunk(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val r = input.read(buffer, total, buffer.size - total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
