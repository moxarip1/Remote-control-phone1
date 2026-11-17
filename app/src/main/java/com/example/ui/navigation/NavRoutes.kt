package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object ModeSelectRoute

@Serializable
object ServerSetupRoute

@Serializable
object ServerDashboardRoute

@Serializable
object ClientHomeRoute

@Serializable
data class ClientFilesRoute(val focusSearch: Boolean = false)

@Serializable
object ClientTransfersRoute

@Serializable
object ClientStorageRoute

@Serializable
object ClientDevicesRoute

@Serializable
object QrScannerRoute

@Serializable
data class FilePreviewRoute(
    val path: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val categoryName: String
)

@Serializable
object SettingsRoute
