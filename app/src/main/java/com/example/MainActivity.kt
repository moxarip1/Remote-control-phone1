package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.client.ClientViewModel
import com.example.domain.model.AppMode
import com.example.domain.model.FileCategory
import com.example.domain.model.RemoteFileItem
import com.example.server.ServerViewModel
import com.example.ui.navigation.ClientDevicesRoute
import com.example.ui.navigation.ClientFilesRoute
import com.example.ui.navigation.ClientHomeRoute
import com.example.ui.navigation.ClientStorageRoute
import com.example.ui.navigation.ClientTransfersRoute
import com.example.ui.navigation.FilePreviewRoute
import com.example.ui.navigation.ModeSelectRoute
import com.example.ui.navigation.QrScannerRoute
import com.example.ui.navigation.ServerDashboardRoute
import com.example.ui.navigation.ServerSetupRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.screens.ModeSelectionScreen
import com.example.ui.screens.client.ClientHomeScreen
import com.example.ui.screens.client.DevicesScreen
import com.example.ui.screens.client.FileManagerScreen
import com.example.ui.screens.client.FilePreviewScreen
import com.example.ui.screens.client.QrScannerScreen
import com.example.ui.screens.client.StorageDashboardScreen
import com.example.ui.screens.client.TransfersScreen
import com.example.ui.screens.server.ServerDashboardScreen
import com.example.ui.screens.server.ServerSetupWizardScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.theme.HomeVaultTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HomeVaultApplication).container

        setContent {
            val settings by container.settingsStore.settingsFlow.collectAsStateWithLifecycle(
                initialValue = com.example.database.AppSettings()
            )
            HomeVaultTheme(themeMode = settings.themeMode) {
                val clientViewModel: ClientViewModel = viewModel(
                    factory = ClientViewModel.Factory(
                        context = applicationContext,
                        settingsStore = container.settingsStore,
                        pairedServerDao = container.database.pairedServerDao(),
                        recentFileDao = container.database.recentFileDao(),
                        clientApi = container.clientApi,
                        transferManager = container.transferManager,
                        nsdService = container.nsdDiscoveryService
                    )
                )
                val serverViewModel: ServerViewModel = viewModel(
                    factory = ServerViewModel.Factory(
                        context = applicationContext,
                        settingsStore = container.settingsStore,
                        sessionManager = container.serverSessionManager,
                        activityDao = container.database.serverActivityDao()
                    )
                )

                HomeVaultAppRoot(
                    appMode = settings.appMode,
                    hasServerPin = settings.hasPinConfigured,
                    onSetAppMode = { mode ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            container.settingsStore.setAppMode(mode)
                        }
                    },
                    onUpdateTheme = { theme ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            container.settingsStore.setThemeMode(theme)
                        }
                    },
                    onUpdateLanguage = { lang ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            container.settingsStore.setLanguage(lang)
                        }
                    },
                    onUpdateAutoReconnect = { enabled ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            container.settingsStore.setAutoReconnect(enabled)
                        }
                    },
                    onUpdateTransferSettings = { maxConc, wifiOnly, retryFailed ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            container.settingsStore.updateTransferSettings(maxConc, wifiOnly, retryFailed)
                        }
                    },
                    clientViewModel = clientViewModel,
                    serverViewModel = serverViewModel
                )
            }
        }
    }
}

private data class ClientNavTab(
    val route: Any,
    val label: String,
    val icon: ImageVector,
    val tag: String
)

@Composable
fun HomeVaultAppRoot(
    appMode: AppMode,
    hasServerPin: Boolean,
    onSetAppMode: (AppMode) -> Unit,
    onUpdateTheme: (com.example.domain.model.ThemeMode) -> Unit,
    onUpdateLanguage: (String) -> Unit,
    onUpdateAutoReconnect: (Boolean) -> Unit,
    onUpdateTransferSettings: (Int?, Boolean?, Boolean?) -> Unit,
    clientViewModel: ClientViewModel,
    serverViewModel: ServerViewModel
) {
    val navController = rememberNavController()
    val clientState by clientViewModel.uiState.collectAsStateWithLifecycle()
    val serverState by serverViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(clientState.infoMessage) {
        val msg = clientState.infoMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        clientViewModel.clearMessages()
    }

    val startDestination: Any = when (appMode) {
        AppMode.UNSET -> ModeSelectRoute
        AppMode.CLIENT -> ClientHomeRoute
        AppMode.SERVER -> if (hasServerPin) ServerDashboardRoute else ServerSetupRoute
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination

    val showClientShellNav = currentDest?.let { dest ->
        dest.hasRoute<ClientHomeRoute>() ||
            dest.hasRoute<ClientFilesRoute>() ||
            dest.hasRoute<ClientTransfersRoute>() ||
            dest.hasRoute<ClientStorageRoute>() ||
            (dest.hasRoute<SettingsRoute>() && appMode == AppMode.CLIENT)
    } == true

    val clientTabs = remember {
        listOf(
            ClientNavTab(ClientHomeRoute, "Home", Icons.Rounded.Home, "nav_tab_home"),
            ClientNavTab(ClientFilesRoute(false), "Files", Icons.Rounded.Folder, "nav_tab_files"),
            ClientNavTab(ClientTransfersRoute, "Transfers", Icons.Rounded.SwapVert, "nav_tab_transfers"),
            ClientNavTab(ClientStorageRoute, "Storage", Icons.Rounded.PieChart, "nav_tab_storage"),
            ClientNavTab(SettingsRoute, "Settings", Icons.Rounded.Settings, "nav_tab_settings")
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 700.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (showClientShellNav && !useNavigationRail) {
                    NavigationBar(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                        clientTabs.forEach { tab ->
                            val selected = when (tab.route) {
                                is ClientHomeRoute -> currentDest?.hasRoute<ClientHomeRoute>() == true
                                is ClientFilesRoute -> currentDest?.hasRoute<ClientFilesRoute>() == true
                                is ClientTransfersRoute -> currentDest?.hasRoute<ClientTransfersRoute>() == true
                                is ClientStorageRoute -> currentDest?.hasRoute<ClientStorageRoute>() == true
                                is SettingsRoute -> currentDest?.hasRoute<SettingsRoute>() == true
                                else -> false
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(ClientHomeRoute) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showClientShellNav && useNavigationRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        clientTabs.forEach { tab ->
                            val selected = when (tab.route) {
                                is ClientHomeRoute -> currentDest?.hasRoute<ClientHomeRoute>() == true
                                is ClientFilesRoute -> currentDest?.hasRoute<ClientFilesRoute>() == true
                                is ClientTransfersRoute -> currentDest?.hasRoute<ClientTransfersRoute>() == true
                                is ClientStorageRoute -> currentDest?.hasRoute<ClientStorageRoute>() == true
                                is SettingsRoute -> currentDest?.hasRoute<SettingsRoute>() == true
                                else -> false
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(ClientHomeRoute) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = startDestination
                    ) {
                        composable<ModeSelectRoute> {
                            ModeSelectionScreen(
                                onSelectClient = {
                                    onSetAppMode(AppMode.CLIENT)
                                    navController.navigate(ClientHomeRoute) {
                                        popUpTo(ModeSelectRoute) { inclusive = true }
                                    }
                                },
                                onSelectServer = {
                                    onSetAppMode(AppMode.SERVER)
                                    val dest = if (hasServerPin) ServerDashboardRoute else ServerSetupRoute
                                    navController.navigate(dest) {
                                        popUpTo(ModeSelectRoute) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable<ServerSetupRoute> {
                            ServerSetupWizardScreen(
                                state = serverState,
                                onBack = {
                                    onSetAppMode(AppMode.UNSET)
                                    navController.navigate(ModeSelectRoute) {
                                        popUpTo(ServerSetupRoute) { inclusive = true }
                                    }
                                },
                                onFinishSetup = { name, uri, label, pin, port, relayUrl, startOnBoot ->
                                    serverViewModel.saveWizardSetup(name, uri, label, pin, port, relayUrl, startOnBoot)
                                    navController.navigate(ServerDashboardRoute) {
                                        popUpTo(ServerSetupRoute) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable<ServerDashboardRoute> {
                            ServerDashboardScreen(
                                state = serverState,
                                onStartServer = { serverViewModel.startServer() },
                                onStopServer = { serverViewModel.stopServer() },
                                onRegeneratePairingCode = { serverViewModel.regeneratePairingCode() },
                                onOpenFilesWizard = { navController.navigate(ServerSetupRoute) },
                                onOpenSettings = { navController.navigate(SettingsRoute) },
                                onOpenBatterySettings = { serverViewModel.openBatteryOptimizationSettings() }
                            )
                        }

                        composable<ClientHomeRoute> {
                            ClientHomeScreen(
                                state = clientState,
                                onRefresh = { clientViewModel.refreshConnectionAndFiles() },
                                onOpenFiles = { navController.navigate(ClientFilesRoute(false)) },
                                onOpenSearch = { navController.navigate(ClientFilesRoute(true)) },
                                onOpenTransfers = { navController.navigate(ClientTransfersRoute) },
                                onOpenDevices = { navController.navigate(ClientDevicesRoute) },
                                onOpenSettings = { navController.navigate(SettingsRoute) },
                                onUploadUris = { uris ->
                                    clientViewModel.uploadUris(uris)
                                    navController.navigate(ClientTransfersRoute)
                                },
                                onPreviewFile = { item ->
                                    navController.navigate(
                                        FilePreviewRoute(
                                            path = item.path,
                                            name = item.name,
                                            mimeType = item.mimeType,
                                            sizeBytes = item.sizeBytes,
                                            categoryName = item.category.name
                                        )
                                    )
                                }
                            )
                        }

                        composable<ClientFilesRoute> { entry ->
                            val args = entry.toRoute<ClientFilesRoute>()
                            FileManagerScreen(
                                state = clientState,
                                initialFocusSearch = args.focusSearch,
                                onOpenFolder = { path -> clientViewModel.loadDirectory(path, reset = true) },
                                onNavigateUp = { clientViewModel.navigateUp() },
                                onToggleSelect = { path -> clientViewModel.toggleSelection(path) },
                                onSelectAll = { clientViewModel.selectAllVisible() },
                                onClearSelection = { clientViewModel.clearSelection() },
                                onCreateFolder = { name -> clientViewModel.createFolder(name) },
                                onRename = { path, newName -> clientViewModel.renameSelected(path, newName) },
                                onDelete = { paths -> clientViewModel.deletePaths(paths) },
                                onCopy = { paths, dest -> clientViewModel.copyPaths(paths, dest) },
                                onMove = { paths, dest -> clientViewModel.movePaths(paths, dest) },
                                onUploadUris = { uris -> clientViewModel.uploadUris(uris) },
                                onDownloadFile = { file, uri -> clientViewModel.downloadFileToUri(file, uri) },
                                onShareFile = { file -> clientViewModel.shareRemoteFile(file) },
                                onPreviewFile = { item ->
                                    navController.navigate(
                                        FilePreviewRoute(
                                            path = item.path,
                                            name = item.name,
                                            mimeType = item.mimeType,
                                            sizeBytes = item.sizeBytes,
                                            categoryName = item.category.name
                                        )
                                    )
                                },
                                onSearchChanged = { q, cat -> clientViewModel.updateSearch(q, cat) },
                                onLoadThumbnail = { path -> clientViewModel.loadThumbnail(path) },
                                onLoadMore = { clientViewModel.loadDirectory(clientState.currentPath, reset = false) }
                            )
                        }

                        composable<ClientTransfersRoute> {
                            TransfersScreen(
                                state = clientState,
                                onPause = { clientViewModel.pauseTransfer(it) },
                                onResume = { clientViewModel.resumeTransfer(it) },
                                onCancel = { clientViewModel.cancelTransfer(it) },
                                onClearHistory = { clientViewModel.clearTransferHistory() }
                            )
                        }

                        composable<ClientStorageRoute> {
                            StorageDashboardScreen(
                                state = clientState,
                                onRefresh = { clientViewModel.refreshConnectionAndFiles() }
                            )
                        }

                        composable<ClientDevicesRoute> {
                            DevicesScreen(
                                state = clientState,
                                onBack = { navController.popBackStack() },
                                onSelectServer = { id ->
                                    clientViewModel.selectServer(id)
                                    navController.popBackStack()
                                },
                                onScanQr = { navController.navigate(QrScannerRoute) },
                                onRefreshLan = { clientViewModel.startLanScan() },
                                onPairManual = { host, port, relay, devId, devName, code, pin, fp ->
                                    clientViewModel.pairWithDetails(host, port, relay, devId, devName, code, pin, fp)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Pairing with $devName...")
                                    }
                                },
                                onRenameServer = { id, name -> clientViewModel.renameServer(id, name) },
                                onDisconnectServer = { id -> clientViewModel.disconnectServer(id) },
                                onRemoveServer = { id -> clientViewModel.removeServer(id) }
                            )
                        }

                        composable<QrScannerRoute> {
                            QrScannerScreen(
                                onBack = { navController.popBackStack() },
                                onQrScannedWithPin = { qrJson, pin ->
                                    clientViewModel.pairWithQrJson(qrJson, pin)
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable<FilePreviewRoute> { entry ->
                            val route = entry.toRoute<FilePreviewRoute>()
                            val file = RemoteFileItem(
                                id = route.path,
                                name = route.name,
                                path = route.path,
                                parentPath = route.path.substringBeforeLast('/', ""),
                                isDirectory = false,
                                sizeBytes = route.sizeBytes,
                                mimeType = route.mimeType,
                                lastModifiedEpochMs = 0L,
                                hasThumbnail = false,
                                category = runCatching { FileCategory.valueOf(route.categoryName) }
                                    .getOrDefault(FileCategory.OTHER)
                            )
                            FilePreviewScreen(
                                file = file,
                                onBack = { navController.popBackStack() },
                                onDownloadToUri = { f, uri -> clientViewModel.downloadFileToUri(f, uri) },
                                onShare = { clientViewModel.shareRemoteFile(it) },
                                onOpenWithOtherApp = { clientViewModel.openWithAnotherApp(it) },
                                onFetchPreviewFile = { path -> clientViewModel.fetchFileBytesForPreview(path) },
                                onGetMediaStreamSpec = { path -> clientViewModel.getMediaStreamSpec(path) }
                            )
                        }

                        composable<SettingsRoute> {
                            SettingsScreen(
                                settings = serverState.settings,
                                serverIsRunning = serverState.runtime.isRunning,
                                onBack = if (appMode == AppMode.SERVER) {
                                    { navController.popBackStack() }
                                } else null,
                                onThemeChanged = onUpdateTheme,
                                onLanguageChanged = onUpdateLanguage,
                                onAutoReconnectChanged = onUpdateAutoReconnect,
                                onStartServer = { serverViewModel.startServer() },
                                onStopServer = { serverViewModel.stopServer() },
                                onUpdateServerConfig = { name, port, relay, direct, boot ->
                                    serverViewModel.updateServerSettings(name, port, relay, direct, boot)
                                },
                                onUpdateServerFolder = { uri, label ->
                                    serverViewModel.updateStorageFolder(uri, label)
                                },
                                onChangePin = { serverViewModel.updatePin(it) },
                                onRevokeAllSessions = { serverViewModel.revokeAllSessions() },
                                onRegeneratePairingCode = { serverViewModel.regeneratePairingCode() },
                                onLogoutAllClients = { serverViewModel.logoutAllClients() },
                                onDisconnectActiveDevice = {
                                    clientState.activeServer?.let { clientViewModel.disconnectServer(it.deviceId) }
                                },
                                onUpdateTransferSettings = onUpdateTransferSettings,
                                onOpenBatterySettings = { serverViewModel.openBatteryOptimizationSettings() },
                                onSwitchAppMode = {
                                    onSetAppMode(AppMode.UNSET)
                                    navController.navigate(ModeSelectRoute) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
