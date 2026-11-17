package com.example.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.example.domain.model.FileCategory
import com.example.domain.model.FileListResponse
import com.example.domain.model.FileTypeUsage
import com.example.domain.model.RemoteFileItem
import com.example.domain.model.StorageInfo
import com.example.security.CryptoManager
import com.example.security.PathSanitizer
import com.example.security.PathTraversalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Storage Access Framework (SAF) storage provider backed by a persisted tree URI
 * (`Intent.ACTION_OPEN_DOCUMENT_TREE`). Staging chunks are kept in the app's private cache
 * directory until size + SHA-256 verification succeeds, preventing partial or corrupt files
 * from ever landing in the user's shared folder.
 */
class SafStorageProvider(
    private val context: Context,
    private val treeUri: Uri,
    private val cryptoManager: CryptoManager = CryptoManager()
) : StorageProvider {

    private val rootDoc: DocumentFile
        get() = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Selected storage folder is no longer accessible.")

    override val storageLabel: String
        get() = rootDoc.name ?: "Home Storage"

    private data class SafUploadSession(
        val uploadId: String,
        val targetFolderPath: String,
        val fileName: String,
        val totalBytes: Long,
        val expectedSha256: String,
        val mimeType: String,
        val stagingFile: File
    )

    private val stagingDir: File by lazy {
        File(context.cacheDir, "hv_upload_staging").apply { mkdirs() }
    }
    private val uploadSessions = ConcurrentHashMap<String, SafUploadSession>()

    private fun resolveDocument(relativePath: String): DocumentFile? {
        val clean = PathSanitizer.sanitizeRelativePath(relativePath)
        var current: DocumentFile = rootDoc
        if (clean.isEmpty()) return current
        for (segment in clean.split('/')) {
            if (segment.isEmpty()) continue
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    override suspend fun listFiles(relativePath: String, offset: Int, limit: Int): FileListResponse =
        withContext(Dispatchers.IO) {
            val clean = PathSanitizer.sanitizeRelativePath(relativePath)
            val dir = resolveDocument(clean)
            if (dir == null || !dir.isDirectory) {
                return@withContext FileListResponse(clean, emptyList(), 0, offset, limit, false)
            }
            val children = dir.listFiles()
                .filter { !it.name.isNullOrBlank() }
                .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name!!.lowercase() })

            val safeOffset = offset.coerceAtLeast(0)
            val safeLimit = limit.coerceIn(1, 500)
            val page = children.drop(safeOffset).take(safeLimit).map { doc ->
                toRemoteFileItem(doc, clean)
            }
            FileListResponse(
                currentPath = clean,
                items = page,
                totalCount = children.size,
                offset = safeOffset,
                limit = safeLimit,
                hasMore = safeOffset + page.size < children.size
            )
        }

    override suspend fun getFileInfo(relativePath: String): RemoteFileItem? = withContext(Dispatchers.IO) {
        val clean = PathSanitizer.sanitizeRelativePath(relativePath)
        val doc = resolveDocument(clean) ?: return@withContext null
        val parent = if (clean.contains('/')) clean.substringBeforeLast('/') else ""
        toRemoteFileItem(doc, parent)
    }

    override suspend fun createFolder(parentRelativePath: String, folderName: String): RemoteFileItem =
        withContext(Dispatchers.IO) {
            val cleanParent = PathSanitizer.sanitizeRelativePath(parentRelativePath)
            val cleanName = PathSanitizer.sanitizeSingleName(folderName)
            val parentDoc = resolveDocument(cleanParent)
                ?: throw IllegalArgumentException("Parent folder does not exist.")
            val existing = parentDoc.findFile(cleanName)
            val created = if (existing != null && existing.isDirectory) {
                existing
            } else {
                parentDoc.createDirectory(cleanName)
                    ?: throw IllegalStateException("Could not create folder '$cleanName'.")
            }
            toRemoteFileItem(created, cleanParent)
        }

    override suspend fun deletePaths(relativePaths: List<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (raw in relativePaths) {
            val clean = PathSanitizer.sanitizeRelativePath(raw)
            if (clean.isEmpty()) throw PathTraversalException("Cannot delete root storage folder.")
            val target = resolveDocument(clean) ?: continue
            if (target.delete()) count++
        }
        count
    }

    override suspend fun rename(relativePath: String, newName: String): RemoteFileItem =
        withContext(Dispatchers.IO) {
            val clean = PathSanitizer.sanitizeRelativePath(relativePath)
            val (parentPath, _) = PathSanitizer.splitParentAndName(clean)
            val safeNewName = PathSanitizer.sanitizeSingleName(newName)
            val target = resolveDocument(clean) ?: throw IllegalArgumentException("File does not exist.")
            if (!target.renameTo(safeNewName)) {
                throw IllegalStateException("Failed to rename '${target.name}'.")
            }
            val updated = resolveDocument(if (parentPath.isEmpty()) safeNewName else "$parentPath/$safeNewName")
                ?: target
            toRemoteFileItem(updated, parentPath)
        }

    override suspend fun copy(sourcePaths: List<String>, destinationFolderPath: String): Int =
        withContext(Dispatchers.IO) {
            val cleanDest = PathSanitizer.sanitizeRelativePath(destinationFolderPath)
            val destDir = resolveDocument(cleanDest)
                ?: throw IllegalArgumentException("Destination folder does not exist.")
            if (!destDir.isDirectory) throw IllegalArgumentException("Destination is not a folder.")
            var count = 0
            for (raw in sourcePaths) {
                val cleanSrc = PathSanitizer.sanitizeRelativePath(raw)
                val srcDoc = resolveDocument(cleanSrc) ?: continue
                copyDocumentRecursively(srcDoc, destDir)
                count++
            }
            count
        }

    override suspend fun move(sourcePaths: List<String>, destinationFolderPath: String): Int =
        withContext(Dispatchers.IO) {
            val cleanDest = PathSanitizer.sanitizeRelativePath(destinationFolderPath)
            val destDir = resolveDocument(cleanDest)
                ?: throw IllegalArgumentException("Destination folder does not exist.")
            if (!destDir.isDirectory) throw IllegalArgumentException("Destination is not a folder.")
            var count = 0
            for (raw in sourcePaths) {
                val cleanSrc = PathSanitizer.sanitizeRelativePath(raw)
                if (cleanSrc.isEmpty()) continue
                val srcDoc = resolveDocument(cleanSrc) ?: continue
                copyDocumentRecursively(srcDoc, destDir)
                if (srcDoc.delete()) count++
            }
            count
        }

    override suspend fun search(
        query: String,
        categoryFilter: FileCategory?,
        maxResults: Int
    ): List<RemoteFileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RemoteFileItem>()
        fun walk(dir: DocumentFile, relDir: String) {
            if (results.size >= maxResults) return
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                val childRel = if (relDir.isEmpty()) name else "$relDir/$name"
                val item = toRemoteFileItem(child, relDir)
                val matchesQuery = FileClassifier.matchesSearchPattern(name, childRel, query)
                val matchesCat = categoryFilter == null || (!child.isDirectory && item.category == categoryFilter)
                if (matchesQuery && matchesCat) {
                    results.add(item)
                    if (results.size >= maxResults) return
                }
                if (child.isDirectory) {
                    walk(child, childRel)
                }
            }
        }
        walk(rootDoc, "")
        results
    }

    override suspend fun getStorageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val used = (total - free).coerceAtLeast(0L)

        var fileCount = 0
        var folderCount = 0
        val categoryMap = mutableMapOf<FileCategory, Pair<Long, Int>>()
        val allFiles = mutableListOf<RemoteFileItem>()

        fun walk(dir: DocumentFile, relDir: String, depth: Int) {
            if (depth > 8) return
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    folderCount++
                    val nextRel = if (relDir.isEmpty()) name else "$relDir/$name"
                    walk(child, nextRel, depth + 1)
                } else {
                    fileCount++
                    val len = child.length().coerceAtLeast(0L)
                    val item = toRemoteFileItem(child, relDir)
                    allFiles.add(item)
                    val prev = categoryMap[item.category] ?: (0L to 0)
                    categoryMap[item.category] = (prev.first + len) to (prev.second + 1)
                }
            }
        }
        walk(rootDoc, "", 0)

        StorageInfo(
            totalBytes = total,
            usedBytes = used,
            freeBytes = free,
            fileCount = fileCount,
            folderCount = folderCount,
            largestFiles = allFiles.sortedByDescending { it.sizeBytes }.take(8),
            typeBreakdown = categoryMap.entries
                .map { FileTypeUsage(it.key, it.value.first, it.value.second) }
                .sortedByDescending { it.bytesUsed }
        )
    }

    override suspend fun openReadStream(relativePath: String): InputStream? = withContext(Dispatchers.IO) {
        val doc = resolveDocument(relativePath) ?: return@withContext null
        if (doc.isDirectory) return@withContext null
        context.contentResolver.openInputStream(doc.uri)
    }

    override suspend fun computeFileSha256(relativePath: String): String? = withContext(Dispatchers.IO) {
        openReadStream(relativePath)?.use { cryptoManager.sha256Hex(it) }
    }

    override suspend fun getThumbnailBytes(relativePath: String, maxDimensionPx: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val doc = resolveDocument(relativePath) ?: return@withContext null
            if (doc.isDirectory) return@withContext null
            val name = doc.name ?: return@withContext null
            val category = FileClassifier.classify(name, doc.type ?: FileClassifier.inferMimeType(name))
            try {
                val bitmap: Bitmap? = when (category) {
                    FileCategory.IMAGE -> {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(doc.uri)?.use {
                            BitmapFactory.decodeStream(it, null, opts)
                        }
                        var sample = 1
                        while (opts.outWidth / sample > maxDimensionPx * 2 || opts.outHeight / sample > maxDimensionPx * 2) {
                            sample *= 2
                        }
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                        context.contentResolver.openInputStream(doc.uri)?.use {
                            BitmapFactory.decodeStream(it, null, decodeOpts)
                        }
                    }
                    FileCategory.VIDEO -> {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, doc.uri)
                            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } finally {
                            retriever.release()
                        }
                    }
                    else -> null
                }
                bitmap?.let { bmp ->
                    val w = bmp.width
                    val h = bmp.height
                    val scaled = if (w > maxDimensionPx || h > maxDimensionPx) {
                        val ratio = minOf(maxDimensionPx.toFloat() / w, maxDimensionPx.toFloat() / h)
                        Bitmap.createScaledBitmap(bmp, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
                    } else bmp
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 78, out)
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                    out.toByteArray()
                }
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun initUpload(
        uploadId: String,
        targetFolderPath: String,
        fileName: String,
        totalBytes: Long,
        expectedSha256: String,
        mimeType: String
    ): Long = withContext(Dispatchers.IO) {
        val cleanFolder = PathSanitizer.sanitizeRelativePath(targetFolderPath)
        val cleanName = PathSanitizer.sanitizeSingleName(fileName)
        val folderDoc = resolveDocument(cleanFolder)
            ?: throw IllegalArgumentException("Target folder does not exist.")
        if (!folderDoc.isDirectory) throw IllegalArgumentException("Target path is not a folder.")
        val partFile = File(stagingDir, "$uploadId.part")
        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        uploadSessions[uploadId] = SafUploadSession(
            uploadId = uploadId,
            targetFolderPath = cleanFolder,
            fileName = cleanName,
            totalBytes = totalBytes,
            expectedSha256 = expectedSha256.lowercase(),
            mimeType = mimeType.ifBlank { FileClassifier.inferMimeType(cleanName) },
            stagingFile = partFile
        )
        existingBytes
    }

    override suspend fun appendUploadChunk(
        uploadId: String,
        offsetBytes: Long,
        chunkBytes: ByteArray
    ): Long = withContext(Dispatchers.IO) {
        val session = uploadSessions[uploadId]
            ?: throw IllegalStateException("Upload session not found.")
        RandomAccessFile(session.stagingFile, "rw").use { raf ->
            raf.seek(offsetBytes)
            raf.write(chunkBytes)
            raf.length()
        }
    }

    override suspend fun completeUpload(uploadId: String, expectedSha256: String): RemoteFileItem =
        withContext(Dispatchers.IO) {
            val session = uploadSessions[uploadId]
                ?: throw IllegalStateException("Upload session not found.")
            val part = session.stagingFile
            if (!part.exists()) throw IllegalStateException("Uploaded staging file missing.")

            if (session.totalBytes >= 0 && part.length() != session.totalBytes) {
                throw IllegalStateException("Size mismatch: expected ${session.totalBytes}, got ${part.length()}.")
            }

            val actualHash = FileInputStream(part).use { cryptoManager.sha256Hex(it) }
            val targetHash = expectedSha256.ifBlank { session.expectedSha256 }.lowercase()
            if (targetHash.isNotBlank() && actualHash != targetHash) {
                part.delete()
                uploadSessions.remove(uploadId)
                throw SecurityException("Checksum verification failed.")
            }

            val folderDoc = resolveDocument(session.targetFolderPath)
                ?: throw IllegalArgumentException("Target folder disappeared.")
            folderDoc.findFile(session.fileName)?.delete()
            val destDoc = folderDoc.createFile(session.mimeType, session.fileName)
                ?: throw IllegalStateException("Unable to create destination file '${session.fileName}'.")

            val buffer = ByteArray(64 * 1024)
            FileInputStream(part).use { input ->
                context.contentResolver.openOutputStream(destDoc.uri, "w")?.use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                } ?: throw IllegalStateException("Cannot write to destination SAF file.")
            }

            part.delete()
            uploadSessions.remove(uploadId)
            toRemoteFileItem(destDoc, session.targetFolderPath)
        }

    override suspend fun cancelUpload(uploadId: String) = withContext(Dispatchers.IO) {
        val session = uploadSessions.remove(uploadId)
        session?.stagingFile?.delete()
        Unit
    }

    private fun copyDocumentRecursively(src: DocumentFile, destParent: DocumentFile) {
        val name = src.name ?: return
        if (src.isDirectory) {
            val subDir = destParent.findFile(name) ?: destParent.createDirectory(name) ?: return
            src.listFiles().forEach { child -> copyDocumentRecursively(child, subDir) }
        } else {
            destParent.findFile(name)?.delete()
            val mime = src.type ?: FileClassifier.inferMimeType(name)
            val created = destParent.createFile(mime, name) ?: return
            val buffer = ByteArray(64 * 1024)
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                context.contentResolver.openOutputStream(created.uri, "w")?.use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
        }
    }

    private fun toRemoteFileItem(doc: DocumentFile, parentRel: String): RemoteFileItem {
        val name = doc.name ?: "Unnamed"
        val relPath = if (parentRel.isEmpty()) name else "$parentRel/$name"
        val mime = if (doc.isDirectory) "inode/directory" else (doc.type ?: FileClassifier.inferMimeType(name))
        val cat = if (doc.isDirectory) FileCategory.OTHER else FileClassifier.classify(name, mime)
        return RemoteFileItem(
            id = cryptoManager.sha256Hex(relPath.toByteArray()).take(16),
            name = name,
            path = relPath,
            parentPath = parentRel,
            isDirectory = doc.isDirectory,
            sizeBytes = if (doc.isDirectory) 0L else doc.length().coerceAtLeast(0L),
            mimeType = mime,
            lastModifiedEpochMs = doc.lastModified(),
            hasThumbnail = cat == FileCategory.IMAGE || cat == FileCategory.VIDEO,
            category = cat
        )
    }
}
