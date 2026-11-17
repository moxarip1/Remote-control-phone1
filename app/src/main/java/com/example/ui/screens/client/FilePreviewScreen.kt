package com.example.ui.screens.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.domain.model.FileCategory
import com.example.domain.model.RemoteFileItem
import com.example.ui.util.Formatters
import okhttp3.OkHttpClient
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun FilePreviewScreen(
    file: RemoteFileItem,
    onBack: () -> Unit,
    onDownloadToUri: (RemoteFileItem, Uri) -> Unit,
    onShare: (RemoteFileItem) -> Unit,
    onOpenWithOtherApp: (RemoteFileItem) -> Unit,
    onFetchPreviewFile: suspend (String) -> File?,
    onGetMediaStreamSpec: suspend (String) -> Triple<OkHttpClient, String, Map<String, String>>?
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var streamSpec by remember { mutableStateOf<Triple<OkHttpClient, String, Map<String, String>>?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(file.mimeType)
    ) { uri ->
        if (uri != null) onDownloadToUri(file, uri)
    }

    LaunchedEffect(file.path) {
        loading = true
        when (file.category) {
            FileCategory.VIDEO, FileCategory.AUDIO -> {
                streamSpec = onGetMediaStreamSpec(file.path)
            }
            FileCategory.IMAGE, FileCategory.TEXT -> {
                previewFile = onFetchPreviewFile(file.path)
            }
            FileCategory.DOCUMENT -> {
                if (file.mimeType == "application/pdf") {
                    previewFile = onFetchPreviewFile(file.path)
                }
            }
            else -> Unit
        }
        loading = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            Formatters.formatBytes(file.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onShare(file) }) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { saveLauncher.launch(file.name) }) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = "Download")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when {
                    loading -> CircularProgressIndicator()

                    (file.category == FileCategory.VIDEO || file.category == FileCategory.AUDIO) &&
                        streamSpec != null -> {
                        val (okClient, url, headers) = streamSpec!!
                        val exoPlayer = remember(url) {
                            val dsFactory = OkHttpDataSource.Factory(okClient)
                                .setDefaultRequestProperties(headers)
                            ExoPlayer.Builder(context)
                                .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
                                .build()
                                .apply {
                                    setMediaItem(MediaItem.fromUri(url))
                                    prepare()
                                    playWhenReady = true
                                }
                        }
                        DisposableEffect(exoPlayer) {
                            onDispose { exoPlayer.release() }
                        }
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply { player = exoPlayer }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    file.category == FileCategory.IMAGE && previewFile != null -> {
                        val bmp = remember(previewFile) {
                            BitmapFactory.decodeFile(previewFile!!.absolutePath)?.asImageBitmap()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = file.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            UnsupportedPreviewPlaceholder(file, onOpenWithOtherApp)
                        }
                    }

                    file.mimeType == "application/pdf" && previewFile != null -> {
                        PdfFileViewer(pdfFile = previewFile!!)
                    }

                    file.category == FileCategory.TEXT && previewFile != null -> {
                        val textContent = remember(previewFile) {
                            runCatching { previewFile!!.readText().take(80_000) }.getOrDefault("")
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                                .padding(14.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = textContent,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    else -> {
                        UnsupportedPreviewPlaceholder(file, onOpenWithOtherApp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onOpenWithOtherApp(file) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open with another app")
                }
                Button(
                    onClick = { saveLauncher.launch(file.name) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download")
                }
            }
        }
    }
}

@Composable
private fun PdfFileViewer(pdfFile: File) {
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(pdfFile, pageIndex) {
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        pageCount = renderer.pageCount
        if (pageCount > 0) {
            val safeIdx = pageIndex.coerceIn(0, pageCount - 1)
            val page = renderer.openPage(safeIdx)
            val bmp = Bitmap.createBitmap(
                (page.width * 2).coerceAtMost(1440),
                (page.height * 2).coerceAtMost(2048),
                Bitmap.Config.ARGB_8888
            )
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderedBitmap = bmp
        }
        onDispose {
            renderer.close()
            pfd.close()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            renderedBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "PDF Page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { if (pageIndex > 0) pageIndex-- }, enabled = pageIndex > 0) {
                Text("Prev")
            }
            Text("Page ${pageIndex + 1} / ${pageCount.coerceAtLeast(1)}")
            OutlinedButton(onClick = { if (pageIndex < pageCount - 1) pageIndex++ }, enabled = pageIndex < pageCount - 1) {
                Text("Next")
            }
        }
    }
}

@Composable
private fun UnsupportedPreviewPlaceholder(
    file: RemoteFileItem,
    onOpenWithOtherApp: (RemoteFileItem) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Preview not available for ${file.mimeType}", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { onOpenWithOtherApp(file) }) {
            Text("Open with another app")
        }
    }
}
