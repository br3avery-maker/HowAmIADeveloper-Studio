package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.nostr.AdaptiveNetworkEngine
import com.example.nostr.BlossomClient
import com.example.nostr.DeliveryVerificationStage
import com.example.nostr.ExternalSignerManager
import com.example.nostr.ExternalSiteLauncher
import com.example.nostr.NostrCrypto
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.RelayPublishReceipt
import com.example.nostr.UnsignedNostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.UUID
import java.util.zip.ZipInputStream

data class ForkImportOutcome(
    val project: NsiteProjectEntity,
    val verifiedPaths: List<String>,
    val missingOrCorruptPaths: List<Pair<String, String>>
) {
    val isComplete: Boolean get() = missingOrCorruptPaths.isEmpty() && verifiedPaths.isNotEmpty()
}

data class PublishVerificationBreakdown(
    val totalProjectFiles: Int,
    val verifiedUploadedBlobs: Int,
    val mirroredServerCount: Int,
    val connectedRelays: Int,
    val acceptedRelays: Int,
    val verifiedRetrievalRelays: Int,
    val manifestVerifiedLive: Boolean,
    val entryBlobVerifiedLive: Boolean,
    val relayReceipts: List<RelayPublishReceipt>
)

class NsiteProjectRepository(
    context: Context,
    private val dao: ShellDao,
    private val networkEngine: AdaptiveNetworkEngine,
    private val signerManager: ExternalSignerManager,
    private val prefsRepo: LauncherPreferencesRepository
) {
    private val appContext = context.applicationContext
    private val projectsRootDir = File(appContext.filesDir, "nsite_projects").apply { mkdirs() }

    val allProjectsFlow: Flow<List<NsiteProjectEntity>> = dao.observeAllProjects()

    fun observeProject(projectId: String): Flow<NsiteProjectEntity?> = dao.observeProjectById(projectId)

    fun observeProjectFiles(projectId: String): Flow<List<NsiteProjectFileEntity>> =
        dao.observeProjectFiles(projectId)

    suspend fun ensureInitialProjectExists(ownerPubkeyHex: String): NsiteProjectEntity =
        withContext(Dispatchers.IO) {
            val existing = dao.getAllProjectsList().firstOrNull()
            if (existing != null) return@withContext existing
            createScratchProject(
                ownerPubkeyHex = ownerPubkeyHex,
                title = "My Sovereign Nsite",
                dTag = "my-nsite",
                targetKind = NostrKinds.NSITE_NAMED
            )
        }

    suspend fun createScratchProject(
        ownerPubkeyHex: String,
        title: String,
        dTag: String,
        targetKind: Int = NostrKinds.NSITE_NAMED
    ): NsiteProjectEntity = withContext(Dispatchers.IO) {
        val projectId = "proj_${UUID.randomUUID().toString().take(12)}"
        val cleanSlug = sanitizeDTag(dTag.ifBlank { title })
        val project = NsiteProjectEntity(
            projectId = projectId,
            ownerPubkeyHex = ownerPubkeyHex,
            title = title.trim().ifBlank { "Untitled Nsite" },
            dTag = cleanSlug,
            targetKind = if (targetKind == NostrKinds.NSITE_ROOT) NostrKinds.NSITE_ROOT else NostrKinds.NSITE_NAMED,
            description = "Multi-file NIP-5A static website built in SovereignShell.",
            entryPath = "/index.html",
            sourceType = "SCRATCH"
        )
        dao.upsertProject(project)

        val defaultHtml = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>${project.title}</title>
  <link rel="stylesheet" href="./css/style.css" />
</head>
<body>
  <main class="shell-card">
    <span class="tag">NIP-5A NSITE</span>
    <h1 id="headline">${project.title}</h1>
    <p>Multi-file Nostr website hosted on Blossom with cryptographic SHA-256 path verification.</p>
    <div class="row">
      <button id="counterBtn" type="button">Clicks: 0</button>
      <button id="nostrBtn" type="button" class="secondary">Verify NIP-07 Key</button>
    </div>
    <pre id="output">Ready.</pre>
  </main>
  <script type="module" src="./js/app.js"></script>
</body>
</html>
        """.trimIndent()

        val defaultCss = """
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body {
  margin: 0;
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: #000000;
  color: #ffffff;
  font-family: system-ui, -apple-system, sans-serif;
}
.shell-card {
  width: min(100%, 480px);
  padding: 24px;
  border-radius: 20px;
  background: #111111;
  border: 1px solid #FF1493;
}
.tag {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 999px;
  background: #5B0633;
  color: #FFB6D9;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}
h1 { margin: 12px 0 8px; font-size: 24px; }
p { margin: 0 0 18px; color: #B8B8B8; line-height: 1.5; font-size: 14px; }
.row { display: flex; gap: 10px; flex-wrap: wrap; }
button {
  border: 0;
  border-radius: 12px;
  padding: 12px 16px;
  background: #FF1493;
  color: #ffffff;
  font-weight: 700;
  cursor: pointer;
}
button.secondary {
  background: #1A1A1A;
  border: 1px solid #9A0957;
  color: #FFB6D9;
}
pre {
  margin: 16px 0 0;
  padding: 12px;
  border-radius: 12px;
  background: #080808;
  color: #FFB6D9;
  font-size: 12px;
  overflow-x: auto;
}
        """.trimIndent()

        val defaultJs = """
let count = 0;
const counterBtn = document.getElementById('counterBtn');
const nostrBtn = document.getElementById('nostrBtn');
const output = document.getElementById('output');

counterBtn?.addEventListener('click', () => {
  count += 1;
  counterBtn.textContent = 'Clicks: ' + count;
  output.textContent = 'Interactive state updated (' + count + ')';
});

nostrBtn?.addEventListener('click', async () => {
  if (!window.nostr) {
    output.textContent = 'NIP-07 disabled for this view.';
    return;
  }
  try {
    const pubkey = await window.nostr.getPublicKey();
    output.textContent = 'Active Nostr pubkey: ' + pubkey;
  } catch (err) {
    output.textContent = 'Signer response: ' + err.message;
  }
});
        """.trimIndent()

        writeFileInternal(projectId, "/index.html", defaultHtml.toByteArray(Charsets.UTF_8))
        writeFileInternal(projectId, "/css/style.css", defaultCss.toByteArray(Charsets.UTF_8))
        writeFileInternal(projectId, "/js/app.js", defaultJs.toByteArray(Charsets.UTF_8))
        project
    }

    /**
     * 1. Single HTML file import.
     */
    suspend fun importSingleHtmlFile(
        ownerPubkeyHex: String,
        titleHint: String,
        originalFileName: String,
        htmlBytes: ByteArray
    ): NsiteProjectEntity = withContext(Dispatchers.IO) {
        require(htmlBytes.isNotEmpty()) { "Selected HTML file is empty" }
        val projectId = "proj_${UUID.randomUUID().toString().take(12)}"
        val extractedTitle = extractHtmlTitle(htmlBytes) ?: titleHint.removeSuffix(".html").removeSuffix(".htm")
        val cleanTitle = extractedTitle.trim().ifBlank { "Imported HTML Site" }
        val cleanSlug = sanitizeDTag(cleanTitle)

        val project = NsiteProjectEntity(
            projectId = projectId,
            ownerPubkeyHex = ownerPubkeyHex,
            title = cleanTitle,
            dTag = cleanSlug,
            targetKind = NostrKinds.NSITE_NAMED,
            description = "Imported from $originalFileName",
            entryPath = "/index.html",
            sourceType = "HTML_FILE"
        )
        dao.upsertProject(project)
        writeFileInternal(projectId, "/index.html", htmlBytes)
        val cleanOrigName = originalFileName.trim().substringAfterLast('/').substringAfterLast('\\')
        if (
            cleanOrigName.isNotBlank() &&
            !cleanOrigName.equals("index.html", ignoreCase = true) &&
            (cleanOrigName.endsWith(".html", ignoreCase = true) || cleanOrigName.endsWith(".htm", ignoreCase = true))
        ) {
            writeFileInternal(projectId, "/$cleanOrigName", htmlBytes)
        }
        project
    }

    /**
     * 2. Multi-file ZIP archive import preserving relative paths, nested folders, and binary assets.
     */
    suspend fun importZipArchive(
        ownerPubkeyHex: String,
        titleHint: String,
        zipInputStream: InputStream
    ): NsiteProjectEntity = withContext(Dispatchers.IO) {
        val rawEntries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zipInputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val safeRel = sanitizeZipEntryPath(entry.name)
                    if (safeRel != null && !isIgnoredMetadataFile(safeRel)) {
                        rawEntries[safeRel] = zis.readBytes()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        require(rawEntries.isNotEmpty()) { "ZIP archive contains no importable website files" }

        val unwrapped = unwrapCommonSingleRootFolder(rawEntries)
        val entryPath = detectEntryPoint(unwrapped.keys)
        val indexBytes = unwrapped[entryPath]
        val inferredTitle = indexBytes?.let { extractHtmlTitle(it) }
            ?: titleHint.removeSuffix(".zip")
        val cleanTitle = inferredTitle.trim().ifBlank { "Imported ZIP Nsite" }
        val projectId = "proj_${UUID.randomUUID().toString().take(12)}"

        val project = NsiteProjectEntity(
            projectId = projectId,
            ownerPubkeyHex = ownerPubkeyHex,
            title = cleanTitle,
            dTag = sanitizeDTag(cleanTitle),
            targetKind = NostrKinds.NSITE_NAMED,
            description = "Imported ZIP website (${unwrapped.size} files)",
            entryPath = entryPath,
            sourceType = "ZIP_ARCHIVE"
        )
        dao.upsertProject(project)
        unwrapped.forEach { (path, bytes) ->
            writeFileInternal(projectId, path, bytes)
        }
        project
    }

    suspend fun importZipBytes(
        ownerPubkeyHex: String,
        titleHint: String,
        zipBytes: ByteArray
    ): NsiteProjectEntity = importZipArchive(ownerPubkeyHex, titleHint, ByteArrayInputStream(zipBytes))

    /**
     * 3A. Folder import via Android SAF `OpenDocumentTree` URI (`DocumentsContract`), preserving
     * full nested folder structure, relative paths, and binary/text assets.
     */
    suspend fun importFolderFromTreeUri(
        context: Context,
        ownerPubkeyHex: String,
        treeUri: Uri
    ): NsiteProjectEntity = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryDocumentDisplayName(context, treeUri, rootDocId).ifBlank { "Folder Nsite" }

        val collected = LinkedHashMap<String, ByteArray>()
        fun walkFolder(parentDocId: String, prefixPath: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1)?.trim().orEmpty()
                    val mime = cursor.getString(2).orEmpty()
                    if (name.isBlank() || name.startsWith(".")) continue
                    val childRel = "$prefixPath/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walkFolder(childId, childRel)
                    } else {
                        val safe = normalizeRelativePath(childRel)
                        if (!isIgnoredMetadataFile(safe)) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            val bytes = resolver.openInputStream(docUri)?.use { it.readBytes() }
                            if (bytes != null) {
                                collected[safe] = bytes
                            }
                        }
                    }
                }
            }
        }
        walkFolder(rootDocId, "")
        require(collected.isNotEmpty()) { "Selected folder contains no website files" }

        val entryPath = detectEntryPoint(collected.keys)
        val inferredTitle = collected[entryPath]?.let { extractHtmlTitle(it) } ?: rootName
        val projectId = "proj_${UUID.randomUUID().toString().take(12)}"
        val project = NsiteProjectEntity(
            projectId = projectId,
            ownerPubkeyHex = ownerPubkeyHex,
            title = inferredTitle.trim().ifBlank { "Folder Nsite" },
            dTag = sanitizeDTag(inferredTitle),
            targetKind = NostrKinds.NSITE_NAMED,
            description = "Imported folder ($rootName • ${collected.size} files)",
            entryPath = entryPath,
            sourceType = "FOLDER"
        )
        dao.upsertProject(project)
        collected.forEach { (path, bytes) ->
            writeFileInternal(projectId, path, bytes)
        }
        project
    }

    /**
     * 3B. Direct filesystem directory import (used by local file imports & integration tests).
     */
    suspend fun importFolderFromDirectory(
        ownerPubkeyHex: String,
        directory: File,
        titleHint: String = directory.name
    ): NsiteProjectEntity = withContext(Dispatchers.IO) {
        require(directory.exists() && directory.isDirectory) { "Directory does not exist: ${directory.path}" }
        val collected = LinkedHashMap<String, ByteArray>()
        directory.walkTopDown().forEach { file ->
            if (file.isFile) {
                val rel = "/" + file.relativeTo(directory).invariantSeparatorsPath.trimStart('/')
                val norm = normalizeRelativePath(rel)
                if (!isIgnoredMetadataFile(norm)) {
                    collected[norm] = file.readBytes()
                }
            }
        }
        require(collected.isNotEmpty()) { "Folder '${directory.name}' contains no files" }

        val entryPath = detectEntryPoint(collected.keys)
        val cleanTitle = (collected[entryPath]?.let { extractHtmlTitle(it) } ?: titleHint)
            .trim().ifBlank { "Imported Folder Nsite" }
        val projectId = "proj_${UUID.randomUUID().toString().take(12)}"
        val project = NsiteProjectEntity(
            projectId = projectId,
            ownerPubkeyHex = ownerPubkeyHex,
            title = cleanTitle,
            dTag = sanitizeDTag(cleanTitle),
            targetKind = NostrKinds.NSITE_NAMED,
            description = "Imported folder (${collected.size} files)",
            entryPath = entryPath,
            sourceType = "FOLDER"
        )
        dao.upsertProject(project)
        collected.forEach { (path, bytes) ->
            writeFileInternal(projectId, path, bytes)
        }
        project
    }

    /**
     * 4. First-Class Fork of an Existing Published NIP-5A Nsite.
     *
     * - Resolves original signed manifest paths and Blossom servers.
     * - Downloads and SHA-256 verifies every referenced file from Blossom.
     * - Reconstructs full directory hierarchy into an editable local project.
     * - Records original publisher, coordinate, event ID, and timestamp as provenance.
     * - Explicitly reports missing or hash-mismatched blobs without inventing fake files.
     */
    suspend fun forkPublishedNsite(
        ownerPubkeyHex: String,
        sourceSite: InstalledAppletEntity,
        specificVersion: NsiteVersionEntity? = null,
        extraAuthorServers: List<String> = emptyList(),
        fallbackLegacyPathMap: Map<String, String> = emptyMap()
    ): ForkImportOutcome = withContext(Dispatchers.IO) {
        val manifestJsonStr = specificVersion?.manifestPathsJson?.ifBlank { sourceSite.manifestPathsJson }
            ?: sourceSite.manifestPathsJson
        val manifestObj = runCatching {
            if (manifestJsonStr.isNotBlank()) JSONObject(manifestJsonStr) else JSONObject()
        }.getOrDefault(JSONObject())

        val pathToSha = LinkedHashMap<String, String>()
        val keys = manifestObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!k.startsWith("__")) {
                val sha = manifestObj.optString(k).trim().lowercase()
                if (sha.length == 64) {
                    pathToSha[normalizeRelativePath(k)] = sha
                }
            }
        }
        if (pathToSha.isEmpty()) {
            fallbackLegacyPathMap.forEach { (k, v) ->
                if (v.length == 64) pathToSha[normalizeRelativePath(k)] = v.lowercase()
            }
        }
        if (pathToSha.isEmpty() && sourceSite.indexSha256.length == 64) {
            pathToSha["/index.html"] = sourceSite.indexSha256.lowercase()
        }

        val manifestServers = buildList {
            if (sourceSite.blossomServer.isNotBlank()) add(sourceSite.blossomServer)
            specificVersion?.blossomServersCsv?.split(',')?.filter { it.isNotBlank() }?.let { addAll(it) }
            val srvArr = manifestObj.optJSONArray("__servers")
            if (srvArr != null) {
                for (i in 0 until srvArr.length()) {
                    val s = srvArr.optString(i).trim()
                    if (s.isNotBlank()) add(s)
                }
            }
            addAll(extraAuthorServers)
        }.distinct()

        val projectId = "proj_${UUID.randomUUID().toString().take(12)}"
        val verifiedPaths = mutableListOf<String>()
        val failedPaths = mutableListOf<Pair<String, String>>()

        if (pathToSha.isNotEmpty()) {
            for ((normPath, expectedSha) in pathToSha) {
                val ext = normPath.substringAfterLast('/', "").substringAfterLast('.', "")
                val outcome = networkEngine.fetchBlobAdaptively(
                    primaryServer = sourceSite.blossomServer,
                    manifestAndAuthorServers = manifestServers,
                    sha256 = expectedSha,
                    extensionHint = ext
                )
                when (outcome) {
                    is BlossomClient.BlobFetchOutcome.Verified -> {
                        writeFileInternal(projectId, normPath, outcome.bytes)
                        verifiedPaths.add(normPath)
                    }
                    is BlossomClient.BlobFetchOutcome.HashMismatch -> {
                        failedPaths.add(
                            normPath to "SHA-256 mismatch from ${outcome.offendingServer} (expected ${expectedSha.take(10)}…, got ${outcome.actualSha256.take(10)}…)"
                        )
                    }
                    is BlossomClient.BlobFetchOutcome.Missing -> {
                        failedPaths.add(normPath to "Missing blob (${expectedSha.take(12)}…) across Blossom servers")
                    }
                }
            }
        } else if (sourceSite.inlineHtml.isNotBlank()) {
            writeFileInternal(projectId, "/index.html", sourceSite.inlineHtml.toByteArray(Charsets.UTF_8))
            verifiedPaths.add("/index.html")
            if (sourceSite.inlineCss.isNotBlank()) {
                writeFileInternal(projectId, "/style.css", sourceSite.inlineCss.toByteArray(Charsets.UTF_8))
                verifiedPaths.add("/style.css")
            }
            if (sourceSite.inlineJs.isNotBlank()) {
                writeFileInternal(projectId, "/app.js", sourceSite.inlineJs.toByteArray(Charsets.UTF_8))
                verifiedPaths.add("/app.js")
            }
        } else {
            failedPaths.add("/index.html" to "Manifest contained no verifiable Blossom file hashes")
        }

        val entryPath = if (verifiedPaths.isNotEmpty()) {
            detectEntryPoint(verifiedPaths)
        } else {
            "/index.html"
        }

        val warningText = if (failedPaths.isEmpty()) {
            ""
        } else {
            buildString {
                append("Incomplete import: ${failedPaths.size} file(s) could not be verified — ")
                append(failedPaths.joinToString("; ") { "${it.first}: ${it.second}" })
            }
        }

        val baseSlug = if (sourceSite.dTag.isBlank() || sourceSite.dTag == "root") {
            sanitizeDTag(sourceSite.title)
        } else {
            sourceSite.dTag
        }
        val forkSlug = sanitizeDTag("$baseSlug-fork")
        val coordinate = "${sourceSite.kind}:${sourceSite.authorPubkey}:${sourceSite.dTag.ifBlank { "root" }}"

        val project = NsiteProjectEntity(
            projectId = projectId,
            ownerPubkeyHex = ownerPubkeyHex,
            title = "${sourceSite.title} (Fork)".take(70),
            dTag = forkSlug,
            targetKind = NostrKinds.NSITE_NAMED,
            description = sourceSite.description,
            entryPath = entryPath,
            sourceType = "FORK",
            forkSourceCoordinate = coordinate,
            forkSourceEventId = specificVersion?.eventId ?: sourceSite.latestEventId,
            forkSourcePubkey = sourceSite.authorPubkey,
            forkSourceNpub = sourceSite.authorNpub,
            forkSourceDTag = sourceSite.dTag.ifBlank { "root" },
            forkSourceVersionSec = specificVersion?.createdAtSec ?: (sourceSite.updatedAt / 1000L),
            incompleteImportWarning = warningText
        )
        dao.upsertProject(project)
        ForkImportOutcome(
            project = project,
            verifiedPaths = verifiedPaths,
            missingOrCorruptPaths = failedPaths
        )
    }

    // --- Multi-file Project Editing & Asset Management ---

    suspend fun updateProjectMetadata(
        projectId: String,
        title: String,
        dTag: String,
        targetKind: Int,
        description: String,
        entryPath: String
    ) = withContext(Dispatchers.IO) {
        val existing = dao.getProjectById(projectId) ?: return@withContext
        val cleanKind = if (targetKind == NostrKinds.NSITE_ROOT) NostrKinds.NSITE_ROOT else NostrKinds.NSITE_NAMED
        dao.upsertProject(
            existing.copy(
                title = title.trim().ifBlank { existing.title },
                dTag = if (cleanKind == NostrKinds.NSITE_ROOT) "root" else sanitizeDTag(dTag),
                targetKind = cleanKind,
                description = description,
                entryPath = normalizeRelativePath(entryPath.ifBlank { "/index.html" }),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveTextFile(
        projectId: String,
        rawRelativePath: String,
        content: String
    ): NsiteProjectFileEntity = withContext(Dispatchers.IO) {
        val normPath = normalizeRelativePath(rawRelativePath)
        val fileEntity = writeFileInternal(projectId, normPath, content.toByteArray(Charsets.UTF_8), forceText = true)
        touchProjectTimestamp(projectId)
        fileEntity
    }

    suspend fun importOrReplaceAsset(
        projectId: String,
        rawRelativePath: String,
        bytes: ByteArray
    ): NsiteProjectFileEntity = withContext(Dispatchers.IO) {
        val normPath = normalizeRelativePath(rawRelativePath)
        val fileEntity = writeFileInternal(projectId, normPath, bytes)
        touchProjectTimestamp(projectId)
        fileEntity
    }

    suspend fun renameProjectFile(
        projectId: String,
        oldPath: String,
        newPathRaw: String
    ): NsiteProjectFileEntity? = withContext(Dispatchers.IO) {
        val normOld = normalizeRelativePath(oldPath)
        val normNew = normalizeRelativePath(newPathRaw)
        if (normOld == normNew) return@withContext dao.getProjectFile(projectId, normOld)
        val existing = dao.getProjectFile(projectId, normOld) ?: return@withContext null
        val bytes = readProjectFileBytes(existing) ?: return@withContext null
        dao.deleteProjectFile(projectId, normOld)
        val created = writeFileInternal(projectId, normNew, bytes)
        val proj = dao.getProjectById(projectId)
        if (proj != null && proj.entryPath == normOld) {
            dao.upsertProject(proj.copy(entryPath = normNew, updatedAt = System.currentTimeMillis()))
        } else {
            touchProjectTimestamp(projectId)
        }
        created
    }

    suspend fun deleteProjectFile(projectId: String, relativePath: String) = withContext(Dispatchers.IO) {
        val norm = normalizeRelativePath(relativePath)
        val existing = dao.getProjectFile(projectId, norm)
        if (existing?.diskBlobPath?.isNotBlank() == true) {
            runCatching { File(existing.diskBlobPath).delete() }
        }
        dao.deleteProjectFile(projectId, norm)
        touchProjectTimestamp(projectId)
    }

    suspend fun deleteEntireProject(projectId: String) = withContext(Dispatchers.IO) {
        File(projectsRootDir, projectId).deleteRecursively()
        dao.deleteAllProjectFiles(projectId)
        dao.deleteProject(projectId)
    }

    suspend fun readProjectFileBytes(file: NsiteProjectFileEntity): ByteArray? = withContext(Dispatchers.IO) {
        if (!file.isBinary) {
            return@withContext file.textContent.toByteArray(Charsets.UTF_8)
        }
        if (file.diskBlobPath.isNotBlank()) {
            val f = File(file.diskBlobPath)
            if (f.exists()) return@withContext f.readBytes()
        }
        null
    }

    /**
     * Serves any file in a local project workspace to the real WebView engine for live preview,
     * preserving relative paths, ES modules, stylesheets, images, and SPA routes.
     */
    suspend fun resolveProjectPreviewAsset(
        projectId: String,
        rawPath: String,
        isMainFrame: Boolean,
        transformRootHtml: (String) -> String = { it }
    ): ResolvedWebAsset? = withContext(Dispatchers.IO) {
        val project = dao.getProjectById(projectId) ?: return@withContext null
        val files = dao.getProjectFilesList(projectId)
        if (files.isEmpty()) return@withContext null

        val stripped = rawPath.substringBefore('?').substringBefore('#').trim()
        val decoded = runCatching { URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
        val cleanPath = if (decoded.isBlank() || decoded == "/") project.entryPath else normalizeRelativePath(decoded)

        val candidates = listOf(
            cleanPath,
            "${cleanPath.trimEnd('/')}/index.html",
            "$cleanPath.html"
        )
        val matched = files.firstOrNull { it.relativePath in candidates }
            ?: if (isMainFrame && !cleanPath.substringAfterLast('/').contains('.')) {
                files.firstOrNull { it.relativePath == project.entryPath }
            } else null

        val target = matched ?: return@withContext null
        val rawBytes = readProjectFileBytes(target) ?: return@withContext null
        val (mime, enc) = ShellRepository.guessMimeType(target.relativePath, rawBytes)
        val finalBytes = if (mime == "text/html" && isMainFrame) {
            transformRootHtml(String(rawBytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        } else {
            rawBytes
        }
        ResolvedWebAsset(
            bytes = finalBytes,
            mimeType = mime,
            encoding = enc,
            sha256 = target.sha256
        )
    }

    // --- Verified NIP-5A Publishing & Updating ---

    /**
     * Publishes a complete multi-file local project as a signed NIP-5A Nsite (`Kind 35128` or `Kind 15128`):
     * 1. Verifies active identity can sign (never impersonating original fork publisher).
     * 2. Validates entry point and inspects all project files & relative paths.
     * 3. Uploads every file to Blossom, requiring >= 1 verified server and mirroring to backups.
     * 4. Builds & signs canonical NIP-5A manifest (preserving fork provenance when applicable).
     * 5. Publishes across healthy write relays and verifies retrieval of both manifest and entry blob.
     */
    suspend fun publishProjectAsNip5a(
        projectId: String,
        activeAccount: VaultAccountEntity
    ): Pair<PublishDeploymentReport, PublishVerificationBreakdown> = withContext(Dispatchers.IO) {
        require(activeAccount.canSign) {
            "Active identity (${activeAccount.displayName}) is read-only. Switch to a signing identity to publish."
        }
        val project = dao.getProjectById(projectId) ?: error("Project not found")
        val files = dao.getProjectFilesList(projectId)
        require(files.isNotEmpty()) { "Project contains no files to publish" }

        val entryFile = files.firstOrNull { it.relativePath == project.entryPath }
            ?: files.firstOrNull { it.relativePath == "/index.html" }
            ?: files.firstOrNull { it.relativePath.endsWith(".html", ignoreCase = true) }
            ?: error("Project is missing an HTML entry point (e.g. /index.html)")

        val prefs = prefsRepo.prefsFlow.first()
        val primaryBlossom = prefs.primaryBlossomServer
        val targetKind = if (project.targetKind == NostrKinds.NSITE_ROOT) {
            NostrKinds.NSITE_ROOT
        } else {
            NostrKinds.NSITE_NAMED
        }
        val cleanSlug = if (targetKind == NostrKinds.NSITE_ROOT) {
            "root"
        } else {
            val candidate = sanitizeDTag(project.dTag.ifBlank { project.title })
            require(ExternalSiteLauncher.isValidNamedNsiteDTag(candidate)) {
                "Named Nsite d-tag must be 1–13 lowercase letters, digits, or hyphens (not ending in '-')"
            }
            candidate
        }

        val uploadResultsByPath = LinkedHashMap<String, BlossomClient.BlobUploadResult>()
        val verifiedServersSet = LinkedHashSet<String>()
        var mirroredCount = 0

        for (file in files) {
            val bytes = readProjectFileBytes(file)
                ?: error("Unable to read bytes for project file ${file.relativePath}")
            val ext = file.relativePath.substringAfterLast('/', "").substringAfterLast('.', "")
            val (mime, enc) = ShellRepository.guessMimeType(file.relativePath, bytes)
            val contentType = if (enc != null) "$mime; charset=$enc" else mime

            val uploadRes = networkEngine.uploadBlobWithRedundancy(
                primaryPreferred = primaryBlossom,
                bytes = bytes,
                mimeType = contentType,
                extension = ext,
                pubkeyHex = activeAccount.pubkeyHex,
                signAuthEvent = { unsignedAuth ->
                    signerManager.signEventForAccount(activeAccount, unsignedAuth)
                }
            )
            if (!uploadRes.uploadedLive) {
                error("Blossom upload failed for ${file.relativePath}: ${uploadRes.statusDetail}")
            }
            // Cache verified bytes locally as well so immediate launch works zero-latency
            networkEngine.blossomClient.putVerifiedBlobInCache(bytes)
            uploadResultsByPath[file.relativePath] = uploadRes
            verifiedServersSet.add(uploadRes.serverUrl)
            uploadRes.mirroredServers.forEach { verifiedServersSet.add(it) }
            mirroredCount += uploadRes.mirroredServers.size
        }

        // Ensure canonical /index.html route is mapped if entry file had a custom name
        val entryUpload = uploadResultsByPath[entryFile.relativePath]!!
        val tags = buildList {
            if (targetKind == NostrKinds.NSITE_NAMED) {
                add(listOf("d", cleanSlug))
            }
            add(listOf("title", project.title.trim()))
            add(listOf("description", project.description.trim()))
            if (entryFile.relativePath != "/index.html") {
                add(listOf("path", "/index.html", entryUpload.sha256))
            }
            uploadResultsByPath.forEach { (relPath, res) ->
                add(listOf("path", relPath, res.sha256))
            }
            if (!uploadResultsByPath.containsKey("/404.html")) {
                add(listOf("path", "/404.html", entryUpload.sha256))
            }
            verifiedServersSet.forEach { srv ->
                add(listOf("server", srv))
            }
            // Preserve fork provenance without ever impersonating the original publisher
            if (project.isFork && !project.forkSourcePubkey.equals(activeAccount.pubkeyHex, ignoreCase = true)) {
                if (project.forkSourceCoordinate.isNotBlank()) {
                    add(listOf("fork", project.forkSourceCoordinate))
                    add(listOf("a", project.forkSourceCoordinate))
                }
                if (project.forkSourceEventId.isNotBlank()) {
                    add(listOf("e", project.forkSourceEventId, "", "fork"))
                }
                if (project.forkSourcePubkey.length == 64) {
                    add(listOf("p", project.forkSourcePubkey.lowercase()))
                }
            }
            add(listOf("version", project.versionCounter.toString()))
            add(listOf("client", "SovereignShell"))
        }

        val unsignedManifest = UnsignedNostrEvent(
            pubkey = activeAccount.pubkeyHex.lowercase(),
            kind = targetKind,
            content = project.description.trim(),
            tags = tags
        )
        val signedManifest = signerManager.signEventForAccount(activeAccount, unsignedManifest)
        require(signedManifest.pubkey.equals(activeAccount.pubkeyHex, ignoreCase = true)) {
            "Signer returned mismatched author pubkey"
        }

        val publishSummary = networkEngine.publishEventAdaptively(
            event = signedManifest,
            verifyRetrieval = true,
            signAuthIfChallenged = { authChallengeEvent ->
                signerManager.signEventForAccount(activeAccount, authChallengeEvent)
            }
        )
        require(publishSummary.acceptedCount > 0) {
            val reasons = publishSummary.receipts.joinToString("; ") { "${it.relayUrl}: ${it.message}" }
            "No relays accepted the NIP-5A manifest ($reasons)"
        }

        // Verify entry blob is retrievable from primary verified Blossom server
        val entryVerifiedLive = networkEngine.blossomClient.verifyRemoteBlobBytes(
            serverUrl = entryUpload.serverUrl,
            expectedSha256 = entryUpload.sha256,
            extensionHint = "html",
            directUrl = entryUpload.url
        )

        val pathsJsonObj = JSONObject().apply {
            if (entryFile.relativePath != "/index.html") {
                put("/index.html", entryUpload.sha256)
            }
            uploadResultsByPath.forEach { (relPath, res) ->
                put(relPath, res.sha256)
            }
            if (verifiedServersSet.size > 1) {
                val arr = JSONArray()
                verifiedServersSet.forEach { arr.put(it) }
                put("__servers", arr)
            }
        }

        val coordinate = "$targetKind:${activeAccount.pubkeyHex.lowercase()}:$cleanSlug"
        val gatewayUrl = ExternalSiteLauncher.buildNip5aGatewayUrl(
            kind = targetKind,
            authorPubkeyHex = activeAccount.pubkeyHex,
            authorNpub = activeAccount.npub,
            dTag = cleanSlug
        ) ?: entryUpload.url

        // Persist into Launcher Library & Version History
        dao.upsertApplet(
            InstalledAppletEntity(
                appletId = coordinate,
                kind = targetKind,
                authorPubkey = activeAccount.pubkeyHex.lowercase(),
                authorNpub = activeAccount.npub,
                dTag = cleanSlug,
                title = project.title.trim(),
                description = project.description.trim(),
                iconGlyph = "🌐",
                accentHex = "#FF1493",
                isNsite = true,
                enableNip07 = true,
                blossomServer = entryUpload.serverUrl,
                indexSha256 = entryUpload.sha256,
                manifestPathsJson = pathsJsonObj.toString(),
                isInstalled = true,
                pinOrder = 0,
                updatedAt = signedManifest.createdAt * 1000L,
                latestEventId = signedManifest.id,
                cachedComplete = true
            )
        )
        dao.upsertNsiteVersion(
            NsiteVersionEntity(
                eventId = signedManifest.id,
                coordinate = coordinate,
                kind = targetKind,
                authorPubkey = activeAccount.pubkeyHex.lowercase(),
                authorNpub = activeAccount.npub,
                dTag = cleanSlug,
                title = project.title.trim(),
                description = project.description.trim(),
                indexSha256 = entryUpload.sha256,
                fileCount = uploadResultsByPath.size,
                blossomServersCsv = verifiedServersSet.joinToString(","),
                manifestPathsJson = pathsJsonObj.toString(),
                rawEventJson = signedManifest.toJsonString(),
                signatureVerified = true,
                createdAtSec = signedManifest.createdAt
            )
        )
        dao.upsertProject(
            project.copy(
                dTag = cleanSlug,
                targetKind = targetKind,
                versionCounter = project.versionCounter + 1,
                lastPublishedEventId = signedManifest.id,
                lastPublishedUrl = gatewayUrl,
                lastPublishedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        dao.insertAuditEntry(
            SignerAuditEntity(
                pubkeyHex = activeAccount.pubkeyHex,
                appletId = coordinate,
                appletTitle = project.title,
                protocolLabel = activeAccount.resolvedSignerType.label,
                method = "publishNip5a",
                eventKind = targetKind,
                approved = true,
                summary = "Published NIP-5A site '${project.title}' (${uploadResultsByPath.size} files)"
            )
        )

        val report = PublishDeploymentReport(
            title = project.title.trim(),
            kind = targetKind,
            manifestEventId = signedManifest.id,
            npub = activeAccount.npub,
            dTag = cleanSlug,
            uploadedFiles = uploadResultsByPath.values.toList(),
            acceptedRelays = publishSummary.acceptedCount,
            totalRelays = publishSummary.receipts.size,
            gatewayPreviewUrl = gatewayUrl
        )
        val breakdown = PublishVerificationBreakdown(
            totalProjectFiles = files.size,
            verifiedUploadedBlobs = uploadResultsByPath.values.count { it.verifiedRetrieval || it.uploadedLive },
            mirroredServerCount = verifiedServersSet.size,
            connectedRelays = publishSummary.receipts.count { it.stage != DeliveryVerificationStage.DISCONNECTED },
            acceptedRelays = publishSummary.acceptedCount,
            verifiedRetrievalRelays = publishSummary.verifiedRetrievalCount,
            manifestVerifiedLive = publishSummary.verifiedRetrievalCount > 0,
            entryBlobVerifiedLive = entryVerifiedLive,
            relayReceipts = publishSummary.receipts
        )
        report to breakdown
    }

    private suspend fun writeFileInternal(
        projectId: String,
        rawPath: String,
        bytes: ByteArray,
        forceText: Boolean = false
    ): NsiteProjectFileEntity {
        val normPath = normalizeRelativePath(rawPath)
        val sha = NostrCrypto.sha256Hex(bytes).lowercase()
        val (mime, _) = ShellRepository.guessMimeType(normPath, bytes)
        val isText = forceText || isEditableTextMime(normPath, mime, bytes)
        val blobPath = if (!isText) {
            val projDir = File(projectsRootDir, "$projectId/blobs").apply { mkdirs() }
            val out = File(projDir, sha)
            if (!out.exists()) out.writeBytes(bytes)
            out.absolutePath
        } else {
            ""
        }
        val entity = NsiteProjectFileEntity(
            projectId = projectId,
            relativePath = normPath,
            mimeType = mime,
            isBinary = !isText,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha,
            textContent = if (isText) String(bytes, Charsets.UTF_8) else "",
            diskBlobPath = blobPath,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertProjectFile(entity)
        return entity
    }

    private suspend fun touchProjectTimestamp(projectId: String) {
        val existing = dao.getProjectById(projectId) ?: return
        dao.upsertProject(existing.copy(updatedAt = System.currentTimeMillis()))
    }

    private fun queryDocumentDisplayName(context: Context, treeUri: Uri, docId: String): String {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return runCatching {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")
    }

    companion object {
        private val TEXT_EXTENSIONS = setOf(
            "html", "htm", "css", "js", "mjs", "cjs", "json", "webmanifest",
            "svg", "xml", "txt", "md", "csv", "toml", "yaml", "yml", "map"
        )

        fun normalizeRelativePath(raw: String): String {
            val cleaned = raw.trim().replace('\\', '/')
                .split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/")
            return "/" + cleaned.ifBlank { "index.html" }
        }

        fun sanitizeZipEntryPath(entryName: String): String? {
            val slashNorm = entryName.replace('\\', '/').trim()
            if (slashNorm.isBlank()) return null
            val parts = slashNorm.split('/')
            if (parts.any { it == ".." }) return null // Prevent Zip-Slip traversal
            val filtered = parts.filter { it.isNotBlank() && it != "." }
            if (filtered.isEmpty()) return null
            return "/" + filtered.joinToString("/")
        }

        fun isIgnoredMetadataFile(path: String): Boolean {
            val lower = path.lowercase()
            val base = lower.substringAfterLast('/')
            return lower.contains("/__macosx/") ||
                base == ".ds_store" ||
                base == "thumbs.db" ||
                lower.startsWith("/.git/")
        }

        fun unwrapCommonSingleRootFolder(entries: Map<String, ByteArray>): Map<String, ByteArray> {
            if (entries.isEmpty()) return entries
            // If /index.html already exists at root, keep as-is
            if (entries.keys.any { it.equals("/index.html", ignoreCase = true) }) return entries
            val segmentsList = entries.keys.map { it.trimStart('/').split('/') }
            if (segmentsList.all { it.size >= 2 }) {
                val firstSeg = segmentsList.first()[0]
                if (segmentsList.all { it[0] == firstSeg }) {
                    return entries.entries.associate { (k, v) ->
                        ("/" + k.trimStart('/').removePrefix("$firstSeg/")) to v
                    }
                }
            }
            return entries
        }

        fun detectEntryPoint(paths: Collection<String>): String {
            if (paths.any { it.equals("/index.html", ignoreCase = true) }) {
                return paths.first { it.equals("/index.html", ignoreCase = true) }
            }
            if (paths.any { it.equals("/index.htm", ignoreCase = true) }) {
                return paths.first { it.equals("/index.htm", ignoreCase = true) }
            }
            return paths
                .filter { it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true) }
                .minByOrNull { it.count { ch -> ch == '/' } }
                ?: paths.firstOrNull()
                ?: "/index.html"
        }

        fun sanitizeDTag(raw: String): String {
            return raw.trim().lowercase()
                .replace(Regex("[^a-z0-9-]"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(13)
                .trimEnd('-')
                .ifBlank { "site" }
        }

        fun isEditableTextMime(path: String, mimeType: String, bytes: ByteArray): Boolean {
            val ext = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
            if (ext in TEXT_EXTENSIONS) return true
            if (mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "image/svg+xml") {
                return true
            }
            if (bytes.size > 512_000) return false
            // Heuristic null-byte check for small extensionless text files
            val checkLen = minOf(bytes.size, 1024)
            for (i in 0 until checkLen) {
                if (bytes[i].toInt() == 0) return false
            }
            return ext.isBlank()
        }

        private fun extractHtmlTitle(bytes: ByteArray): String? {
            val head = String(bytes, 0, minOf(bytes.size, 8192), Charsets.UTF_8)
            val m = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(head)
            return m?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        }
    }
}
