package com.example.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppMode
import com.example.domain.model.ThemeMode
import com.example.security.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "homevault_settings")

data class AppSettings(
    val appMode: AppMode = AppMode.UNSET,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val languageCode: String = "en",
    val autoReconnect: Boolean = true,
    val activeDeviceId: String = "",
    // Server settings
    val serverDeviceId: String = "",
    val serverDeviceName: String = "Home Storage",
    val serverPort: Int = 8443,
    val serverStorageUri: String = "",
    val serverStorageLabel: String = "",
    val startOnBoot: Boolean = false,
    val serverShouldBeRunning: Boolean = false,
    val relayUrl: String = "",
    val directEndpointUrl: String = "",
    // Transfer settings
    val maxConcurrentTransfers: Int = 2,
    val wifiOnlyTransfers: Boolean = false,
    val retryFailedTransfers: Boolean = true,
    // Security status
    val hasPinConfigured: Boolean = false,
    val clientId: String = ""
)

class SettingsStore(
    private val context: Context,
    private val cryptoManager: CryptoManager
) {
    private object Keys {
        val APP_MODE = stringPreferencesKey("app_mode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val ACTIVE_DEVICE_ID = stringPreferencesKey("active_device_id")

        val SERVER_DEVICE_ID = stringPreferencesKey("server_device_id")
        val SERVER_DEVICE_NAME = stringPreferencesKey("server_device_name")
        val SERVER_PORT = intPreferencesKey("server_port")
        val SERVER_STORAGE_URI = stringPreferencesKey("server_storage_uri")
        val SERVER_STORAGE_LABEL = stringPreferencesKey("server_storage_label")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val SERVER_SHOULD_RUN = booleanPreferencesKey("server_should_run")
        val RELAY_URL = stringPreferencesKey("relay_url")
        val DIRECT_ENDPOINT_URL = stringPreferencesKey("direct_endpoint_url")

        val MAX_CONCURRENT_TRANSFERS = intPreferencesKey("max_concurrent_transfers")
        val WIFI_ONLY_TRANSFERS = booleanPreferencesKey("wifi_only_transfers")
        val RETRY_FAILED_TRANSFERS = booleanPreferencesKey("retry_failed_transfers")

        val PIN_SALT_ENC = stringPreferencesKey("pin_salt_enc")
        val PIN_HASH_ENC = stringPreferencesKey("pin_hash_enc")
        val CLIENT_ID = stringPreferencesKey("client_id")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            appMode = prefs[Keys.APP_MODE]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() } ?: AppMode.UNSET,
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            languageCode = prefs[Keys.LANGUAGE] ?: "en",
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            activeDeviceId = prefs[Keys.ACTIVE_DEVICE_ID] ?: "",
            serverDeviceId = prefs[Keys.SERVER_DEVICE_ID] ?: "",
            serverDeviceName = prefs[Keys.SERVER_DEVICE_NAME] ?: "Home Storage",
            serverPort = prefs[Keys.SERVER_PORT] ?: 8443,
            serverStorageUri = prefs[Keys.SERVER_STORAGE_URI] ?: "",
            serverStorageLabel = prefs[Keys.SERVER_STORAGE_LABEL] ?: "",
            startOnBoot = prefs[Keys.START_ON_BOOT] ?: false,
            serverShouldBeRunning = prefs[Keys.SERVER_SHOULD_RUN] ?: false,
            relayUrl = prefs[Keys.RELAY_URL] ?: "",
            directEndpointUrl = prefs[Keys.DIRECT_ENDPOINT_URL] ?: "",
            maxConcurrentTransfers = prefs[Keys.MAX_CONCURRENT_TRANSFERS] ?: 2,
            wifiOnlyTransfers = prefs[Keys.WIFI_ONLY_TRANSFERS] ?: false,
            retryFailedTransfers = prefs[Keys.RETRY_FAILED_TRANSFERS] ?: true,
            hasPinConfigured = !prefs[Keys.PIN_HASH_ENC].isNullOrEmpty(),
            clientId = prefs[Keys.CLIENT_ID] ?: ""
        )
    }

    suspend fun getOrCreateServerDeviceId(): String {
        val prefs = context.dataStore.data.first()
        val existing = prefs[Keys.SERVER_DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val generated = "hv-" + UUID.randomUUID().toString().replace("-", "").take(12)
        context.dataStore.edit { it[Keys.SERVER_DEVICE_ID] = generated }
        return generated
    }

    suspend fun getOrCreateClientId(): String {
        val prefs = context.dataStore.data.first()
        val existing = prefs[Keys.CLIENT_ID]
        if (!existing.isNullOrBlank()) return existing
        val generated = "cl-" + UUID.randomUUID().toString().replace("-", "").take(12)
        context.dataStore.edit { it[Keys.CLIENT_ID] = generated }
        return generated
    }

    suspend fun setAppMode(mode: AppMode) {
        context.dataStore.edit { it[Keys.APP_MODE] = mode.name }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setLanguage(lang: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = lang }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }
    }

    suspend fun setActiveDeviceId(deviceId: String) {
        context.dataStore.edit { it[Keys.ACTIVE_DEVICE_ID] = deviceId }
    }

    suspend fun updateServerConfig(
        deviceName: String? = null,
        port: Int? = null,
        storageUri: String? = null,
        storageLabel: String? = null,
        startOnBoot: Boolean? = null,
        shouldBeRunning: Boolean? = null,
        relayUrl: String? = null,
        directEndpointUrl: String? = null
    ) {
        context.dataStore.edit { prefs ->
            deviceName?.let { prefs[Keys.SERVER_DEVICE_NAME] = it }
            port?.let { prefs[Keys.SERVER_PORT] = it }
            storageUri?.let { prefs[Keys.SERVER_STORAGE_URI] = it }
            storageLabel?.let { prefs[Keys.SERVER_STORAGE_LABEL] = it }
            startOnBoot?.let { prefs[Keys.START_ON_BOOT] = it }
            shouldBeRunning?.let { prefs[Keys.SERVER_SHOULD_RUN] = it }
            relayUrl?.let { prefs[Keys.RELAY_URL] = it }
            directEndpointUrl?.let { prefs[Keys.DIRECT_ENDPOINT_URL] = it }
        }
    }

    suspend fun updateTransferSettings(
        maxConcurrent: Int? = null,
        wifiOnly: Boolean? = null,
        retryFailed: Boolean? = null
    ) {
        context.dataStore.edit { prefs ->
            maxConcurrent?.let { prefs[Keys.MAX_CONCURRENT_TRANSFERS] = it.coerceIn(1, 5) }
            wifiOnly?.let { prefs[Keys.WIFI_ONLY_TRANSFERS] = it }
            retryFailed?.let { prefs[Keys.RETRY_FAILED_TRANSFERS] = it }
        }
    }

    suspend fun savePin(pin: String) {
        val (salt, hash) = cryptoManager.hashPin(pin)
        val encSalt = cryptoManager.encryptString(salt)
        val encHash = cryptoManager.encryptString(hash)
        context.dataStore.edit { prefs ->
            prefs[Keys.PIN_SALT_ENC] = encSalt
            prefs[Keys.PIN_HASH_ENC] = encHash
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val prefs = context.dataStore.data.first()
        val encSalt = prefs[Keys.PIN_SALT_ENC] ?: return false
        val encHash = prefs[Keys.PIN_HASH_ENC] ?: return false
        val salt = cryptoManager.decryptString(encSalt)
        val hash = cryptoManager.decryptString(encHash)
        return cryptoManager.verifyPin(pin, salt, hash)
    }
}
