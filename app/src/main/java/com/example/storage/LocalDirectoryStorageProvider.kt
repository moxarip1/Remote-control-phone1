package com.example.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.StatFs
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
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

class LocalDirectoryStorageProvider(
    private val rootDir: File,
    private val cryptoManager: CryptoManager = CryptoManager()
) : StorageProvider {

    override val storageLabel: String
        get() = rootDir.name.ifBlank { rootDir.absolutePath }

    private data class UploadSession(
        val uploadId: String,
        val targetFolderPath: String,
        val fileName: String,
        val totalBytes: Long,
        val expectedSha256: String,
        val stagingFile: File
    )

    private val stagingDir: File by lazy {
        File(rootDir, ".hv_staging").apply { mkdirs() }
    }
    private val uploadSessions = ConcurrentHashMap<String, UploadSession>()

    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
    }

    private fun resolveSafeFile(relativePath: String): File {
        val clean = PathSanitizer.sanitizeRelativePath(relativePath)
        val rootCanonical = rootDir.canonicalFile
        val candidate = if (clean.isEmpty()) rootCanonical else File(rootCanonical, clean)
        val candidateCanonical = candidate.canonicalFile
        val rootPrefix = rootCanonical.path.trimEnd(File.separatorChar) + File.separatorChar
        if (candidateCanonical != rootCanonical && !candidateCanonical.path.startsWith(rootPrefix)) {
            throw PathTraversalException("Access outside storage root is forbidden.")
        }
        return candidateCanonical
    }

    override suspend fun listFiles(relativePath: String, offset: Int, limit: Int): FileListResponse =
        withContext(Dispatchers.IO) {
            val clean = PathSanitizer.sanitizeRelativePath(relativePath)
            val dir = resolveSafeFile(clean)
            if (!dir.exists() || !dir.isDirectory) {
                return@withContext FileListResponse(clean, emptyList(), 0, offset, limit, false)
            }
            val children = (dir.listFiles() ?: emptyArray())
                .filter { it.name != ".hv_staging" }
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })

            val safeOffset = offset.coerceAtLeast(0)
            val safeLimit = limit.coerceIn(1, 500)
            val page = children.drop(safeOffset).take(safeLimit).map { file ->
                toRemoteFileItem(file, clean)
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
        val file = resolveSafeFile(clean)
        if (!file.exists()) return@withContext null
        val parent = if (clean.contains('/')) clean.substringBeforeLast('/') else ""
        toRemoteFileItem(file, parent)
    }

    override suspend fun createFolder(parentRelativePath: String, folderName: String): RemoteFileItem =
        withContext(Dispatchers.IO) {
            val cleanParent = PathSanitizer.sanitizeRelativePath(parentRelativePath)
            val cleanName = PathSanitizer.sanitizeSingleName(folderName)
            val parentDir = resolveSafeFile(cleanParent)
            if (!parentDir.exists() || !parentDir.isDirectory) {
                throw IllegalArgumentException("Parent folder does not exist.")
            }
            val newDir = resolveSafeFile(if (cleanParent.isEmpty()) cleanName else "$cleanParent/$cleanName")
            if (!newDir.exists() && !newDir.mkdirs()) {
                throw IllegalStateException("Could not create folder '$cleanName'.")
            }
            toRemoteFileItem(newDir, cleanParent)
        }

    override suspend fun deletePaths(relativePaths: List<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (raw in relativePaths) {
            val clean = PathSanitizer.sanitizeRelativePath(raw)
            if (clean.isEmpty()) throw PathTraversalException("Cannot delete root storage folder.")
            val target = resolveSafeFile(clean)
            if (target.exists() && target.deleteRecursively()) {
                count++
            }
        }
        count
    }

    override suspend fun rename(relativePath: String, newName: String): RemoteFileItem =
        withContext(Dispatchers.IO) {
            val clean = PathSanitizer.sanitizeRelativePath(relativePath)
            val (parentPath, _) = PathSanitizer.splitParentAndName(clean)
            val safeNewName = PathSanitizer.sanitizeSingleName(newName)
            val source = resolveSafeFile(clean)
            if (!source.exists()) throw IllegalArgumentException("File does not exist.")
            val destRel = if (parentPath.isEmpty()) safeNewName else "$parentPath/$safeNewName"
            val dest = resolveSafeFile(destRel)
            if (dest.exists()) throw IllegalStateException("A file named '$safeNewName' already exists.")
            if (!source.renameTo(dest)) {
                throw IllegalStateException("Failed to rename '${source.name}'.")
            }
            toRemoteFileItem(dest, parentPath)
        }

    override suspend fun copy(sourcePaths: List<String>, destinationFolderPath: String): Int =
        withContext(Dispatchers.IO) {
            val cleanDestFolder = PathSanitizer.sanitizeRelativePath(destinationFolderPath)
            val destDir = resolveSafeFile(cleanDestFolder)
            if (!destDir.exists() || !destDir.isDirectory) {
                throw IllegalArgumentException("Destination folder does not exist.")
            }
            var copied = 0
            for (raw in sourcePaths) {
                val cleanSrc = PathSanitizer.sanitizeRelativePath(raw)
                val src = resolveSafeFile(cleanSrc)
                if (!src.exists()) continue
                val target = resolveSafeFile(
                    if (cleanDestFolder.isEmpty()) src.name else "$cleanDestFolder/${src.name}"
                )
                if (src.canonicalPath == target.canonicalPath) continue
                copyRecursivelyBuffered(src, target)
                copied++
            }
            copied
        }

    override suspend fun move(sourcePaths: List<String>, destinationFolderPath: String): Int =
        withContext(Dispatchers.IO) {
            val cleanDestFolder = PathSanitizer.sanitizeRelativePath(destinationFolderPath)
            val destDir = resolveSafeFile(cleanDestFolder)
            if (!destDir.exists() || !destDir.isDirectory) {
                throw IllegalArgumentException("Destination folder does not exist.")
            }
            var moved = 0
            for (raw in sourcePaths) {
                val cleanSrc = PathSanitizer.sanitizeRelativePath(raw)
                if (cleanSrc.isEmpty()) continue
                val src = resolveSafeFile(cleanSrc)
                if (!src.exists()) continue
                val target = resolveSafeFile(
                    if (cleanDestFolder.isEmpty()) src.name else "$cleanDestFolder/${src.name}"
                )
                if (src.canonicalPath == target.canonicalPath) continue
                if (src.renameTo(target)) {
                    moved++
                } else {
                    copyRecursivelyBuffered(src, target)
                    if (src.deleteRecursively()) moved++
                }
            }
            moved
        }

    override suspend fun search(
        query: String,
        categoryFilter: FileCategory?,
        maxResults: Int
    ): List<RemoteFileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RemoteFileItem>()
        fun walk(dir: File, relDir: String) {
            if (results.size >= maxResults) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.name == ".hv_staging") continue
                val childRel = if (relDir.isEmpty()) child.name else "$relDir/${child.name}"
                val item = toRemoteFileItem(child, relDir)
                val matchesQuery = FileClassifier.matchesSearchPattern(child.name, childRel, query)
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
        walk(rootDir, "")
        results
    }

    override suspend fun getStorageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        val statFs = runCatching { StatFs(rootDir.absolutePath) }.getOrNull()
        val fsTotal = statFs?.totalBytes ?: 0L
        val fsFree = statFs?.availableBytes ?: 0L

        var fileCount = 0
        var folderCount = 0
        var vaultBytes = 0L
        val categoryMap = mutableMapOf<FileCategory, Pair<Long, Int>>()
        val allFiles = mutableListOf<RemoteFileItem>()

        fun walk(dir: File, relDir: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.name == ".hv_staging") continue
                if (child.isDirectory) {
                    folderCount++
                    val nextRel = if (relDir.isEmpty()) child.name else "$relDir/${child.name}"
                    walk(child, nextRel)
                } else {
                    fileCount++
                    val len = child.length()
                    vaultBytes += len
                    val item = toRemoteFileItem(child, relDir)
                    allFiles.add(item)
                    val prev = categoryMap[item.category] ?: (0L to 0)
                    categoryMap[item.category] = (prev.first + len) to (prev.second + 1)
                }
            }
        }
        walk(rootDir, "")

        val total = if (fsTotal > 0L) fsTotal else vaultBytes.coerceAtLeast(1L)
        val free = if (fsTotal > 0L) fsFree else 0L
        val used = if (fsTotal > 0L) (fsTotal - fsFree).coerceAtLeast(vaultBytes) else vaultBytes

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
        val file = resolveSafeFile(relativePath)
        if (!file.exists() || file.isDirectory) return@withContext null
        FileInputStream(file)
    }

    override suspend fun computeFileSha256(relativePath: String): String? = withContext(Dispatchers.IO) {
        val file = resolveSafeFile(relativePath)
        if (!file.exists() || file.isDirectory) return@withContext null
        FileInputStream(file).use { cryptoManager.sha256Hex(it) }
    }

    override suspend fun getThumbnailBytes(relativePath: String, maxDimensionPx: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val file = resolveSafeFile(relativePath)
            if (!file.exists() || file.isDirectory) return@withContext null
            val mime = FileClassifier.inferMimeType(file.name)
            val category = FileClassifier.classify(file.name, mime)
            try {
                val bitmap: Bitmap? = when (category) {
                    FileCategory.IMAGE -> {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, opts)
                        var sample = 1
                        while (opts.outWidth / sample > maxDimensionPx * 2 || opts.outHeight / sample > maxDimensionPx * 2) {
                            sample *= 2
                        }
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                    }
                    FileCategory.VIDEO -> {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } finally {
                            retriever.release()
                        }
                    }
                    else -> null
                }
                bitmap?.let { bmp ->
                    val scaled = scaleBitmapDown(bmp, maxDimensionPx)
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
        val folder = resolveSafeFile(cleanFolder)
        if (!folder.exists() || !folder.isDirectory) {
            throw IllegalArgumentException("Target folder does not exist.")
        }
        val partFile = File(stagingDir, "$uploadId.part")
        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        uploadSessions[uploadId] = UploadSession(
            uploadId = uploadId,
            targetFolderPath = cleanFolder,
            fileName = cleanName,
            totalBytes = totalBytes,
            expectedSha256 = expectedSha256.lowercase(),
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

            val destRel = if (session.targetFolderPath.isEmpty()) session.fileName
            else "${session.targetFolderPath}/${session.fileName}"
            val dest = resolveSafeFile(destRel)
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                copyRecursivelyBuffered(part, dest)
                part.delete()
            }
            uploadSessions.remove(uploadId)
            toRemoteFileItem(dest, session.targetFolderPath)
        }

    override suspend fun cancelUpload(uploadId: String) = withContext(Dispatchers.IO) {
        val session = uploadSessions.remove(uploadId)
        session?.stagingFile?.delete()
        Unit
    }

    private fun copyRecursivelyBuffered(src: File, dst: File) {
        if (src.isDirectory) {
            if (!dst.exists()) dst.mkdirs()
            src.listFiles()?.forEach { child ->
                copyRecursivelyBuffered(child, File(dst, child.name))
            }
        } else {
            dst.parentFile?.mkdirs()
            val buffer = ByteArray(64 * 1024)
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
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

    private fun toRemoteFileItem(file: File, parentRel: String): RemoteFileItem {
        val relPath = if (parentRel.isEmpty()) file.name else "$parentRel/${file.name}"
        val mime = if (file.isDirectory) "inode/directory" else FileClassifier.inferMimeType(file.name)
        val cat = if (file.isDirectory) FileCategory.OTHER else FileClassifier.classify(file.name, mime)
        return RemoteFileItem(
            id = cryptoManager.sha256Hex(relPath.toByteArray()).take(16),
            name = file.name,
            path = relPath,
            parentPath = parentRel,
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isDirectory) 0L else file.length(),
            mimeType = mime,
            lastModifiedEpochMs = file.lastModified(),
            hasThumbnail = cat == FileCategory.IMAGE || cat == FileCategory.VIDEO,
            category = cat
        )
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        return Bitmap.createScaledBitmap(
            bitmap,
            (w * ratio).toInt().coerceAtLeast(1),
            (h * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
