package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.client.HomeVaultClientApi
import com.example.database.HomeVaultDatabase
import com.example.domain.model.ConnectionRoute
import com.example.domain.model.FileCategory
import com.example.domain.model.PairedServer
import com.example.domain.model.PairingPayload
import com.example.domain.model.UploadInitRequest
import com.example.network.ConnectionRouter
import com.example.security.CryptoManager
import com.example.server.HomeVaultHttpServer
import com.example.server.SessionManager
import com.example.storage.LocalDirectoryStorageProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EndToEndServerClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: HomeVaultDatabase
    private lateinit var rootDir: File
    private lateinit var server: HomeVaultHttpServer
    private lateinit var sessionManager: SessionManager
    private lateinit var cryptoManager: CryptoManager
    private lateinit var clientApi: HomeVaultClientApi
    private var serverPort: Int = 0

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, HomeVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rootDir = tempFolder.newFolder("home_storage")
        cryptoManager = CryptoManager()
        sessionManager = SessionManager(cryptoManager = cryptoManager)
        val storage = LocalDirectoryStorageProvider(rootDir, cryptoManager)
        server = HomeVaultHttpServer(
            deviceId = "hv-e2e-1",
            deviceNameProvider = { "Home Storage" },
            storageProviderFactory = { storage },
            pinVerifier = { it == "246810" },
            sessionManager = sessionManager,
            cryptoManager = cryptoManager
        )
        serverPort = server.start(port = 0, useTls = true)
        clientApi = HomeVaultClientApi(db.pairedServerDao(), cryptoManager)
    }

    @After
    fun teardown() {
        server.stop()
        db.close()
    }

    @Test
    fun `full E2E pairing, folder CRUD, chunked upload with SHA256, range resume download, and wildcard search`() = runTest {
        val pairing = sessionManager.generateOrGetPairingCode(true)
        val payload = PairingPayload(
            deviceId = "hv-e2e-1",
            deviceName = "Home Storage",
            localIp = "127.0.0.1",
            port = serverPort,
            pairingCode = pairing.code,
            tlsFingerprint = server.tlsIdentity!!.sha256Fingerprint,
            expiresAtEpochMs = pairing.expiresAtMs
        )

        // 1. Pair client over real pinned TLS socket
        val paired = clientApi.pairWithServer(payload, "246810", "cl-e2e", "MainPhone")
        assertEquals("hv-e2e-1", paired.deviceId)

        // 2. Create folders, list, rename, copy, move, delete
        val docsFolder = clientApi.createFolder("hv-e2e-1", "cl-e2e", "", "Docs")
        assertTrue(docsFolder.isDirectory)
        clientApi.createFolder("hv-e2e-1", "cl-e2e", "", "Backup")

        val listRoot = clientApi.listFiles("hv-e2e-1", "cl-e2e", "")
        assertEquals(2, listRoot.totalCount)

        // 3. Large multi-chunk upload + SHA-256 verification
        val payloadBytes = Random(42).nextBytes(1_200_000) // ~1.2 MB multi-chunk payload
        val expectedSha = cryptoManager.sha256Hex(payloadBytes)
        val initResp = clientApi.initUpload(
            "hv-e2e-1",
            "cl-e2e",
            UploadInitRequest(
                targetFolderPath = "Docs",
                fileName = "backup_clip.mp4",
                totalBytes = payloadBytes.size.toLong(),
                chunkSize = 512 * 1024,
                expectedSha256 = expectedSha,
                mimeType = "video/mp4"
            )
        )
        val chunkSize = 512 * 1024
        var offset = 0
        while (offset < payloadBytes.size) {
            val end = minOf(offset + chunkSize, payloadBytes.size)
            val slice = payloadBytes.copyOfRange(offset, end)
            clientApi.uploadChunk("hv-e2e-1", "cl-e2e", initResp.uploadId, offset.toLong(), slice)
            offset = end
        }
        val uploadedItem = clientApi.completeUpload("hv-e2e-1", "cl-e2e", initResp.uploadId, expectedSha)
        assertEquals("backup_clip.mp4", uploadedItem.name)
        assertEquals(payloadBytes.size.toLong(), uploadedItem.sizeBytes)

        // 4. Interrupted download resume via HTTP Range header
        val firstHalf = clientApi.openDownloadResponse("hv-e2e-1", "cl-e2e", "Docs/backup_clip.mp4", 0L).use { resp ->
            resp.body!!.byteStream().readNBytes(500_000)
        }
        val secondHalf = clientApi.openDownloadResponse("hv-e2e-1", "cl-e2e", "Docs/backup_clip.mp4", 500_000L).use { resp ->
            assertEquals(206, resp.code)
            resp.body!!.bytes()
        }
        val reassembled = firstHalf + secondHalf
        assertArrayEquals(payloadBytes, reassembled)

        // 5. Wildcard search (*.mp4)
        val mp4Results = clientApi.searchFiles("hv-e2e-1", "cl-e2e", "*.mp4", FileCategory.VIDEO)
        assertEquals(1, mp4Results.size)
        assertEquals("Docs/backup_clip.mp4", mp4Results.first().path)

        // 6. Rename, copy, move, delete
        val renamed = clientApi.renameFile("hv-e2e-1", "cl-e2e", "Docs/backup_clip.mp4", "holiday.mp4")
        assertEquals("holiday.mp4", renamed.name)
        clientApi.copyFiles("hv-e2e-1", "cl-e2e", listOf("Docs/holiday.mp4"), "Backup")
        assertTrue(File(rootDir, "Backup/holiday.mp4").exists())
        clientApi.deleteFiles("hv-e2e-1", "cl-e2e", listOf("Backup/holiday.mp4"))
        assertTrue(!File(rootDir, "Backup/holiday.mp4").exists())

        // 7. Storage info & largest files
        val storageInfo = clientApi.fetchStorageInfo("hv-e2e-1", "cl-e2e")
        assertTrue(storageInfo.totalBytes > 0L)
        assertEquals(1, storageInfo.fileCount)
        assertEquals("holiday.mp4", storageInfo.largestFiles.first().name)
    }

    @Test
    fun `connection router falls back from unreachable LAN to Direct route automatically`() = runTest {
        val router = ConnectionRouter()
        val serverDomain = PairedServer(
            deviceId = "hv-e2e-1",
            deviceName = "Home Storage",
            localIp = "192.0.2.99", // TEST-NET unreachable LAN IP
            port = 59999,
            directUrl = "https://127.0.0.1:$serverPort",
            relayUrl = "",
            tlsFingerprint = server.tlsIdentity!!.sha256Fingerprint,
            lastRoute = ConnectionRoute.LAN,
            isOnline = true,
            lastConnectedEpochMs = 0L,
            usedBytes = 0L,
            totalBytes = 0L,
            fileCount = 0
        )
        val resolved = router.resolveBestRoute(serverDomain)
        assertNotNull(resolved)
        assertEquals(ConnectionRoute.DIRECT, resolved!!.route)
    }
}
