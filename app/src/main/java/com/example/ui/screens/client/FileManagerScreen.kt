package com.example.ui.screens.client

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.client.ClientUiState
import com.example.domain.model.FileCategory
import com.example.domain.model.RemoteFileItem
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.VaultErrorBanner
import com.example.ui.components.fileCategoryIcon
import com.example.ui.util.Formatters

private val thumbnailLruCache = LruCache<String, ImageBitmap>(80)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    state: ClientUiState,
    initialFocusSearch: Boolean,
    onOpenFolder: (String) -> Unit,
    onNavigateUp: () -> Boolean,
    onToggleSelect: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (List<String>) -> Unit,
    onCopy: (List<String>, String) -> Unit,
    onMove: (List<String>, String) -> Unit,
    onUploadUris: (List<Uri>) -> Unit,
    onDownloadFile: (RemoteFileItem, Uri) -> Unit,
    onShareFile: (RemoteFileItem) -> Unit,
    onPreviewFile: (RemoteFileItem) -> Unit,
    onSearchChanged: (String, FileCategory?) -> Unit,
    onLoadThumbnail: suspend (String) -> ByteArray?,
    onLoadMore: () -> Unit
) {
    var searchVisible by remember { mutableStateOf(initialFocusSearch || state.searchQuery.isNotBlank()) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<RemoteFileItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var moveOrCopyMode by remember { mutableStateOf<Boolean?>(null) } // true=move, false=copy
    var pendingDownloadFile by remember { mutableStateOf<RemoteFileItem?>(null) }

    val selectionActive = state.selectedPaths.isNotEmpty()

    BackHandler(enabled = selectionActive || state.currentPath.isNotEmpty()) {
        if (selectionActive) {
            onClearSelection()
        } else {
            onNavigateUp()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(25)
    ) { uris ->
        if (uris.isNotEmpty()) onUploadUris(uris)
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onUploadUris(uris)
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val file = pendingDownloadFile
        pendingDownloadFile = null
        if (uri != null && file != null) {
            onDownloadFile(file, uri)
        }
    }

    val itemsToDisplay = if (searchVisible && (state.searchQuery.isNotBlank() || state.searchCategory != null)) {
        state.searchResults
    } else {
        state.files
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    if (selectionActive) {
                        Text("${state.selectedPaths.size} selected")
                    } else {
                        Column {
                            Text(
                                text = state.currentPath.ifEmpty { state.activeServer?.deviceName ?: "Files" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.currentPath.isNotEmpty()) {
                                Text(
                                    text = "/${state.currentPath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    when {
                        selectionActive -> {
                            IconButton(onClick = onClearSelection) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear selection")
                            }
                        }
                        state.currentPath.isNotEmpty() -> {
                            IconButton(onClick = { onNavigateUp() }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Up folder")
                            }
                        }
                    }
                },
                actions = {
                    if (selectionActive) {
                        IconButton(onClick = onSelectAll, modifier = Modifier.testTag("select_all_button")) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = "Select All")
                        }
                        if (state.selectedPaths.size == 1) {
                            val selectedItem = itemsToDisplay.firstOrNull { it.path == state.selectedPaths.first() }
                            if (selectedItem != null) {
                                IconButton(onClick = { showRenameDialog = selectedItem }) {
                                    Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = "Rename")
                                }
                                if (!selectedItem.isDirectory) {
                                    IconButton(onClick = {
                                        pendingDownloadFile = selectedItem
                                        saveFileLauncher.launch(selectedItem.name)
                                    }) {
                                        Icon(Icons.Rounded.CloudDownload, contentDescription = "Download")
                                    }
                                    IconButton(onClick = { onShareFile(selectedItem) }) {
                                        Icon(Icons.Rounded.Share, contentDescription = "Share")
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { moveOrCopyMode = false }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = { moveOrCopyMode = true }) {
                            Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = "Move")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                        }
                    } else {
                        IconButton(
                            onClick = {
                                searchVisible = !searchVisible
                                if (!searchVisible) onSearchChanged("", null)
                            },
                            modifier = Modifier.testTag("toggle_search_button")
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search files")
                        }
                        IconButton(
                            onClick = { showCreateFolderDialog = true },
                            modifier = Modifier.testTag("create_folder_button")
                        ) {
                            Icon(Icons.Rounded.CreateNewFolder, contentDescription = "New Folder")
                        }
                    }
                }
            )

            // Search Bar + Category Filter Chips
            if (searchVisible) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { onSearchChanged(it, state.searchCategory) },
                        placeholder = { Text("Search name, *.mp4, *.jpg, *.pdf...") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchChanged("", null) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("file_search_input")
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.searchCategory == null,
                            onClick = { onSearchChanged(state.searchQuery, null) },
                            label = { Text("All") }
                        )
                        listOf(
                            FileCategory.IMAGE to "Images",
                            FileCategory.VIDEO to "Videos",
                            FileCategory.AUDIO to "Audio",
                            FileCategory.DOCUMENT to "Docs",
                            FileCategory.ARCHIVE to "Archives"
                        ).forEach { (cat, label) ->
                            FilterChip(
                                selected = state.searchCategory == cat,
                                onClick = {
                                    onSearchChanged(
                                        state.searchQuery,
                                        if (state.searchCategory == cat) null else cat
                                    )
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // Breadcrumbs
            if (!searchVisible && state.currentPath.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TextButton(onClick = { onOpenFolder("") }) { Text("Root") }
                    val segments = state.currentPath.split('/')
                    segments.forEachIndexed { idx, seg ->
                        Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val target = segments.take(idx + 1).joinToString("/")
                        TextButton(onClick = { onOpenFolder(target) }) { Text(seg) }
                    }
                }
            }

            if (state.statusBannerError != null) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    VaultErrorBanner(message = state.statusBannerError)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter
            ) {
                when {
                    state.isLoadingFiles || state.isSearching -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    itemsToDisplay.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (searchVisible) "No matching files found." else "This folder is empty.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .widthIn(max = 600.dp)
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(itemsToDisplay, key = { it.path }) { item ->
                                val isSelected = item.path in state.selectedPaths
                                FileRowCard(
                                    item = item,
                                    isSelected = isSelected,
                                    selectionMode = selectionActive,
                                    onLoadThumbnail = onLoadThumbnail,
                                    onClick = {
                                        if (selectionActive) {
                                            onToggleSelect(item.path)
                                        } else if (item.isDirectory) {
                                            onOpenFolder(item.path)
                                        } else {
                                            onPreviewFile(item)
                                        }
                                    },
                                    onToggleSelect = { onToggleSelect(item.path) },
                                    onDownloadClick = {
                                        pendingDownloadFile = item
                                        saveFileLauncher.launch(item.name)
                                    }
                                )
                            }

                            if (!searchVisible && state.hasMoreFiles) {
                                item {
                                    TextButton(
                                        onClick = onLoadMore,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Load more files")
                                    }
                                }
                            }

                            item { Spacer(modifier = Modifier.height(84.dp)) }
                        }
                    }
                }
            }
        }

        if (!selectionActive && state.activeServer != null) {
            FloatingActionButton(
                onClick = { showUploadDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
                    .testTag("files_upload_fab")
            ) {
                Icon(Icons.Rounded.CloudUpload, contentDescription = "Upload files")
            }
        }
    }

    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier.testTag("new_folder_name_input")
                )
            },
            confirmButton = {
                Button(onClick = {
                    onCreateFolder(folderName)
                    showCreateFolderDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    showRenameDialog?.let { target ->
        var newName by remember(target.path) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRename(target.path, newName)
                    showRenameDialog = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm) {
        ConfirmActionDialog(
            title = "Delete ${state.selectedPaths.size} item(s)?",
            message = "This will permanently delete the selected items from Home Storage.",
            confirmLabel = "Delete",
            onConfirm = {
                showDeleteConfirm = false
                onDelete(state.selectedPaths.toList())
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    moveOrCopyMode?.let { isMove ->
        var destPath by remember { mutableStateOf(state.currentPath) }
        AlertDialog(
            onDismissRequest = { moveOrCopyMode = null },
            title = { Text(if (isMove) "Move Items To Folder" else "Copy Items To Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter destination folder path relative to Home Storage root (leave empty for root):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = destPath,
                        onValueChange = { destPath = it },
                        label = { Text("Destination Folder") },
                        placeholder = { Text("e.g. Backups/Photos") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val paths = state.selectedPaths.toList()
                    moveOrCopyMode = null
                    if (isMove) onMove(paths, destPath.trim()) else onCopy(paths, destPath.trim())
                }) { Text(if (isMove) "Move" else "Copy") }
            },
            dismissButton = {
                TextButton(onClick = { moveOrCopyMode = null }) { Text("Cancel") }
            }
        )
    }

    if (showUploadDialog) {
        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            title = { Text("Upload to ${state.currentPath.ifEmpty { "Root" }}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            showUploadDialog = false
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Photos & Videos")
                    }
                    OutlinedButton(
                        onClick = {
                            showUploadDialog = false
                            documentPickerLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.CloudUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Any Files / Documents")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUploadDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FileRowCard(
    item: RemoteFileItem,
    isSelected: Boolean,
    selectionMode: Boolean,
    onLoadThumbnail: suspend (String) -> ByteArray?,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onDownloadClick: () -> Unit
) {
    var thumbBitmap by remember(item.path) { mutableStateOf(thumbnailLruCache.get(item.path)) }
    LaunchedEffect(item.path, item.hasThumbnail) {
        if (item.hasThumbnail && thumbBitmap == null) {
            val bytes = onLoadThumbnail(item.path)
            if (bytes != null) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                if (bmp != null) {
                    thumbnailLruCache.put(item.path, bmp)
                    thumbBitmap = bmp
                }
            }
        }
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (thumbBitmap != null) {
                    Image(
                        bitmap = thumbBitmap!!,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = fileCategoryIcon(item.isDirectory, item.category, item.mimeType),
                        contentDescription = item.name,
                        tint = if (item.isDirectory) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = if (item.isDirectory) {
                    "Folder • ${Formatters.formatRelativeTime(item.lastModifiedEpochMs)}"
                } else {
                    "${Formatters.formatBytes(item.sizeBytes)} • ${Formatters.formatRelativeTime(item.lastModifiedEpochMs)}"
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() }
                )
            } else {
                if (!item.isDirectory) {
                    IconButton(onClick = onDownloadClick) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = "Download ${item.name}")
                    }
                }
                IconButton(onClick = onToggleSelect) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                        contentDescription = "Select ${item.name}"
                    )
                }
            }
        }
    }
}
