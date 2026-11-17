package com.example.security

import android.util.Log
import com.example.BuildConfig

/**
 * Security-conscious logger that is disabled in release builds and automatically redacts
 * sensitive fields (PINs, tokens, pairing codes, secrets, file contents).
 */
object SecureVaultLogger {
    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    private val sensitivePattern = Regex(
        """(?i)(pin|token|refreshToken|accessToken|pairingCode|sessionSecret|sessionSecretBase64|authorization|password)\s*[:=]\s*("[^"]*"|[^\s,}]+)"""
    )

    fun d(tag: String, message: String) {
        if (!enabled) return
        Log.d("HV/$tag", redact(message))
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        if (throwable != null) {
            Log.w("HV/$tag", redact(message), throwable)
        } else {
            Log.w("HV/$tag", redact(message))
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        if (throwable != null) {
            Log.e("HV/$tag", redact(message), throwable)
        } else {
            Log.e("HV/$tag", redact(message))
        }
    }

    fun redact(raw: String): String {
        return sensitivePattern.replace(raw) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
    }
}
