package com.example.storage

import com.example.domain.model.FileCategory
import com.example.domain.model.FileListResponse
import com.example.domain.model.RemoteFileItem
import com.example.domain.model.StorageInfo
import java.io.InputStream
import java.util.Locale

interface StorageProvider {
    val storageLabel: String

    suspend fun listFiles(relativePath: String, offset: Int = 0, limit: Int = 100): FileListResponse
    suspend fun getFileInfo(relativePath: String): RemoteFileItem?
    suspend fun createFolder(parentRelativePath: String, folderName: String): RemoteFileItem
    suspend fun deletePaths(relativePaths: List<String>): Int
    suspend fun rename(relativePath: String, newName: String): RemoteFileItem
    suspend fun copy(sourcePaths: List<String>, destinationFolderPath: String): Int
    suspend fun move(sourcePaths: List<String>, destinationFolderPath: String): Int
    suspend fun search(query: String, categoryFilter: FileCategory? = null, maxResults: Int = 100): List<RemoteFileItem>
    suspend fun getStorageInfo(): StorageInfo

    suspend fun openReadStream(relativePath: String): InputStream?
    suspend fun computeFileSha256(relativePath: String): String?
    suspend fun getThumbnailBytes(relativePath: String, maxDimensionPx: Int = 256): ByteArray?

    // Chunked resumable upload staging
    suspend fun initUpload(
        uploadId: String,
        targetFolderPath: String,
        fileName: String,
        totalBytes: Long,
        expectedSha256: String,
        mimeType: String
    ): Long

    suspend fun appendUploadChunk(uploadId: String, offsetBytes: Long, chunkBytes: ByteArray): Long
    suspend fun completeUpload(uploadId: String, expectedSha256: String): RemoteFileItem
    suspend fun cancelUpload(uploadId: String)
}

object FileClassifier {
    fun inferMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "tar", "gz" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"
            "txt", "log", "md", "csv" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "kt", "java", "js", "ts", "py", "yml", "yaml" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    fun classify(fileName: String, mimeType: String): FileCategory {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val mime = mimeType.lowercase(Locale.US)
        return when {
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif") ->
                FileCategory.IMAGE
            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v") ->
                FileCategory.VIDEO
            mime.startsWith("audio/") || ext in setOf("mp3", "wav", "flac", "aac", "m4a", "ogg") ->
                FileCategory.AUDIO
            ext == "apk" || mime == "application/vnd.android.package-archive" ->
                FileCategory.APK
            ext in setOf("zip", "rar", "7z", "tar", "gz") ->
                FileCategory.ARCHIVE
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx") ->
                FileCategory.DOCUMENT
            mime.startsWith("text/") || ext in setOf("txt", "json", "xml", "md", "log", "csv", "yaml", "yml", "kt", "java", "js", "py") ->
                FileCategory.TEXT
            else -> FileCategory.OTHER
        }
    }

    fun matchesSearchPattern(fileName: String, relativePath: String, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        return if (trimmed.contains('*') || trimmed.contains('?')) {
            val regexStr = buildString {
                append("^")
                for (ch in trimmed) {
                    when (ch) {
                        '*' -> append(".*")
                        '?' -> append(".")
                        '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> {
                            append("\\").append(ch)
                        }
                        else -> append(ch)
                    }
                }
                append("$")
            }
            val regex = Regex(regexStr, RegexOption.IGNORE_CASE)
            regex.matches(fileName) || regex.matches(relativePath)
        } else {
            fileName.contains(trimmed, ignoreCase = true) ||
                relativePath.contains(trimmed, ignoreCase = true)
        }
    }
}
