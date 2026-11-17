package com.example.security

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class PathTraversalException(message: String) : SecurityException(message)

/**
 * Strict path sanitizer preventing directory traversal (../, encoded %2e%2e, null bytes,
 * backslash escapes, drive prefixes) so clients can never access files outside the user-selected
 * storage root.
 */
object PathSanitizer {

    fun sanitizeRelativePath(rawPath: String?): String {
        if (rawPath.isNullOrBlank() || rawPath == "/") return ""

        val decoded = try {
            URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            throw PathTraversalException("Malformed path encoding.")
        }

        if (decoded.contains('\u0000') || rawPath.contains('\u0000')) {
            throw PathTraversalException("Null byte detected in path.")
        }
        if (decoded.contains('\\') || rawPath.contains('\\')) {
            throw PathTraversalException("Backslash path separators are not allowed.")
        }
        if (decoded.contains(':') && !decoded.startsWith("/")) {
            throw PathTraversalException("Scheme or drive prefix is not allowed.")
        }
        val lower = rawPath.lowercase()
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) {
            throw PathTraversalException("Encoded path traversal sequence is not allowed.")
        }

        val normalizedSegments = mutableListOf<String>()
        val parts = decoded.trim('/').split('/')
        for (segment in parts) {
            if (segment.isEmpty()) continue
            if (segment == "." || segment == "..") {
                throw PathTraversalException("Path traversal segment '$segment' is forbidden.")
            }
            if (segment.startsWith("..") || segment.endsWith("..")) {
                throw PathTraversalException("Suspicious segment '$segment' is forbidden.")
            }
            normalizedSegments.add(segment)
        }
        return normalizedSegments.joinToString("/")
    }

    fun sanitizeSingleName(rawName: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            throw PathTraversalException("Invalid file or folder name.")
        }
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000')) {
            throw PathTraversalException("Name must not contain path separators.")
        }
        return trimmed
    }

    fun splitParentAndName(sanitizedPath: String): Pair<String, String> {
        if (sanitizedPath.isEmpty()) throw PathTraversalException("Root path cannot be modified.")
        val idx = sanitizedPath.lastIndexOf('/')
        return if (idx < 0) {
            "" to sanitizedPath
        } else {
            sanitizedPath.substring(0, idx) to sanitizedPath.substring(idx + 1)
        }
    }
}
