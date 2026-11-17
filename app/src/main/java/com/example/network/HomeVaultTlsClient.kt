package com.example.network

import com.example.security.SelfSignedCertGenerator
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Builds [OkHttpClient] instances with SHA-256 certificate fingerprint pinning for direct
 * self-signed Home Server TLS connections, and standard system CA verification for public relays.
 */
object HomeVaultTlsClient {

    fun buildPinnedClient(
        expectedSha256Fingerprint: String,
        connectTimeoutSec: Long = 8,
        readTimeoutSec: Long = 30
    ): OkHttpClient {
        val cleanExpected = expectedSha256Fingerprint.trim().uppercase()
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Empty TLS certificate chain.")
                }
                if (cleanExpected.isNotEmpty()) {
                    val actual = SelfSignedCertGenerator.computeSha256Fingerprint(chain[0]).uppercase()
                    if (actual != cleanExpected) {
                        throw CertificateException("TLS certificate fingerprint mismatch.")
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(HostnameVerifier { _, _ -> cleanExpected.isNotEmpty() })
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .build()
    }

    fun buildStandardClient(
        connectTimeoutSec: Long = 10,
        readTimeoutSec: Long = 45
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .build()
    }
}
