package com.example.di

import android.content.Context
import com.example.client.HomeVaultClientApi
import com.example.database.HomeVaultDatabase
import com.example.database.SettingsStore
import com.example.network.ConnectionRouter
import com.example.security.CryptoManager
import com.example.server.NsdDiscoveryService
import com.example.server.SessionManager
import com.example.transfer.TransferManager

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val cryptoManager: CryptoManager = CryptoManager()
    val settingsStore: SettingsStore = SettingsStore(appContext, cryptoManager)
    val database: HomeVaultDatabase = HomeVaultDatabase.build(appContext)
    val serverSessionManager: SessionManager = SessionManager(cryptoManager = cryptoManager)
    val nsdDiscoveryService: NsdDiscoveryService = NsdDiscoveryService(appContext)
    val connectionRouter: ConnectionRouter = ConnectionRouter()

    val clientApi: HomeVaultClientApi = HomeVaultClientApi(
        pairedServerDao = database.pairedServerDao(),
        cryptoManager = cryptoManager,
        router = connectionRouter
    )

    val transferManager: TransferManager = TransferManager(
        context = appContext,
        clientApi = clientApi,
        transferDao = database.transferDao(),
        recentFileDao = database.recentFileDao(),
        settingsStore = settingsStore,
        cryptoManager = cryptoManager
    )
}
