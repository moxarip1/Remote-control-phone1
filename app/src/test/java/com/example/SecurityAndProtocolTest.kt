package com.example

import com.example.domain.model.LoginRequest
import com.example.domain.model.PairRequest
import com.example.security.CryptoManager
import com.example.security.PathSanitizer
import com.example.security.PathTraversalException
import com.example.security.RateLimiter
import com.example.security.SecureVaultLogger
import com.example.security.SelfSignedCertGenerator
import com.example.server.HomeVaultHttpServer
import com.example.server.HttpRequestData
import com.example.server.SessionManager
import com.example.storage.LocalDirectoryStorageProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityAndProtocolTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { encodeDefaults = true }

    @Test
    fun `path sanitizer blocks traversal, backslashes, encoded dots, and null bytes`() {
        val attacks = listOf(
            "../etc/passwd",
            "photos/../../secret",
            "..\\Windows\\System32",
            "%2e%2e/secret",
            "docs/file\u0000.txt",
            "C:/Users/admin"
        )
        for (attack in attacks) {
            var threw = false
            try {
                PathSanitizer.sanitizeRelativePath(attack)
            } catch (_: PathTraversalException) {
                threw = true
            }
            assertTrue("Expected PathTraversalException for '$attack'", threw)
        }
        assertEquals("photos/2026/vacation", PathSanitizer.sanitizeRelativePath("/photos/2026/vacation/"))
        assertEquals("", PathSanitizer.sanitizeRelativePath("/"))
    }

    @Test
    fun `crypto manager hashes PIN with PBKDF2, encrypts secrets, and redacts logs`() {
        val crypto = CryptoManager()
        val (salt, hash) = crypto.hashPin("482910")
        assertTrue(crypto.verifyPin("482910", salt, hash))
        assertFalse(crypto.verifyPin("000000", salt, hash))

        val secret = "super-secret-refresh-token"
        val encrypted = crypto.encryptString(secret)
        assertNotEquals(secret, encrypted)
        assertEquals(secret, crypto.decryptString(encrypted))

        val redacted = SecureVaultLogger.redact("Auth pin=482910 token=abc12345")
        assertFalse(redacted.contains("482910"))
        assertFalse(redacted.contains("abc12345"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `self-signed X509 generator produces valid certificate with SHA-256 fingerprint`() {
        val identity = SelfSignedCertGenerator.generateTlsIdentity("HomeVault-Test")
        assertEquals("X.509", identity.certificate.type)
        assertTrue(identity.sha256Fingerprint.contains(":"))
        assertEquals(95, identity.sha256Fingerprint.length) // 32 bytes hex + 31 colons
    }

    @Test
    fun `server rejects invalid token, expired session, wrong PIN, replay, malformed JSON, path traversal, and rate limits`() = runTest {
        val rootDir = tempFolder.newFolder("vault_root")
        File(rootDir, "allowed.txt").writeText("safe")
        val crypto = CryptoManager()
        var currentTimeMs = 1_700_000_000_000L

        val sessionManager = SessionManager(
            cryptoManager = crypto,
            accessTokenTtlMs = 5_000L,
            timeProvider = { currentTimeMs }
        )
        val rateLimiter = RateLimiter(
            maxRequestsPerWindow = 50,
            maxAuthFailures = 3,
            authLockoutMs = 60_000L,
            timeProvider = { currentTimeMs }
        )
        val storage = LocalDirectoryStorageProvider(rootDir, crypto)
        val server = HomeVaultHttpServer(
            deviceId = "hv-sec-1",
            deviceNameProvider = { "Test Vault" },
            storageProviderFactory = { storage },
            pinVerifier = { it == "998877" },
            sessionManager = sessionManager,
            rateLimiter = rateLimiter,
            cryptoManager = crypto
        )

        // 1. Invalid token -> 401
        val invalidTokenResp = server.dispatch(
            HttpRequestData(
                method = "GET",
                path = "/api/files/list",
                queryParams = emptyMap(),
                headers = mapOf(
                    "authorization" to "Bearer invalid-token",
                    "x-hv-nonce" to "n1",
                    "x-hv-timestamp" to currentTimeMs.toString()
                ),
                body = ByteArray(0),
                remoteAddress = "10.0.0.5"
            )
        )
        assertEquals(401, invalidTokenResp.statusCode)

        // 2. Malformed JSON -> 400
        val malformedResp = server.dispatch(
            HttpRequestData(
                method = "POST",
                path = "/api/auth/pair",
                queryParams = emptyMap(),
                headers = emptyMap(),
                body = "{not-valid-json".toByteArray(StandardCharsets.UTF_8),
                remoteAddress = "10.0.0.5"
            )
        )
        assertEquals(400, malformedResp.statusCode)

        // 3. Wrong PIN triggers 403 and then 429 rate-limit lockout after 3 failures
        val codeState = sessionManager.generateOrGetPairingCode(true)
        repeat(3) { idx ->
            val badPair = PairRequest(
                pairingCode = codeState.code,
                pin = "000000",
                clientId = "cl-1",
                clientName = "Phone",
                nonce = "bad-$idx",
                timestampMs = currentTimeMs
            )
            val resp = server.dispatch(
                HttpRequestData(
                    method = "POST",
                    path = "/api/auth/pair",
                    queryParams = emptyMap(),
                    headers = emptyMap(),
                    body = json.encodeToString(badPair).toByteArray(),
                    remoteAddress = "10.0.0.99"
                )
            )
            assertEquals(403, resp.statusCode)
        }
        val lockedResp = server.dispatch(
            HttpRequestData(
                method = "POST",
                path = "/api/auth/pair",
                queryParams = emptyMap(),
                headers = emptyMap(),
                body = json.encodeToString(
                    PairRequest(codeState.code, "998877", "cl-1", "Phone", "bad-4", currentTimeMs)
                ).toByteArray(),
                remoteAddress = "10.0.0.99"
            )
        )
        assertEquals(429, lockedResp.statusCode)

        // 4. Valid pairing from clean IP succeeds
        val goodPair = PairRequest(codeState.code, "998877", "cl-1", "Phone", "good-1", currentTimeMs)
        val okPairResp = server.dispatch(
            HttpRequestData(
                method = "POST",
                path = "/api/auth/pair",
                queryParams = emptyMap(),
                headers = emptyMap(),
                body = json.encodeToString(goodPair).toByteArray(),
                remoteAddress = "10.0.0.10"
            )
        )
        assertEquals(200, okPairResp.statusCode)
        val session = sessionManager.issueSession("cl-test", "Pixel")

        // 5. Path traversal attempt -> 403
        val traversalResp = server.dispatch(
            HttpRequestData(
                method = "GET",
                path = "/api/files/download",
                queryParams = mapOf("path" to "../secret.txt"),
                headers = mapOf(
                    "authorization" to "Bearer ${session.accessToken}",
                    "x-hv-nonce" to "nonce-trav",
                    "x-hv-timestamp" to currentTimeMs.toString()
                ),
                body = ByteArray(0),
                remoteAddress = "10.0.0.10"
            )
        )
        assertEquals(403, traversalResp.statusCode)

        // 6. Replay attack with same nonce -> 403
        val firstOk = server.dispatch(
            HttpRequestData(
                method = "GET",
                path = "/api/files/list",
                queryParams = mapOf("path" to ""),
                headers = mapOf(
                    "authorization" to "Bearer ${session.accessToken}",
                    "x-hv-nonce" to "nonce-unique",
                    "x-hv-timestamp" to currentTimeMs.toString()
                ),
                body = ByteArray(0),
                remoteAddress = "10.0.0.10"
            )
        )
        assertEquals(200, firstOk.statusCode)

        val replayResp = server.dispatch(
            HttpRequestData(
                method = "GET",
                path = "/api/files/list",
                queryParams = mapOf("path" to ""),
                headers = mapOf(
                    "authorization" to "Bearer ${session.accessToken}",
                    "x-hv-nonce" to "nonce-unique",
                    "x-hv-timestamp" to currentTimeMs.toString()
                ),
                body = ByteArray(0),
                remoteAddress = "10.0.0.10"
            )
        )
        assertEquals(403, replayResp.statusCode)

        // 7. Expired session -> 401
        currentTimeMs += 20_000L
        val expiredResp = server.dispatch(
            HttpRequestData(
                method = "GET",
                path = "/api/files/list",
                queryParams = mapOf("path" to ""),
                headers = mapOf(
                    "authorization" to "Bearer ${session.accessToken}",
                    "x-hv-nonce" to "nonce-after-exp",
                    "x-hv-timestamp" to currentTimeMs.toString()
                ),
                body = ByteArray(0),
                remoteAddress = "10.0.0.10"
            )
        )
        assertEquals(401, expiredResp.statusCode)

        // 8. Wrong PIN on login -> 403
        val badLogin = LoginRequest("hv-sec-1", "cl-test", "Pixel", "111111", "login-bad", currentTimeMs)
        val badLoginResp = server.dispatch(
            HttpRequestData(
                method = "POST",
                path = "/api/auth/login",
                queryParams = emptyMap(),
                headers = emptyMap(),
                body = json.encodeToString(badLogin).toByteArray(),
                remoteAddress = "10.0.0.11"
            )
        )
        assertEquals(403, badLoginResp.statusCode)
    }
}
