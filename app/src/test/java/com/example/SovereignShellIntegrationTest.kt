package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ConfiguredBlossomServerEntity
import com.example.data.LauncherPreferencesRepository
import com.example.data.NsiteTemplateKind
import com.example.data.RelayEntity
import com.example.data.RelayHealthEntity
import com.example.data.ShellDatabase
import com.example.data.ShellRepository
import com.example.nostr.AdaptiveNetworkEngine
import com.example.nostr.BlossomClient
import com.example.nostr.KeyVaultManager
import com.example.nostr.NetworkOperationType
import com.example.nostr.NostrCrypto
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.RelayPoolClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * End-to-end integration tests verifying all 8 core SovereignShell requirements against
 * local HTTP Blossom servers and an embedded Nostr WebSocket Relay:
 * 1. Discovering and launching a real interactive multi-file NIP-5A Nsite (2048 Link).
 * 2. Importing and publishing a single HTML file.
 * 3. Importing and publishing a multi-file ZIP archive.
 * 4. Importing and publishing a multi-file Folder.
 * 5. Forking an existing multi-file Nsite, modifying it, and publishing it under another identity
 *    without overwriting or impersonating the original publisher.
 * 6. Updating an already-published site.
 * 7. Recovery from relay/Blossom failures, missing blobs, and corrupt SHA-256 hashes.
 * 8. Application restart persistence across Room DB + disk storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SovereignShellIntegrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var primaryBlossomHttp: HttpServer
    private lateinit var backupBlossomHttp: HttpServer
    private lateinit var localRelayServer: EmbeddedNostrRelayServer
    private var primaryBlossomUrl: String = ""
    private var backupBlossomUrl: String = ""
    private var localRelayWsUrl: String = ""

    private val primaryBlobs = ConcurrentHashMap<String, ByteArray>()
    private val backupBlobs = ConcurrentHashMap<String, ByteArray>()
    private var corruptNextPrimaryDownloadForSha: String? = null
    private var rejectPrimaryUploads: Boolean = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.cacheDir, "sovereign_integration_${System.nanoTime()}.db")

        primaryBlossomHttp = startLocalBlossomServer(
            store = primaryBlobs,
            shouldCorruptSha = { sha -> corruptNextPrimaryDownloadForSha == sha },
            shouldRejectUpload = { rejectPrimaryUploads }
        )
        backupBlossomHttp = startLocalBlossomServer(
            store = backupBlobs,
            shouldCorruptSha = { false },
            shouldRejectUpload = { false }
        )
        localRelayServer = EmbeddedNostrRelayServer().apply { start() }

        primaryBlossomUrl = "http://127.0.0.1:${primaryBlossomHttp.address.port}"
        backupBlossomUrl = "http://127.0.0.1:${backupBlossomHttp.address.port}"
        localRelayWsUrl = "ws://127.0.0.1:${localRelayServer.port}"
    }

    @After
    fun tearDown() {
        runCatching { localRelayServer.close() }
        runCatching { primaryBlossomHttp.stop(0) }
        runCatching { backupBlossomHttp.stop(0) }
        runCatching { dbFile.delete() }
    }

    private fun startLocalBlossomServer(
        store: ConcurrentHashMap<String, ByteArray>,
        shouldCorruptSha: (String) -> Boolean,
        shouldRejectUpload: () -> Boolean
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/upload") { ex: HttpExchange ->
            if (shouldRejectUpload()) {
                val msg = """{"message":"Simulated Blossom 503 Overload"}""".toByteArray()
                ex.sendResponseHeaders(503, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            if (ex.requestMethod.equals("HEAD", ignoreCase = true) ||
                ex.requestMethod.equals("OPTIONS", ignoreCase = true)
            ) {
                ex.responseHeaders.add("X-Max-Upload-Size", "52428800")
                ex.sendResponseHeaders(200, -1)
                ex.close()
                return@createContext
            }
            val body = ex.requestBody.use { it.readBytes() }
            val sha = NostrCrypto.sha256Hex(body)
            store[sha] = body
            val respJson = JSONObject()
                .put("sha256", sha)
                .put("size", body.size)
                .put("url", "http://127.0.0.1:${server.address.port}/$sha")
                .toString()
                .toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, respJson.size.toLong())
            ex.responseBody.use { it.write(respJson) }
        }
        server.createContext("/mirror") { ex: HttpExchange ->
            val bodyStr = ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val srcUrl = runCatching { JSONObject(bodyStr).optString("url") }.getOrDefault("")
            val sha = srcUrl.substringAfterLast('/').substringBefore('.')
            val bytes = primaryBlobs[sha] ?: backupBlobs[sha]
            if (bytes != null) {
                store[sha] = bytes
                val resp = JSONObject().put("sha256", sha).put("size", bytes.size).toString().toByteArray()
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } else {
                ex.sendResponseHeaders(404, -1)
                ex.close()
            }
        }
        server.createContext("/") { ex: HttpExchange ->
            val rawPath = ex.requestURI.path.trimStart('/')
            val sha = rawPath.substringBefore('.').lowercase()
            val stored = store[sha]
            if (stored == null) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
                return@createContext
            }
            val outBytes = if (shouldCorruptSha(sha)) {
                "CORRUPTED_TAMPERED_PAYLOAD".toByteArray()
            } else {
                stored
            }
            ex.sendResponseHeaders(200, outBytes.size.toLong())
            ex.responseBody.use { it.write(outBytes) }
        }
        server.start()
        return server
    }

    private suspend fun buildRepository(db: ShellDatabase): ShellRepository {
        val prefs = LauncherPreferencesRepository(context)
        prefs.setBlossomServer(primaryBlossomUrl)
        val repo = ShellRepository(
            context = context,
            dao = db.shellDao(),
            keyVault = KeyVaultManager(context),
            relayPool = RelayPoolClient(),
            blossomClient = BlossomClient(
                cacheDir = File(context.cacheDir, "blossom_test_${System.nanoTime()}")
            ),
            prefsRepo = prefs,
            autoBootstrap = false
        )
        db.shellDao().upsertBlossomServer(
            ConfiguredBlossomServerEntity(
                serverUrl = primaryBlossomUrl,
                enabled = true,
                isBackupMirror = false
            )
        )
        db.shellDao().upsertBlossomServer(
            ConfiguredBlossomServerEntity(
                serverUrl = backupBlossomUrl,
                enabled = true,
                isBackupMirror = true
            )
        )
        db.shellDao().upsertRelay(RelayEntity(url = localRelayWsUrl))
        repo.relayPool.syncRelays(listOf(localRelayWsUrl))
        delay(200)
        return repo
    }

    @Test
    fun endToEnd_discoverLaunchImportZipFolderForkModifyUpdateAndRestartPersistence() = runBlocking {
        val db1 = Room.databaseBuilder(context, ShellDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

        try {
            val repo = buildRepository(db1)

            // -------------------------------------------------------------------------------------
            // 1. DISCOVER & LAUNCH A REAL INTERACTIVE MULTI-FILE NSITE (2048 Link)
            // -------------------------------------------------------------------------------------
            val publisherA = NostrCrypto.generateKeyPair()
            val gameHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8" />
                  <title>2048 Link</title>
                  <link rel="stylesheet" href="./css/board.css" />
                </head>
                <body>
                  <canvas id="linkBoard" width="320" height="448"></canvas>
                  <script type="module" src="./js/engine.js"></script>
                </body>
                </html>
            """.trimIndent().toByteArray()
            val gameCss = "body { background: #000000; color: #FF1493; }".toByteArray()
            val gameJs = "export const GRID_ROWS = 7; export const GRID_COLS = 5; console.log('2048 Link ready');".toByteArray()

            val htmlSha = NostrCrypto.sha256Hex(gameHtml)
            val cssSha = NostrCrypto.sha256Hex(gameCss)
            val jsSha = NostrCrypto.sha256Hex(gameJs)

            primaryBlobs[htmlSha] = gameHtml
            primaryBlobs[cssSha] = gameCss
            primaryBlobs[jsSha] = gameJs
            backupBlobs[htmlSha] = gameHtml
            backupBlobs[cssSha] = gameCss
            backupBlobs[jsSha] = gameJs

            val nsite2048Manifest = NostrEvent.createAndSign(
                privKeyHex = publisherA.privKeyHex,
                kind = NostrKinds.NSITE_NAMED,
                content = "Interactive 5x7 tile-linking arcade game on Nostr + Blossom.",
                tags = listOf(
                    listOf("d", "2048-link"),
                    listOf("title", "2048 Link"),
                    listOf("path", "/index.html", htmlSha),
                    listOf("path", "/css/board.css", cssSha),
                    listOf("path", "/js/engine.js", jsSha),
                    listOf("server", primaryBlossomUrl),
                    listOf("server", backupBlossomUrl)
                ),
                createdAt = 1_750_000_000L
            )
            repo.ingestNostrEvent(nsite2048Manifest)

            val discoveredSite = db1.shellDao().getAppletById("35128:${publisherA.pubKeyHex}:2048-link")
            assertNotNull("2048 Link NIP-5A Nsite must be discovered & stored", discoveredSite)
            assertEquals("2048 Link", discoveredSite!!.title)
            assertEquals(htmlSha, discoveredSite.indexSha256)

            // Resolve entry document and nested relative assets (/css/board.css and /js/engine.js)
            val resolvedEntryHtml = repo.resolveAppletDocument(discoveredSite)
            assertTrue(resolvedEntryHtml.contains("<canvas id=\"linkBoard\""))
            val resolvedCss = repo.resolveAppletAsset(discoveredSite, "/css/board.css", false) { it }
            assertNotNull(resolvedCss)
            assertEquals("text/css", resolvedCss!!.mimeType)
            assertTrue(String(resolvedCss.bytes).contains("#FF1493"))
            val resolvedJs = repo.resolveAppletAsset(discoveredSite, "/js/engine.js", false) { it }
            assertNotNull(resolvedJs)
            assertEquals("text/javascript", resolvedJs!!.mimeType)
            assertTrue(String(resolvedJs.bytes).contains("GRID_ROWS = 7"))

            // -------------------------------------------------------------------------------------
            // 2. IMPORT & PUBLISH A SINGLE HTML FILE
            // -------------------------------------------------------------------------------------
            val publisherBAccount = repo.createNewAccount("Publisher B Rebel")
            val singleHtmlBytes = """
                <!DOCTYPE html>
                <html><head><title>Rebel Zine</title></head><body><h1>Single HTML Nsite</h1></body></html>
            """.trimIndent().toByteArray()

            val singleHtmlProj = repo.projectRepository.importSingleHtmlFile(
                ownerPubkeyHex = publisherBAccount.pubkeyHex,
                titleHint = "Rebel Zine",
                originalFileName = "zine.html",
                htmlBytes = singleHtmlBytes
            )
            val singleHtmlFiles = db1.shellDao().getProjectFilesList(singleHtmlProj.projectId)
            assertTrue(singleHtmlFiles.any { it.relativePath == "/index.html" })
            assertTrue(singleHtmlFiles.any { it.relativePath == "/zine.html" })

            val (singleReport, singleVerification) = repo.projectRepository.publishProjectAsNip5a(
                projectId = singleHtmlProj.projectId,
                activeAccount = publisherBAccount
            )
            assertTrue(singleVerification.verifiedUploadedBlobs >= 1)
            assertTrue(singleVerification.entryBlobVerifiedLive)
            assertTrue(primaryBlobs.containsKey(NostrCrypto.sha256Hex(singleHtmlBytes)))
            assertNotNull(singleReport.manifestEventId.takeIf { it.length == 64 })

            // -------------------------------------------------------------------------------------
            // 3. IMPORT & PUBLISH A MULTI-FILE ZIP ARCHIVE
            // -------------------------------------------------------------------------------------
            val zipBytes = buildSampleZip(
                mapOf(
                    "dist/index.html" to "<!DOCTYPE html><html><head><link rel='stylesheet' href='styles/app.css'></head><body><script src='scripts/main.js'></script></body></html>".toByteArray(),
                    "dist/styles/app.css" to "body { color: #E8DCC8; }".toByteArray(),
                    "dist/scripts/main.js" to "console.log('ZIP multi-file app active');".toByteArray()
                )
            )
            val zipProject = repo.projectRepository.importZipArchive(
                ownerPubkeyHex = publisherBAccount.pubkeyHex,
                titleHint = "Arcade Bundle",
                zipInputStream = ByteArrayInputStream(zipBytes)
            )
            val zipProjectFiles = db1.shellDao().getProjectFilesList(zipProject.projectId)
            assertEquals(
                setOf("/index.html", "/styles/app.css", "/scripts/main.js"),
                zipProjectFiles.map { it.relativePath }.toSet()
            )
            val (zipReport, zipVerification) = repo.projectRepository.publishProjectAsNip5a(
                projectId = zipProject.projectId,
                activeAccount = publisherBAccount
            )
            assertEquals(3, zipVerification.totalProjectFiles)
            assertEquals(3, zipVerification.verifiedUploadedBlobs)
            assertTrue(zipReport.gatewayPreviewUrl.contains(".nsite.lol"))

            // -------------------------------------------------------------------------------------
            // 4. IMPORT & PUBLISH A MULTI-FILE FOLDER
            // -------------------------------------------------------------------------------------
            val folderDir = File(context.cacheDir, "sample_folder_${System.nanoTime()}").apply {
                mkdirs()
                File(this, "index.html").writeText("<!DOCTYPE html><html><body>Folder Site</body></html>")
                File(this, "assets").apply {
                    mkdirs()
                    File(this, "theme.css").writeText("h1 { color: #FF1493; }")
                    File(this, "logic.js").writeText("window.folderLoaded = true;")
                }
            }
            val folderProject = repo.projectRepository.importFolderFromDirectory(
                ownerPubkeyHex = publisherBAccount.pubkeyHex,
                directory = folderDir,
                titleHint = "Folder Nsite"
            )
            val folderFiles = db1.shellDao().getProjectFilesList(folderProject.projectId)
            assertEquals(
                setOf("/index.html", "/assets/theme.css", "/assets/logic.js"),
                folderFiles.map { it.relativePath }.toSet()
            )
            val (_, folderVerification) = repo.projectRepository.publishProjectAsNip5a(
                projectId = folderProject.projectId,
                activeAccount = publisherBAccount
            )
            assertEquals(3, folderVerification.verifiedUploadedBlobs)

            // -------------------------------------------------------------------------------------
            // 5. FORK EXISTING MULTI-FILE NSITE, MODIFY IT, & PUBLISH UNDER ANOTHER IDENTITY
            //    (Verify provenance tags, modified code, surviving sibling files, and zero impersonation)
            // -------------------------------------------------------------------------------------
            val forkOutcome = repo.forkSiteIntoProject(discoveredSite)
            assertTrue("All 3 files from 2048 Link must verify cleanly on fork", forkOutcome.isComplete)
            assertEquals(3, forkOutcome.verifiedPaths.size)
            assertEquals(publisherA.pubKeyHex, forkOutcome.project.forkSourcePubkey)
            assertEquals("35128:${publisherA.pubKeyHex}:2048-link", forkOutcome.project.forkSourceCoordinate)

            // Modify /index.html in the forked project while keeping /css/board.css and /js/engine.js intact
            val modifiedForkHtml = """
                <!DOCTYPE html>
                <html>
                <head><title>2048 Link Chaos Remix</title><link rel="stylesheet" href="./css/board.css" /></head>
                <body><h1>REMIXED BY PUBLISHER B</h1><script type="module" src="./js/engine.js"></script></body>
                </html>
            """.trimIndent()
            repo.projectRepository.saveTextFile(
                projectId = forkOutcome.project.projectId,
                rawRelativePath = "/index.html",
                content = modifiedForkHtml
            )

            // Verify live preview asset resolution reflects the edit before publishing
            val previewResolved = repo.projectRepository.resolveProjectPreviewAsset(
                projectId = forkOutcome.project.projectId,
                rawPath = "/index.html",
                isMainFrame = true,
                transformRootHtml = { it }
            )
            assertNotNull(previewResolved)
            assertTrue(String(previewResolved!!.bytes).contains("REMIXED BY PUBLISHER B"))

            // Publish the forked project under Publisher B's identity
            val (forkPublishReport, forkVerification) = repo.projectRepository.publishProjectAsNip5a(
                projectId = forkOutcome.project.projectId,
                activeAccount = publisherBAccount
            )
            assertEquals(3, forkVerification.verifiedUploadedBlobs)

            // Verify Publisher A's original site was NOT overwritten and Publisher B's fork is signed by B!
            val originalStillIntact = db1.shellDao().getAppletById("35128:${publisherA.pubKeyHex}:2048-link")
            assertNotNull(originalStillIntact)
            assertEquals(htmlSha, originalStillIntact!!.indexSha256)

            val publishedForkSite = db1.shellDao().getAppletById(
                "35128:${publisherBAccount.pubkeyHex}:${forkPublishReport.dTag}"
            )
            assertNotNull("Forked Nsite must be published under Publisher B's coordinate", publishedForkSite)
            assertEquals(publisherBAccount.pubkeyHex, publishedForkSite!!.authorPubkey)
            assertNotEquals(publisherA.pubKeyHex, publishedForkSite.authorPubkey)
            assertEquals(NostrCrypto.sha256Hex(modifiedForkHtml), publishedForkSite.indexSha256)

            // Verify fork provenance tags on the signed NsiteVersionEntity
            val forkVersions = db1.shellDao().getVersionsForCoordinate(
                "35128:${publisherBAccount.pubkeyHex}:${forkPublishReport.dTag}"
            )
            assertTrue(forkVersions.isNotEmpty())
            val signedForkEvent = NostrEvent.fromJson(JSONObject(forkVersions.first().rawEventJson))!!
            assertTrue(signedForkEvent.verifySignature())
            assertEquals(publisherBAccount.pubkeyHex, signedForkEvent.pubkey)
            assertTrue(
                "Signed fork manifest must include provenance ['fork', '35128:<publisherA>:2048-link'] tag",
                signedForkEvent.tags.any {
                    it.size >= 2 && (it[0] == "fork" || it[0] == "a") && it[1] == "35128:${publisherA.pubKeyHex}:2048-link"
                }
            )
            // Also verify Soapbox Shakespeare NIP-5A /404.html SPA fallback tag is emitted
            assertTrue(
                "Signed manifest must include /404.html SPA fallback tag",
                signedForkEvent.tags.any { it.size >= 3 && it[0] == "path" && it[1] == "/404.html" }
            )

            // -------------------------------------------------------------------------------------
            // 6. UPDATE AN ALREADY-PUBLISHED SITE
            // -------------------------------------------------------------------------------------
            val updatedForkHtml = modifiedForkHtml.replace("REMIXED BY PUBLISHER B", "REMIXED V2 EDITION")
            repo.projectRepository.saveTextFile(
                projectId = forkOutcome.project.projectId,
                rawRelativePath = "/index.html",
                content = updatedForkHtml
            )
            val (updateReport, _) = repo.projectRepository.publishProjectAsNip5a(
                projectId = forkOutcome.project.projectId,
                activeAccount = publisherBAccount
            )
            val updatedEntity = db1.shellDao().getAppletById(
                "35128:${publisherBAccount.pubkeyHex}:${updateReport.dTag}"
            )
            assertEquals(NostrCrypto.sha256Hex(updatedForkHtml), updatedEntity?.indexSha256)
            val updatedProjMeta = db1.shellDao().getProjectById(forkOutcome.project.projectId)
            assertTrue("Version counter must increment on update", (updatedProjMeta?.versionCounter ?: 0) >= 3)

            // -------------------------------------------------------------------------------------
            // 7. RECOVERY FROM CORRUPT BLOSSOM HASHES, PRIMARY SERVER FAILURE & MISSING BLOBS
            // -------------------------------------------------------------------------------------
            // 7a. Primary server returns tampered bytes for a fresh un-cached blob -> AdaptiveNetworkEngine
            //     must reject HashMismatch on primary and recover verified bytes from backupBlossomUrl!
            val freshRecoveryBytes = "/* Verified CSS recovered from backup mirror */ body { margin: 0; }".toByteArray()
            val freshRecoverySha = NostrCrypto.sha256Hex(freshRecoveryBytes)
            primaryBlobs[freshRecoverySha] = freshRecoveryBytes
            backupBlobs[freshRecoverySha] = freshRecoveryBytes
            corruptNextPrimaryDownloadForSha = freshRecoverySha

            val recoveredOutcome = repo.networkEngine.fetchBlobAdaptively(
                primaryServer = primaryBlossomUrl,
                manifestAndAuthorServers = listOf(primaryBlossomUrl, backupBlossomUrl),
                sha256 = freshRecoverySha,
                extensionHint = "css"
            )
            assertTrue(
                "AdaptiveNetworkEngine must reject tampered primary payload and recover from backup mirror",
                recoveredOutcome is BlossomClient.BlobFetchOutcome.Verified
            )
            val verifiedCss = recoveredOutcome as BlossomClient.BlobFetchOutcome.Verified
            assertEquals(backupBlossomUrl, verifiedCss.sourceServer)
            assertEquals(freshRecoverySha, verifiedCss.sha256)
            corruptNextPrimaryDownloadForSha = null

            // 7b. Primary Blossom server rejects uploads (HTTP 503) -> AdaptiveNetworkEngine falls back to backup mirror
            rejectPrimaryUploads = true
            val resilientHtml = "<!DOCTYPE html><html><body>Fallback Upload Verified</body></html>"
            repo.projectRepository.saveTextFile(
                projectId = singleHtmlProj.projectId,
                rawRelativePath = "/index.html",
                content = resilientHtml
            )
            val (_, fallbackVerify) = repo.projectRepository.publishProjectAsNip5a(
                projectId = singleHtmlProj.projectId,
                activeAccount = publisherBAccount
            )
            assertTrue(fallbackVerify.entryBlobVerifiedLive)
            assertTrue(backupBlobs.containsKey(NostrCrypto.sha256Hex(resilientHtml)))
            rejectPrimaryUploads = false

            // 7c. Missing Blossom blob during Fork must be reported explicitly without inventing fake files
            val missingSha = "f".repeat(64)
            val incompleteManifest = NostrEvent.createAndSign(
                privKeyHex = publisherA.privKeyHex,
                kind = NostrKinds.NSITE_NAMED,
                content = "Site with one missing asset blob",
                tags = listOf(
                    listOf("d", "incomplete-site"),
                    listOf("title", "Incomplete Site"),
                    listOf("path", "/index.html", htmlSha),
                    listOf("path", "/missing/ghost.js", missingSha),
                    listOf("server", primaryBlossomUrl)
                ),
                createdAt = 1_750_000_500L
            )
            repo.ingestNostrEvent(incompleteManifest)
            val incompleteSite = db1.shellDao().getAppletById("35128:${publisherA.pubKeyHex}:incomplete-site")!!
            val incompleteFork = repo.forkSiteIntoProject(incompleteSite)
            assertFalse("Fork with missing blob must NOT claim isComplete = true", incompleteFork.isComplete)
            assertEquals(listOf("/missing/ghost.js"), incompleteFork.missingOrCorruptPaths.map { it.first })
            assertTrue(incompleteFork.project.incompleteImportWarning.contains("/missing/ghost.js"))
            val incompleteForkFiles = db1.shellDao().getProjectFilesList(incompleteFork.project.projectId)
            assertFalse(
                "Missing blob must NOT be replaced with fake invented content",
                incompleteForkFiles.any { it.relativePath == "/missing/ghost.js" }
            )
        } finally {
            db1.close()
        }

        // -----------------------------------------------------------------------------------------
        // 8. APPLICATION RESTART PERSISTENCE
        //    Re-open the Room database on disk and verify accounts, relays, Blossom mirrors,
        //    installed Nsites, version history, and Studio projects/files survive restart intact.
        // -----------------------------------------------------------------------------------------
        val db2 = Room.databaseBuilder(context, ShellDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            val activeAfterRestart = db2.shellDao().getActiveAccount()
            assertNotNull("Active Nostr account must persist across app restart", activeAfterRestart)

            val projectsAfterRestart = db2.shellDao().getAllProjectsList()
            assertTrue("Studio projects must persist across app restart", projectsAfterRestart.size >= 4)

            val forkedProjAfterRestart = projectsAfterRestart.firstOrNull { it.isFork && it.incompleteImportWarning.isBlank() }
            assertNotNull("Forked 2048 Link project must persist across app restart", forkedProjAfterRestart)

            val forkedFilesAfterRestart = db2.shellDao().getProjectFilesList(forkedProjAfterRestart!!.projectId)
            assertEquals(3, forkedFilesAfterRestart.size)
            val persistedIndex = forkedFilesAfterRestart.first { it.relativePath == "/index.html" }
            assertTrue(persistedIndex.textContent.contains("REMIXED V2 EDITION"))

            val blossomHealthAfterRestart = db2.shellDao().getAllBlossomHealth()
            assertTrue("Adaptive Blossom health telemetry must persist across restart", blossomHealthAfterRestart.isNotEmpty())
        } finally {
            db2.close()
        }
    }

    @Test
    fun relayAutoDiscovery_nip65RoleTargeting_andDropDeadConnections_workEndToEnd() = runBlocking {
        // 1. URL validation gate for auto-discovery
        assertTrue(AdaptiveNetworkEngine.isAutoDiscoverableRelayUrl("wss://relay.damus.io"))
        assertTrue(AdaptiveNetworkEngine.isAutoDiscoverableRelayUrl("ws://127.0.0.1:4848"))
        assertFalse(AdaptiveNetworkEngine.isAutoDiscoverableRelayUrl("wss://hiddenservice1234.onion"))
        assertFalse(AdaptiveNetworkEngine.isAutoDiscoverableRelayUrl("wss://peer.i2p"))
        assertFalse(AdaptiveNetworkEngine.isAutoDiscoverableRelayUrl("wss://user:pass@relay.example.org"))
        assertFalse(AdaptiveNetworkEngine.isAutoDiscoverableRelayUrl("wss://relay.damus.io/#frag"))
        assertFalse(AdaptiveNetworkEngine.isAutoDiscoverableRelayUrl("wss://example.com"))

        val db = Room.inMemoryDatabaseBuilder(context, ShellDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val liveDiscoveredRelay = EmbeddedNostrRelayServer().apply { start() }
        // Allocate and immediately close a port so it is guaranteed unreachable
        val deadPort = ServerSocket(0).use { it.localPort }

        try {
            val dao = db.shellDao()
            val repo = buildRepository(db)

            // 2. NIP-65 Kind 10002 parsing -> writeRelays (outbox) vs readRelays (inbox)
            val authorKeys = NostrCrypto.generateKeyPair()
            val recipientKeys = NostrCrypto.generateKeyPair()

            val authorOutboxUrl = "wss://outbox.author.test"
            val authorSharedUrl = "wss://shared.author.test"
            val authorInboxOnlyUrl = "wss://inbox.author.test"
            val recipientInboxUrl = "wss://inbox.recipient.test"

            val authorNip65 = NostrEvent.createAndSign(
                privKeyHex = authorKeys.privKeyHex,
                kind = NostrKinds.RELAY_LIST,
                content = "",
                tags = listOf(
                    listOf("r", authorOutboxUrl, "write"),
                    listOf("r", authorSharedUrl),
                    listOf("r", authorInboxOnlyUrl, "read")
                ),
                createdAt = 1_760_000_000L
            )
            val recipientNip65 = NostrEvent.createAndSign(
                privKeyHex = recipientKeys.privKeyHex,
                kind = NostrKinds.RELAY_LIST,
                content = "",
                tags = listOf(
                    listOf("r", recipientInboxUrl, "read"),
                    listOf("r", "wss://outbox.recipient.test", "write")
                ),
                createdAt = 1_760_000_000L
            )
            repo.ingestNostrEvent(authorNip65)
            repo.ingestNostrEvent(recipientNip65)

            assertEquals(
                listOf(authorOutboxUrl, authorSharedUrl),
                repo.networkEngine.getAuthorWriteRelays(authorKeys.pubKeyHex)
            )
            assertEquals(
                listOf(authorSharedUrl, authorInboxOnlyUrl),
                repo.networkEngine.getAuthorReadRelays(authorKeys.pubKeyHex)
            )
            assertEquals(
                listOf(recipientInboxUrl),
                repo.networkEngine.getAuthorReadRelays(recipientKeys.pubKeyHex)
            )

            // 3. Operation-specific relay targeting
            val nsiteRelay = "wss://relay.nsite.lol"
            val indexerRelay = "wss://purplepag.es"
            val noteRelay = "wss://relay.damus.io"
            val searchRelay = "wss://search.nostr.test"
            val paidRelay = "wss://paid.relay.test"

            listOf(nsiteRelay, indexerRelay, noteRelay, searchRelay, paidRelay).forEach { u ->
                dao.upsertRelay(RelayEntity(url = u, readEnabled = true, writeEnabled = true))
            }
            dao.upsertRelayHealth(
                RelayHealthEntity(
                    url = searchRelay,
                    supportedNipsCsv = "1,11,50",
                    successCount = 5
                )
            )
            dao.upsertRelayHealth(RelayHealthEntity(url = nsiteRelay, successCount = 5))
            dao.upsertRelayHealth(RelayHealthEntity(url = indexerRelay, supportedNipsCsv = "1,11", successCount = 5))
            dao.upsertRelayHealth(RelayHealthEntity(url = noteRelay, successCount = 5))
            dao.upsertRelayHealth(
                RelayHealthEntity(
                    url = paidRelay,
                    paymentRequired = true,
                    successCount = 2
                )
            )

            // DISCOVER_NSITES prefers relay.nsite.lol and NIP-50 search relays
            val discoverSelected = repo.networkEngine.selectRelaysForOperation(
                operation = NetworkOperationType.DISCOVER_NSITES,
                limit = 4
            )
            assertTrue("Expected $nsiteRelay in $discoverSelected", nsiteRelay in discoverSelected)
            assertTrue("Expected $searchRelay in $discoverSelected", searchRelay in discoverSelected)

            // FETCH_METADATA prioritizes purplepag.es & target author outbox write relays
            val metadataSelected = repo.networkEngine.selectRelaysForOperation(
                operation = NetworkOperationType.FETCH_METADATA,
                targetAuthorPubkeys = listOf(authorKeys.pubKeyHex),
                limit = 4
            )
            assertTrue("Expected $authorOutboxUrl in $metadataSelected", authorOutboxUrl in metadataSelected)
            assertTrue("Expected $indexerRelay in $metadataSelected", indexerRelay in metadataSelected)

            // PUBLISH_NOTE targets recipient's NIP-65 read relay & general relays, penalizing purplepag.es & paidRelay
            val publishNoteSelected = repo.networkEngine.selectRelaysForOperation(
                operation = NetworkOperationType.PUBLISH_NOTE,
                targetAuthorPubkeys = listOf(authorKeys.pubKeyHex),
                mentionedPubkeys = listOf(recipientKeys.pubKeyHex),
                limit = 5
            )
            assertTrue("Expected $recipientInboxUrl in $publishNoteSelected", recipientInboxUrl in publishNoteSelected)
            assertTrue("Expected $authorOutboxUrl in $publishNoteSelected", authorOutboxUrl in publishNoteSelected)
            assertFalse("Paid relay should be excluded from $publishNoteSelected", paidRelay in publishNoteSelected)
            assertFalse("Metadata indexer should be deprioritized for notes: $publishNoteSelected", indexerRelay in publishNoteSelected)

            // 4. Dead relay detection & pruning while preserving minKeepRelays = 2
            val nowMs = System.currentTimeMillis()
            val deadRelayA = "wss://dead-alpha.invalid"
            val deadRelayB = "wss://dead-beta.invalid"
            dao.upsertRelay(RelayEntity(url = deadRelayA))
            dao.upsertRelay(RelayEntity(url = deadRelayB))
            dao.upsertRelayHealth(
                RelayHealthEntity(
                    url = deadRelayA,
                    consecutiveFailures = 4,
                    failureCount = 5,
                    lastFailureAt = nowMs - 5_000L
                )
            )
            dao.upsertRelayHealth(
                RelayHealthEntity(
                    url = deadRelayB,
                    consecutiveFailures = 3,
                    failureCount = 4,
                    lastFailureAt = nowMs - 10_000L
                )
            )

            assertTrue(AdaptiveNetworkEngine.isRelayDead(dao.getRelayHealth(deadRelayA), null, nowMs))
            assertFalse(AdaptiveNetworkEngine.isRelayDead(dao.getRelayHealth(searchRelay), null, nowMs))

            // selectRelaysForOperation excludes dead relays when healthy ones exist
            val activeOnly = repo.networkEngine.selectRelaysForOperation(
                operation = NetworkOperationType.DISCOVER_NSITES,
                limit = 12
            )
            assertFalse(deadRelayA in activeOnly)
            assertFalse(deadRelayB in activeOnly)

            // dropDeadRelaysAndConnections prunes both dead relays from Room
            val (droppedUrls, _) = repo.networkEngine.dropDeadRelaysAndConnections(minKeepRelays = 2, nowMs = nowMs)
            assertEquals(setOf(deadRelayA, deadRelayB), droppedUrls.toSet())
            val remainingUrls = dao.getRelaysList().map { it.url }.toSet()
            assertFalse(deadRelayA in remainingUrls)
            assertFalse(deadRelayB in remainingUrls)

            // 5. Auto-discovery against a live local WebSocket relay + unreachable dead port
            // Trim pool below maxConfiguredCap = 10 first
            dao.getRelaysList().drop(1).forEach { dao.deleteRelay(it.url) }
            val liveLocalCandidate = "ws://127.0.0.1:${liveDiscoveredRelay.port}"
            val deadLocalCandidate = "ws://127.0.0.1:$deadPort"
            val adopted = repo.networkEngine.autoDiscoverAndAdoptRelays(
                extraCandidates = listOf(liveLocalCandidate, deadLocalCandidate),
                maxConfiguredCap = 6,
                maxProbesPerPass = 4
            )
            assertTrue(
                "Live local relay must be discovered and adopted: adopted=$adopted",
                liveLocalCandidate in adopted
            )
            assertFalse(
                "Unreachable local port must NOT be adopted",
                deadLocalCandidate in adopted
            )
            assertTrue(dao.getRelaysList().any { it.url == liveLocalCandidate })
        } finally {
            runCatching { liveDiscoveredRelay.close() }
            db.close()
        }
    }

    @Test
    fun nsiteContentTemplates_createParseJsonAndResolvePreviewAssets_acrossAllFiveKinds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ShellDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = buildRepository(db)
            val creator = repo.createNewAccount("Template Tester")

            val expectedDataFiles = mapOf(
                NsiteTemplateKind.BLANK to Pair(null, null),
                NsiteTemplateKind.MUSIC to Pair("/data/tracks.json", "tracks"),
                NsiteTemplateKind.BOOKS to Pair("/data/chapters.json", "chapters"),
                NsiteTemplateKind.BLOGS to Pair("/data/posts.json", "posts"),
                NsiteTemplateKind.VIDEOS to Pair("/data/videos.json", "videos")
            )

            val discoveredTemplates = com.example.data.NsiteTemplates.listAvailableTemplates(context)
            val discoveredDirs = discoveredTemplates.map { it.assetDir }.toSet()
            assertTrue("assets/templates/music missing", "music" in discoveredDirs)
            assertTrue("assets/templates/blogs missing", "blogs" in discoveredDirs)
            assertTrue("assets/templates/video missing", "video" in discoveredDirs)
            assertTrue("assets/templates/books missing", "books" in discoveredDirs)
            assertTrue("assets/templates/blank missing", "blank" in discoveredDirs)

            for (templateKind in NsiteTemplateKind.entries) {
                val rawTitle = "Chaos \"${templateKind.label}\" <A&B>"
                val project = repo.projectRepository.createScratchProject(
                    ownerPubkeyHex = creator.pubkeyHex,
                    title = rawTitle,
                    dTag = "${templateKind.id}-starter",
                    targetKind = NostrKinds.NSITE_NAMED,
                    templateKind = templateKind
                )
                val files = db.shellDao().getProjectFilesList(project.projectId)
                val byPath = files.associateBy { it.relativePath }

                assertTrue("/index.html missing for $templateKind", byPath.containsKey("/index.html"))
                assertTrue("/css/style.css missing for $templateKind", byPath.containsKey("/css/style.css"))
                assertTrue("No JS file generated for $templateKind", files.any { it.relativePath.endsWith(".js") })

                // Preview asset resolution for /index.html
                val indexAsset = repo.projectRepository.resolveProjectPreviewAsset(
                    projectId = project.projectId,
                    rawPath = "/index.html",
                    isMainFrame = true,
                    transformRootHtml = { it }
                )
                assertNotNull(indexAsset)
                assertEquals("text/html", indexAsset!!.mimeType)
                val htmlText = String(indexAsset.bytes, Charsets.UTF_8)
                assertTrue("<!DOCTYPE html> must lead $templateKind", htmlText.startsWith("<!DOCTYPE html>"))
                assertTrue("HTML title must be escaped", htmlText.contains("&lt;A&amp;B&gt;") && htmlText.contains("&quot;"))

                val cssAsset = repo.projectRepository.resolveProjectPreviewAsset(
                    projectId = project.projectId,
                    rawPath = "/css/style.css",
                    isMainFrame = false,
                    transformRootHtml = { it }
                )
                assertEquals("text/css", cssAsset?.mimeType)

                val (jsonPath, arrayKey) = expectedDataFiles[templateKind]!!
                if (jsonPath != null && arrayKey != null) {
                    assertTrue("$jsonPath missing for $templateKind", byPath.containsKey(jsonPath))
                    val jsonAsset = repo.projectRepository.resolveProjectPreviewAsset(
                        projectId = project.projectId,
                        rawPath = jsonPath,
                        isMainFrame = false,
                        transformRootHtml = { it }
                    )
                    assertNotNull(jsonAsset)
                    assertEquals("application/json", jsonAsset!!.mimeType)
                    val parsed = JSONObject(String(jsonAsset.bytes, Charsets.UTF_8))
                    assertTrue("$arrayKey array missing in $jsonPath", parsed.has(arrayKey))
                    assertEquals(
                        "Template starter $jsonPath should start empty for user content",
                        0,
                        parsed.getJSONArray(arrayKey).length()
                    )
                }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun relayBackgroundManager_queriesNip01RelayLists_monitorsLatency_andDropsDeadConnections() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ShellDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val seedRelay = EmbeddedNostrRelayServer()
        val discoveredRelay = EmbeddedNostrRelayServer()
        seedRelay.start()
        discoveredRelay.start()

        val deadPort = ServerSocket(0).use { it.localPort }
        val seedUrl = "ws://127.0.0.1:${seedRelay.port}"
        val discoveredUrl = "ws://127.0.0.1:${discoveredRelay.port}"
        val deadUrl = "ws://127.0.0.1:$deadPort"

        try {
            val repo = buildRepository(db)
            val publisher = NostrCrypto.generateKeyPair()

            // Seed a valid NIP-65 Kind 10002 relay list onto seedRelay pointing to discoveredRelay
            val nip65Event = NostrEvent.createAndSign(
                privKeyHex = publisher.privKeyHex,
                kind = NostrKinds.RELAY_LIST,
                content = "",
                tags = listOf(listOf("r", discoveredUrl))
            )
            val pubSummary = repo.relayPool.publishEvent(
                event = nip65Event,
                targetRelayUrls = listOf(seedUrl),
                verifyRetrieval = true
            )
            assertEquals(1, pubSummary.acceptedCount)

            // Configure DB with live seedRelay + unreachable deadUrl
            db.shellDao().upsertRelay(RelayEntity(url = seedUrl))
            db.shellDao().upsertRelay(RelayEntity(url = deadUrl))
            db.shellDao().upsertRelayHealth(
                com.example.data.RelayHealthEntity(
                    url = deadUrl,
                    failureCount = 2,
                    consecutiveFailures = 2,
                    lastFailureAt = System.currentTimeMillis(),
                    lastErrorReason = "Connection refused"
                )
            )
            repo.relayPool.syncRelays(listOf(seedUrl, deadUrl))
            repo.relayPool.awaitSocketReady(seedUrl, timeoutMs = 1500L)

            val summary = repo.relayBackgroundManager.runOptimizationCycle(
                queryTimeoutMs = 2000L,
                minKeepRelays = 1,
                maxConfiguredCap = 6
            )

            assertTrue(
                "Newly discovered NIP-01 Kind 10002 relay must be adopted: ${summary.discoveredUrls}",
                discoveredUrl in summary.discoveredUrls
            )
            assertTrue(
                "Unresponsive relay must be dropped: ${summary.droppedUrls}",
                deadUrl in summary.droppedUrls
            )

            val configuredUrls = db.shellDao().getRelaysList().map { it.url }
            assertTrue(seedUrl in configuredUrls)
            assertTrue(discoveredUrl in configuredUrls)
            assertFalse(deadUrl in configuredUrls)

            val bgStatus = repo.relayBackgroundStatusFlow.value
            assertTrue(bgStatus.cycleCount >= 1)
            assertTrue(bgStatus.discoveredFromNip01Count >= 1)
            assertTrue(bgStatus.adoptedRelaysCount >= 1)
            assertTrue(bgStatus.droppedUnresponsiveCount >= 1)
            assertTrue("Expected positive monitored latency, got ${bgStatus.averageLatencyMs}", bgStatus.averageLatencyMs > 0L)

            repo.shutdown()
            assertFalse(repo.relayBackgroundStatusFlow.value.isRunning)
        } finally {
            runCatching { seedRelay.close() }
            runCatching { discoveredRelay.close() }
            db.close()
        }
    }

    private fun buildSampleZip(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    /**
     * Minimal RFC 6455 WebSocket Nostr Relay server for deterministic local integration testing.
     * Accepts `["EVENT", {...}]`, stores the event, replies `["OK", "<id>", true, ""]`,
     * and answers `["REQ", "<subId>", {...}]` with matching stored `["EVENT", "<subId>", {...}]` + `["EOSE", "<subId>"]`.
     */
    private class EmbeddedNostrRelayServer {
        private val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        private val storedEvents = CopyOnWriteArrayList<JSONObject>()
        @Volatile private var running = true

        fun start() {
            thread(isDaemon = true, name = "EmbeddedNostrRelay") {
                while (running && !serverSocket.isClosed) {
                    val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { handleClient(client) }
                }
            }
        }

        private fun handleClient(socket: Socket) {
            socket.use { sock ->
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                var wsKey: String? = null
                while (true) {
                    val line = reader.readLine() ?: return
                    if (line.isEmpty()) break
                    if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                        wsKey = line.substringAfter(':').trim()
                    }
                }
                val key = wsKey ?: return
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.UTF_8))
                )
                val handshake = (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
                output.write(handshake)
                output.flush()

                while (running && !sock.isClosed) {
                    val text = readWebSocketTextFrame(input) ?: break
                    val arr = runCatching { JSONArray(text) }.getOrNull() ?: continue
                    when (arr.optString(0)) {
                        "EVENT" -> {
                            val ev = arr.optJSONObject(1) ?: continue
                            val id = ev.optString("id")
                            storedEvents.add(ev)
                            val okMsg = JSONArray()
                                .put("OK")
                                .put(id)
                                .put(true)
                                .put("stored")
                                .toString()
                            writeWebSocketTextFrame(output, okMsg)
                        }
                        "REQ" -> {
                            val subId = arr.optString(1)
                            val filter = arr.optJSONObject(2)
                            val idsFilter = filter?.optJSONArray("ids")?.let { j ->
                                (0 until j.length()).map { j.optString(it) }.toSet()
                            }
                            for (ev in storedEvents) {
                                if (idsFilter == null || ev.optString("id") in idsFilter) {
                                    val evMsg = JSONArray().put("EVENT").put(subId).put(ev).toString()
                                    writeWebSocketTextFrame(output, evMsg)
                                }
                            }
                            val eoseMsg = JSONArray().put("EOSE").put(subId).toString()
                            writeWebSocketTextFrame(output, eoseMsg)
                        }
                        "CLOSE" -> {
                            // No-op
                        }
                    }
                }
            }
        }

        private fun readWebSocketTextFrame(input: InputStream): String? {
            val b0 = input.read()
            if (b0 == -1) return null
            val opcode = b0 and 0x0F
            if (opcode == 8) return null // Close frame
            val b1 = input.read()
            if (b1 == -1) return null
            val masked = (b1 and 0x80) != 0
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                val hi = input.read()
                val lo = input.read()
                length = ((hi shl 8) or lo).toLong()
            } else if (length == 127L) {
                var l = 0L
                repeat(8) {
                    l = (l shl 8) or (input.read().toLong() and 0xFF)
                }
                length = l
            }
            val maskKey = ByteArray(4)
            if (masked) {
                var read = 0
                while (read < 4) {
                    val r = input.read(maskKey, read, 4 - read)
                    if (r == -1) return null
                    read += r
                }
            }
            val payload = ByteArray(length.toInt())
            var total = 0
            while (total < payload.size) {
                val r = input.read(payload, total, payload.size - total)
                if (r == -1) return null
                total += r
            }
            if (masked) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }
            return String(payload, Charsets.UTF_8)
        }

        @Synchronized
        private fun writeWebSocketTextFrame(output: OutputStream, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            output.write(0x81) // FIN + text frame
            when {
                bytes.size <= 125 -> output.write(bytes.size)
                bytes.size <= 65535 -> {
                    output.write(126)
                    output.write((bytes.size shr 8) and 0xFF)
                    output.write(bytes.size and 0xFF)
                }
                else -> {
                    output.write(127)
                    val len = bytes.size.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        output.write(((len shr shift) and 0xFF).toInt())
                    }
                }
            }
            output.write(bytes)
            output.flush()
        }

        fun close() {
            running = false
            runCatching { serverSocket.close() }
        }
    }
}
