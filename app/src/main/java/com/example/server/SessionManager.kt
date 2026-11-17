package com.example.server

import android.util.Base64
import com.example.security.CryptoManager
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class ActiveSession(
    val accessToken: String,
    val refreshToken: String,
    val clientId: String,
    val clientName: String,
    val sessionSecretBase64: String,
    val accessExpiresAtMs: Long,
    val refreshExpiresAtMs: Long
)

data class PairingCodeState(
    val code: String,
    val expiresAtMs: Long
)

class SessionManager(
    private val cryptoManager: CryptoManager = CryptoManager(),
    private val accessTokenTtlMs: Long = 15 * 60 * 1000L, // 15 minutes short-lived token
    private val refreshTokenTtlMs: Long = 30L * 24 * 60 * 60 * 1000L, // 30 days
    private val pairingTtlMs: Long = 5 * 60 * 1000L, // 5 minutes
    private val maxClockSkewMs: Long = 5 * 60 * 1000L, // 5 minutes replay window
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val sessionsByAccess = ConcurrentHashMap<String, ActiveSession>()
    private val sessionsByRefresh = ConcurrentHashMap<String, ActiveSession>()
    private val pairedClients = ConcurrentHashMap<String, String>() // clientId -> clientName
    private val seenNonces = ConcurrentHashMap<String, Long>() // nonce -> seenAtMs

    @Volatile
    private var currentPairing: PairingCodeState? = null

    fun generateOrGetPairingCode(forceRegenerate: Boolean = false): PairingCodeState {
        val now = timeProvider()
        val existing = currentPairing
        if (!forceRegenerate && existing != null && existing.expiresAtMs > now) {
            return existing
        }
        val created = PairingCodeState(
            code = cryptoManager.generatePairingCode(),
            expiresAtMs = now + pairingTtlMs
        )
        currentPairing = created
        return created
    }

    fun verifyAndConsumePairingCode(code: String): Boolean {
        val now = timeProvider()
        val state = currentPairing ?: return false
        if (now > state.expiresAtMs) {
            currentPairing = null
            return false
        }
        val matches = cryptoManager.constantTimeEquals(state.code, code.trim())
        if (matches) {
            // Rotate pairing code after use to prevent reuse
            currentPairing = null
        }
        return matches
    }

    fun issueSession(clientId: String, clientName: String): ActiveSession {
        val now = timeProvider()
        val secretBytes = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val session = ActiveSession(
            accessToken = cryptoManager.generateRandomToken(32),
            refreshToken = cryptoManager.generateRandomToken(40),
            clientId = clientId,
            clientName = clientName,
            sessionSecretBase64 = Base64.encodeToString(secretBytes, Base64.NO_WRAP),
            accessExpiresAtMs = now + accessTokenTtlMs,
            refreshExpiresAtMs = now + refreshTokenTtlMs
        )
        pairedClients[clientId] = clientName
        sessionsByAccess[session.accessToken] = session
        sessionsByRefresh[session.refreshToken] = session
        return session
    }

    fun isClientPaired(clientId: String): Boolean = pairedClients.containsKey(clientId)

    fun registerPairedClient(clientId: String, clientName: String) {
        pairedClients[clientId] = clientName
    }

    fun validateAccessToken(token: String?): ActiveSession? {
        if (token.isNullOrBlank()) return null
        val session = sessionsByAccess[token] ?: return null
        val now = timeProvider()
        if (now > session.accessExpiresAtMs) {
            sessionsByAccess.remove(token)
            return null
        }
        return session
    }

    fun refreshSession(refreshToken: String, clientId: String): ActiveSession? {
        val existing = sessionsByRefresh[refreshToken] ?: return null
        val now = timeProvider()
        if (now > existing.refreshExpiresAtMs || existing.clientId != clientId) {
            sessionsByRefresh.remove(refreshToken)
            sessionsByAccess.remove(existing.accessToken)
            return null
        }
        // Rotate tokens
        sessionsByAccess.remove(existing.accessToken)
        sessionsByRefresh.remove(existing.refreshToken)
        return issueSession(existing.clientId, existing.clientName)
    }

    fun revokeAccessToken(accessToken: String) {
        val removed = sessionsByAccess.remove(accessToken)
        if (removed != null) {
            sessionsByRefresh.remove(removed.refreshToken)
        }
    }

    fun revokeAllSessions() {
        sessionsByAccess.clear()
        sessionsByRefresh.clear()
    }

    fun disconnectAllClients() {
        revokeAllSessions()
        pairedClients.clear()
        currentPairing = null
    }

    fun activeClientCount(): Int = sessionsByAccess.values
        .filter { timeProvider() <= it.accessExpiresAtMs }
        .map { it.clientId }
        .distinct()
        .size

    /**
     * Prevents replay attacks by checking timestamp freshness and guaranteeing every nonce
     * can only be used once within the allowed clock skew window.
     */
    fun verifyReplayProtection(nonce: String?, timestampMs: Long?): Boolean {
        if (nonce.isNullOrBlank() || timestampMs == null) return false
        val now = timeProvider()
        if (kotlin.math.abs(now - timestampMs) > maxClockSkewMs) {
            return false
        }
        cleanupExpiredNonces(now)
        val previous = seenNonces.putIfAbsent(nonce, now)
        return previous == null
    }

    private fun cleanupExpiredNonces(now: Long) {
        if (seenNonces.size < 256) return
        val cutoff = now - maxClockSkewMs
        val it = seenNonces.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < cutoff) it.remove()
        }
    }
}
