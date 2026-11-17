package com.example.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe sliding-window rate limiter & temporary lockout guard against brute-force PIN,
 * pairing code, and API flood attacks.
 */
class RateLimiter(
    private val maxRequestsPerWindow: Int = 60,
    private val windowMs: Long = 60_000L,
    private val maxAuthFailures: Int = 5,
    private val authLockoutMs: Long = 120_000L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private data class Bucket(
        val timestamps: ArrayDeque<Long> = ArrayDeque()
    )

    private data class AuthFailureState(
        var failures: Int = 0,
        var lockedUntilMs: Long = 0L
    )

    private val requestBuckets = ConcurrentHashMap<String, Bucket>()
    private val authFailures = ConcurrentHashMap<String, AuthFailureState>()

    fun allowRequest(key: String): Boolean {
        val now = timeProvider()
        val bucket = requestBuckets.computeIfAbsent(key) { Bucket() }
        synchronized(bucket) {
            while (bucket.timestamps.isNotEmpty() && now - bucket.timestamps.first() > windowMs) {
                bucket.timestamps.removeFirst()
            }
            if (bucket.timestamps.size >= maxRequestsPerWindow) {
                return false
            }
            bucket.timestamps.addLast(now)
            return true
        }
    }

    fun isAuthLocked(key: String): Boolean {
        val state = authFailures[key] ?: return false
        val now = timeProvider()
        synchronized(state) {
            if (state.lockedUntilMs > now) return true
            if (state.lockedUntilMs != 0L && now >= state.lockedUntilMs) {
                state.failures = 0
                state.lockedUntilMs = 0L
            }
            return false
        }
    }

    fun recordAuthFailure(key: String) {
        val now = timeProvider()
        val state = authFailures.computeIfAbsent(key) { AuthFailureState() }
        synchronized(state) {
            state.failures += 1
            if (state.failures >= maxAuthFailures) {
                state.lockedUntilMs = now + authLockoutMs
            }
        }
    }

    fun clearAuthFailures(key: String) {
        authFailures.remove(key)
    }
}
