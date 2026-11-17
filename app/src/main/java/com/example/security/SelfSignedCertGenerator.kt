package com.example.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

data class TlsServerIdentity(
    val sslContext: SSLContext,
    val certificate: X509Certificate,
    val sha256Fingerprint: String
)

/**
 * Pure-Kotlin X.509 v3 self-signed TLS certificate generator using standard Java Cryptography
 * APIs (no external BouncyCastle dependency, works on Android API 24+ and JVM tests).
 */
object SelfSignedCertGenerator {

    fun generateTlsIdentity(
        commonName: String = "HomeVault-Server",
        validityDays: Int = 3650
    ): TlsServerIdentity {
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048, SecureRandom())
        val keyPair = keyPairGen.generateKeyPair()

        val cert = buildSelfSignedX509(keyPair, commonName, validityDays)
        val fingerprint = computeSha256Fingerprint(cert)

        val keyStore = KeyStore.getInstance("PKCS12")
        val password = CharArray(0)
        keyStore.load(null, password)
        keyStore.setKeyEntry(
            "homevault-tls",
            keyPair.private,
            password,
            arrayOf(cert)
        )

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())

        return TlsServerIdentity(
            sslContext = sslContext,
            certificate = cert,
            sha256Fingerprint = fingerprint
        )
    }

    fun computeSha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun buildSelfSignedX509(
        keyPair: KeyPair,
        commonName: String,
        validityDays: Int
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000L)
        val notAfter = Date(now + validityDays.toLong() * 86_400_000L)

        val serialBytes = ByteArray(8).apply { SecureRandom().nextBytes(this) }
        serialBytes[0] = (serialBytes[0].toInt() and 0x7F).toByte()
        if (serialBytes[0].toInt() == 0) serialBytes[0] = 1
        val serial = BigInteger(1, serialBytes)

        // sha256WithRSAEncryption: 1.2.840.113549.1.1.11
        val sigAlgId = derSequence(
            derOid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)),
            byteArrayOf(0x05, 0x00) // NULL
        )

        val cnName = derSequence(
            derSet(
                derSequence(
                    derOid(byteArrayOf(0x55, 0x04, 0x03)), // CN (2.5.4.3)
                    derUtf8String(commonName)
                )
            )
        )

        val validity = derSequence(
            derUtcTime(notBefore),
            derUtcTime(notAfter)
        )

        val versionExplicit0 = derExplicit(0, derInteger(BigInteger.valueOf(2)))

        // BasicConstraints (2.5.29.19): CA=FALSE
        val basicConstraintsExt = derSequence(
            derOid(byteArrayOf(0x55, 0x1D, 0x13)),
            byteArrayOf(0x01, 0x01, 0xFF.toByte()), // critical = TRUE
            derOctetString(derSequence())
        )
        val extensionsExplicit3 = derExplicit(3, derSequence(basicConstraintsExt))

        val tbsCertificate = derSequence(
            versionExplicit0,
            derInteger(serial),
            sigAlgId,
            cnName,
            validity,
            cnName,
            keyPair.public.encoded,
            extensionsExplicit3
        )

        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(keyPair.private)
        signature.update(tbsCertificate)
        val sigBytes = signature.sign()

        val certBytes = derSequence(
            tbsCertificate,
            sigAlgId,
            derBitString(sigBytes)
        )

        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
    }

    private fun derSequence(vararg items: ByteArray): ByteArray = derTag(0x30, concat(*items))
    private fun derSet(vararg items: ByteArray): ByteArray = derTag(0x31, concat(*items))
    private fun derExplicit(tagNum: Int, content: ByteArray): ByteArray =
        derTag(0xA0 or tagNum, content)

    private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())
    private fun derOid(encodedOidBody: ByteArray): ByteArray = derTag(0x06, encodedOidBody)
    private fun derUtf8String(text: String): ByteArray =
        derTag(0x0C, text.toByteArray(StandardCharsets.UTF_8))

    private fun derOctetString(content: ByteArray): ByteArray = derTag(0x04, content)

    private fun derBitString(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size + 1)
        out[0] = 0x00 // 0 unused bits
        System.arraycopy(bytes, 0, out, 1, bytes.size)
        return derTag(0x03, out)
    }

    private fun derUtcTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return derTag(0x17, fmt.format(date).toByteArray(StandardCharsets.US_ASCII))
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 128 -> out.write(len)
            len <= 0xFF -> {
                out.write(0x81)
                out.write(len)
            }
            len <= 0xFFFF -> {
                out.write(0x82)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write((len shr 16) and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (a in arrays) out.write(a)
        return out.toByteArray()
    }
}
