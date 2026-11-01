package com.example.data

import android.content.Context
import android.net.Uri
import com.example.nostr.BlossomClient
import com.example.nostr.KeyVaultManager
import com.example.nostr.NostrCrypto
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.RelayPoolClient
import com.example.nostr.RelayPublishSummary
import com.example.nostr.RelayStatusInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class ManagedRelayItem(
    val url: String,
    val readEnabled: Boolean,
    val writeEnabled: Boolean,
    val statusInfo: RelayStatusInfo
)

data class PublishDeploymentReport(
    val title: String,
    val kind: Int,
    val manifestEventId: String,
    val npub: String,
    val dTag: String,
    val uploadedFiles: List<BlossomClient.BlobUploadResult>,
    val acceptedRelays: Int,
    val totalRelays: Int,
    val gatewayPreviewUrl: String
)

class ShellRepository(
    context: Context,
    private val dao: ShellDao = ShellDatabase.getInstance(context).shellDao(),
    private val keyVault: KeyVaultManager = KeyVaultManager(context),
    val relayPool: RelayPoolClient = RelayPoolClient(),
    private val blossomClient: BlossomClient = BlossomClient(),
    val prefsRepo: LauncherPreferencesRepository = LauncherPreferencesRepository(context)
) {
    private val appContext = context.applicationContext
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val accountsFlow: Flow<List<VaultAccountEntity>> = dao.observeAccounts()
    val activeAccountFlow: Flow<VaultAccountEntity?> = dao.observeActiveAccount()
    val installedAppletsFlow: Flow<List<InstalledAppletEntity>> = dao.observeInstalledApplets()
    val catalogAppletsFlow: Flow<List<InstalledAppletEntity>> = dao.observeAllCatalogApplets()
    val notesFlow: Flow<List<NoteEntity>> = dao.observeNotes()
    val auditLogFlow: Flow<List<SignerAuditEntity>> = dao.observeAuditLog()
    val signerPoliciesFlow: Flow<List<SignerPolicyEntity>> = dao.observeSignerPolicies()

    val managedRelaysFlow: Flow<List<ManagedRelayItem>> = combine(
        dao.observeRelays(),
        relayPool.relayStatuses
    ) { entities, statusMap ->
        entities.map { entity ->
            val info = statusMap[entity.url] ?: RelayStatusInfo(url = entity.url)
            ManagedRelayItem(
                url = entity.url,
                readEnabled = entity.readEnabled,
                writeEnabled = entity.writeEnabled,
                statusInfo = info
            )
        }
    }

    init {
        repoScope.launch {
            bootstrapIfNeeded()
            observeRelayEvents()
        }
    }

    suspend fun bootstrapIfNeeded() = withContext(Dispatchers.IO) {
        // 1. Ensure at least one sovereign Nostr account exists in Keystore Vault
        var active = dao.getActiveAccount()
        if (active == null && dao.countAccounts() == 0) {
            val generated = NostrCrypto.generateKeyPair()
            val encrypted = keyVault.encryptPrivateKeyHex(generated.privKeyHex)
            val initial = VaultAccountEntity(
                pubkeyHex = generated.pubKeyHex,
                npub = generated.npub,
                encryptedPrivKeyBase64 = encrypted.ciphertextBase64,
                ivBase64 = encrypted.ivBase64,
                displayName = "Sovereign Builder",
                bio = "Exploring NIP-5D Napplets & decentralized NIP-5A Nsites.",
                isActive = true
            )
            dao.upsertAccount(initial)
            active = initial
        }

        // 2. Seed default relays if empty
        val currentRelays = dao.getRelaysList()
        val relayUrls = if (currentRelays.isEmpty()) {
            RelayPoolClient.DEFAULT_RELAYS.forEach { url ->
                dao.upsertRelay(RelayEntity(url = url))
            }
            RelayPoolClient.DEFAULT_RELAYS
        } else {
            currentRelays.map { it.url }
        }
        relayPool.syncRelays(relayUrls)

        // 3. Seed starter Napplets & Nsites catalog if empty
        if (dao.countApplets() == 0 && active != null) {
            val starters = StarterCatalog.defaultCatalog(active.pubkeyHex, active.npub)
            dao.upsertApplets(starters)
        }

        active?.let { relayPool.requestAuthorMetadata(it.pubkeyHex) }
    }

    private suspend fun observeRelayEvents() {
        relayPool.incomingEvents.collect { (_, event) ->
            when (event.kind) {
                NostrKinds.METADATA -> handleIncomingMetadata(event)
                NostrKinds.TEXT_NOTE -> handleIncomingTextNote(event)
                NostrKinds.NAPPLET_NAMED, NostrKinds.NAPPLET_ROOT,
                NostrKinds.NSITE_NAMED, NostrKinds.NSITE_ROOT -> handleIncomingManifest(event)
            }
        }
    }

    private suspend fun handleIncomingMetadata(event: NostrEvent) {
        val localAcc = dao.getAccountByPubkey(event.pubkey) ?: return
        runCatching {
            val obj = JSONObject(event.content)
            val name = obj.optString("display_name").ifBlank { obj.optString("name") }
            val about = obj.optString("about")
            val picture = obj.optString("picture")
            if (name.isNotBlank() || about.isNotBlank() || picture.isNotBlank()) {
                dao.upsertAccount(
                    localAcc.copy(
                        displayName = name.ifBlank { localAcc.displayName },
                        bio = about.ifBlank { localAcc.bio },
                        remoteAvatarUrl = picture.ifBlank { localAcc.remoteAvatarUrl }
                    )
                )
            }
        }
    }

    private suspend fun handleIncomingTextNote(event: NostrEvent) {
        if (event.content.isBlank()) return
        val localAcc = dao.getAccountByPubkey(event.pubkey)
        val npub = runCatching { NostrCrypto.encodeNpub(event.pubkey) }.getOrDefault(event.pubkey.take(16))
        val note = NoteEntity(
            eventId = event.id,
            pubkeyHex = event.pubkey,
            npub = npub,
            authorName = localAcc?.displayName ?: "npub1…${npub.takeLast(6)}",
            authorAvatar = localAcc?.localAvatarPath?.ifBlank { localAcc.remoteAvatarUrl }.orEmpty(),
            content = event.content,
            createdAtSec = event.createdAt,
            sigHex = event.sig,
            acceptedRelaysCount = 1,
            rawEventJson = event.toJsonString(),
            isLocalUser = localAcc != null
        )
        dao.upsertNote(note)
    }

    private suspend fun handleIncomingManifest(event: NostrEvent) {
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: "root"
        val title = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
            ?.ifBlank { null } ?: dTag
        val desc = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
            ?.ifBlank { null } ?: event.content.ifBlank { "Decentralized Nostr web manifest" }
        val server = event.tags.firstOrNull { it.size >= 2 && it[0] == "server" }?.get(1)
            ?: "https://blossom.primal.net"
        val pathMap = JSONObject()
        var indexSha = ""
        event.tags.filter { it.size >= 3 && it[0] == "path" }.forEach { tag ->
            pathMap.put(tag[1], tag[2])
            if (tag[1] == "/index.html" || tag[1] == "index.html") {
                indexSha = tag[2]
            }
        }
        if (indexSha.isBlank() && pathMap.length() == 0) return
        val appletId = "${event.kind}:${event.pubkey}:$dTag"
        val existing = dao.getAppletById(appletId)
        val isNsite = event.kind == NostrKinds.NSITE_NAMED || event.kind == NostrKinds.NSITE_ROOT
        val npub = runCatching { NostrCrypto.encodeNpub(event.pubkey) }.getOrDefault(event.pubkey.take(16))
        dao.upsertApplet(
            InstalledAppletEntity(
                appletId = appletId,
                kind = event.kind,
                authorPubkey = event.pubkey,
                authorNpub = npub,
                dTag = dTag,
                title = title,
                description = desc,
                iconGlyph = existing?.iconGlyph ?: if (isNsite) "🌐" else "🧩",
                accentHex = existing?.accentHex ?: if (isNsite) "#5CC8FF" else "#A47BFF",
                isNsite = isNsite,
                enableNip07 = existing?.enableNip07 ?: isNsite,
                blossomServer = server,
                indexSha256 = indexSha,
                manifestPathsJson = pathMap.toString(),
                inlineHtml = existing?.inlineHtml.orEmpty(),
                inlineCss = existing?.inlineCss.orEmpty(),
                inlineJs = existing?.inlineJs.orEmpty(),
                isInstalled = existing?.isInstalled ?: false,
                pinOrder = existing?.pinOrder ?: 99
            )
        )
    }

    // --- Account & Key Vault Management ---

    suspend fun createNewAccount(alias: String): VaultAccountEntity = withContext(Dispatchers.IO) {
        val kp = NostrCrypto.generateKeyPair()
        val enc = keyVault.encryptPrivateKeyHex(kp.privKeyHex)
        val entity = VaultAccountEntity(
            pubkeyHex = kp.pubKeyHex,
            npub = kp.npub,
            encryptedPrivKeyBase64 = enc.ciphertextBase64,
            ivBase64 = enc.ivBase64,
            displayName = alias.trim().ifBlank { "Identity ${kp.npub.takeLast(4)}" },
            bio = "",
            isActive = true
        )
        dao.clearActiveAccounts()
        dao.upsertAccount(entity)
        entity
    }

    suspend fun importAccountFromKey(rawKeyInput: String, alias: String): VaultAccountEntity =
        withContext(Dispatchers.IO) {
            val kp = NostrCrypto.importPrivateKey(rawKeyInput)
            val enc = keyVault.encryptPrivateKeyHex(kp.privKeyHex)
            val existing = dao.getAccountByPubkey(kp.pubKeyHex)
            val entity = VaultAccountEntity(
                pubkeyHex = kp.pubKeyHex,
                npub = kp.npub,
                encryptedPrivKeyBase64 = enc.ciphertextBase64,
                ivBase64 = enc.ivBase64,
                displayName = alias.trim().ifBlank { existing?.displayName ?: "Imported ${kp.npub.takeLast(5)}" },
                bio = existing?.bio.orEmpty(),
                localAvatarPath = existing?.localAvatarPath.orEmpty(),
                remoteAvatarUrl = existing?.remoteAvatarUrl.orEmpty(),
                isActive = true
            )
            dao.clearActiveAccounts()
            dao.upsertAccount(entity)
            relayPool.requestAuthorMetadata(kp.pubKeyHex)
            entity
        }

    suspend fun switchAccount(pubkeyHex: String) = withContext(Dispatchers.IO) {
        dao.switchActiveAccount(pubkeyHex)
        relayPool.requestAuthorMetadata(pubkeyHex)
    }

    suspend fun exportActiveNsec(): String? = withContext(Dispatchers.IO) {
        val active = dao.getActiveAccount() ?: return@withContext null
        val privHex = keyVault.decryptPrivateKeyHex(active.encryptedPrivKeyBase64, active.ivBase64)
        NostrCrypto.encodeNsec(privHex)
    }

    suspend fun getActivePrivateKeyHex(): Pair<VaultAccountEntity, String>? = withContext(Dispatchers.IO) {
        val active = dao.getActiveAccount() ?: return@withContext null
        val privHex = keyVault.decryptPrivateKeyHex(active.encryptedPrivKeyBase64, active.ivBase64)
        active to privHex
    }

    // --- User Profile System (Display Name, Avatar Upload, Short Bio -> Associated with Nostr Pubkey & Kind 0) ---

    suspend fun copyPickedImageToInternalFile(uri: Uri, folderName: String, prefix: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(appContext.filesDir, folderName).apply { mkdirs() }
                val outFile = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                outFile.absolutePath
            }.getOrNull()
        }

    suspend fun saveAndPublishUserProfile(
        displayName: String,
        bio: String,
        localAvatarPath: String,
        uploadAvatarToBlossom: Boolean,
        badgeGlyph: String = "≠",
        badgeColorHex: String = "#FF1493"
    ): RelayPublishSummary = withContext(Dispatchers.IO) {
        val (active, privHex) = getActivePrivateKeyHex()
            ?: error("No active Nostr keypair found")
        val prefs = prefsRepo.prefsFlow.first()

        var pictureUrl = active.remoteAvatarUrl
        if (uploadAvatarToBlossom && localAvatarPath.isNotBlank()) {
            val file = File(localAvatarPath)
            if (file.exists()) {
                val uploadRes = blossomClient.uploadBlob(
                    serverUrl = prefs.primaryBlossomServer,
                    bytes = file.readBytes(),
                    mimeType = "image/jpeg",
                    extension = "jpg",
                    privKeyHex = privHex
                )
                pictureUrl = uploadRes.url
            }
        }

        val updatedAccount = active.copy(
            displayName = displayName.trim(),
            bio = bio.trim(),
            localAvatarPath = localAvatarPath,
            remoteAvatarUrl = pictureUrl,
            badgeGlyph = badgeGlyph.trim().take(3).ifBlank { "≠" },
            badgeColorHex = badgeColorHex.ifBlank { "#FF1493" }
        )
        dao.upsertAccount(updatedAccount)

        val metadataJson = JSONObject().apply {
            put("name", updatedAccount.displayName)
            put("display_name", updatedAccount.displayName)
            put("about", updatedAccount.bio)
            if (pictureUrl.isNotBlank()) {
                put("picture", pictureUrl)
            }
            put("glyph", updatedAccount.badgeGlyph)
            put("accent_color", updatedAccount.badgeColorHex)
            put("npub", updatedAccount.npub)
        }.toString()

        val signedKind0 = NostrEvent.createAndSign(
            privKeyHex = privHex,
            kind = NostrKinds.METADATA,
            content = metadataJson,
            tags = listOf(listOf("client", "SovereignShell"))
        )
        recordAudit(
            account = updatedAccount,
            appletId = "shell:profile",
            appletTitle = "SovereignShell Profile",
            protocolLabel = "Built-in Signer",
            method = "signEvent",
            eventKind = NostrKinds.METADATA,
            approved = true,
            summary = "Signed Kind 0 profile metadata (${updatedAccount.displayName})"
        )
        relayPool.publishEvent(signedKind0)
    }

    // --- Relay Management Interface (Add URL, View Connected Status, Remove Relays) ---

    suspend fun addRelayUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        val normalized = RelayPoolClient.normalizeRelayUrl(rawUrl)
        require(normalized.startsWith("wss://") || normalized.startsWith("ws://")) {
            "Relay URL must start with wss:// or ws://"
        }
        require(normalized.length > 8 && normalized.contains('.')) {
            "Please enter a valid relay hostname (e.g. wss://relay.damus.io)"
        }
        dao.upsertRelay(RelayEntity(url = normalized))
        relayPool.connectRelay(normalized)
        normalized
    }

    suspend fun removeRelayUrl(url: String) = withContext(Dispatchers.IO) {
        val normalized = RelayPoolClient.normalizeRelayUrl(url)
        dao.deleteRelay(normalized)
        relayPool.disconnectRelay(normalized, removeFromMap = true)
    }

    fun reconnectRelay(url: String) {
        relayPool.connectRelay(url)
    }

    suspend fun publishRelayListNip65(): RelayPublishSummary = withContext(Dispatchers.IO) {
        val (active, privHex) = getActivePrivateKeyHex() ?: error("No active account")
        val relays = dao.getRelaysList()
        val tags = relays.map { listOf("r", it.url) }
        val ev = NostrEvent.createAndSign(
            privKeyHex = privHex,
            kind = NostrKinds.RELAY_LIST,
            content = "",
            tags = tags
        )
        recordAudit(
            account = active,
            appletId = "shell:relays",
            appletTitle = "Relay Manager",
            protocolLabel = "Built-in Signer",
            method = "signEvent",
            eventKind = NostrKinds.RELAY_LIST,
            approved = true,
            summary = "Published NIP-65 Kind 10002 relay list (${relays.size} relays)"
        )
        relayPool.publishEvent(ev)
    }

    // --- Create, Sign & Publish Nostr Notes (NIP-01 Kind 1 Events) ---

    suspend fun createSignAndPublishNote(
        content: String,
        hashtags: List<String> = emptyList()
    ): RelayPublishSummary = withContext(Dispatchers.IO) {
        val trimmed = content.trim()
        require(trimmed.isNotBlank()) { "Note content cannot be empty" }
        val (active, privHex) = getActivePrivateKeyHex() ?: error("No active Nostr account")

        val tags = buildList {
            hashtags.map { it.trim().removePrefix("#") }
                .filter { it.isNotBlank() }
                .forEach { add(listOf("t", it)) }
            add(listOf("client", "SovereignShell"))
        }

        val signedNote = NostrEvent.createAndSign(
            privKeyHex = privHex,
            kind = NostrKinds.TEXT_NOTE,
            content = trimmed,
            tags = tags
        )
        recordAudit(
            account = active,
            appletId = "shell:notes",
            appletTitle = "Shell Note Composer",
            protocolLabel = "Built-in Signer",
            method = "signEvent",
            eventKind = NostrKinds.TEXT_NOTE,
            approved = true,
            summary = "Signed Kind 1 note: ${trimmed.take(42)}"
        )

        val summary = relayPool.publishEvent(signedNote)
        dao.upsertNote(
            NoteEntity(
                eventId = signedNote.id,
                pubkeyHex = active.pubkeyHex,
                npub = active.npub,
                authorName = active.displayName.ifBlank { active.npub.take(14) },
                authorAvatar = active.localAvatarPath.ifBlank { active.remoteAvatarUrl },
                content = signedNote.content,
                createdAtSec = signedNote.createdAt,
                sigHex = signedNote.sig,
                acceptedRelaysCount = summary.acceptedCount,
                rawEventJson = signedNote.toJsonString(),
                isLocalUser = true
            )
        )
        summary
    }

    suspend fun mirrorPublishedNote(event: NostrEvent, acceptedCount: Int) = withContext(Dispatchers.IO) {
        val active = dao.getAccountByPubkey(event.pubkey)
        val npub = active?.npub ?: runCatching { NostrCrypto.encodeNpub(event.pubkey) }.getOrDefault(event.pubkey.take(16))
        dao.upsertNote(
            NoteEntity(
                eventId = event.id,
                pubkeyHex = event.pubkey,
                npub = npub,
                authorName = active?.displayName?.ifBlank { npub.take(14) } ?: npub.take(14),
                authorAvatar = active?.localAvatarPath?.ifBlank { active.remoteAvatarUrl }.orEmpty(),
                content = event.content,
                createdAtSec = event.createdAt,
                sigHex = event.sig,
                acceptedRelaysCount = acceptedCount,
                rawEventJson = event.toJsonString(),
                isLocalUser = active != null
            )
        )
    }

    // --- Applet & Nsite Publishing + Self-Publishing Shell ---

    suspend fun publishProjectToBlossomAndRelays(
        title: String,
        dTagRaw: String,
        description: String,
        iconGlyph: String,
        targetKind: Int,
        indexHtml: String,
        styleCss: String,
        appJs: String
    ): PublishDeploymentReport = withContext(Dispatchers.IO) {
        val (active, privHex) = getActivePrivateKeyHex() ?: error("No active signer key")
        val prefs = prefsRepo.prefsFlow.first()
        val blossomServer = prefs.primaryBlossomServer
        val cleanDTag = dTagRaw.trim().lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .trim('-')
            .take(13)
            .ifBlank { "app" }

        val uploads = mutableListOf<BlossomClient.BlobUploadResult>()
        val htmlRes = blossomClient.uploadBlob(
            serverUrl = blossomServer,
            bytes = indexHtml.toByteArray(Charsets.UTF_8),
            mimeType = "text/html; charset=utf-8",
            extension = "html",
            privKeyHex = privHex
        )
        uploads.add(htmlRes)

        val cssRes = blossomClient.uploadBlob(
            serverUrl = blossomServer,
            bytes = styleCss.toByteArray(Charsets.UTF_8),
            mimeType = "text/css; charset=utf-8",
            extension = "css",
            privKeyHex = privHex
        )
        uploads.add(cssRes)

        val jsRes = blossomClient.uploadBlob(
            serverUrl = blossomServer,
            bytes = appJs.toByteArray(Charsets.UTF_8),
            mimeType = "application/javascript; charset=utf-8",
            extension = "js",
            privKeyHex = privHex
        )
        uploads.add(jsRes)

        val aggregateSha = NostrCrypto.sha256Hex("${htmlRes.sha256}:${cssRes.sha256}:${jsRes.sha256}")
        val tags = buildList {
            if (targetKind != NostrKinds.NSITE_ROOT && targetKind != NostrKinds.NAPPLET_ROOT) {
                add(listOf("d", cleanDTag))
            }
            add(listOf("title", title.trim()))
            add(listOf("description", description.trim()))
            add(listOf("path", "/index.html", htmlRes.sha256))
            add(listOf("path", "/style.css", cssRes.sha256))
            add(listOf("path", "/app.js", jsRes.sha256))
            add(listOf("x", aggregateSha))
            add(listOf("server", blossomServer))
            add(listOf("client", "SovereignShell"))
        }

        val manifestEvent = NostrEvent.createAndSign(
            privKeyHex = privHex,
            kind = targetKind,
            content = description.trim(),
            tags = tags
        )
        recordAudit(
            account = active,
            appletId = "shell:studio",
            appletTitle = "Creator Studio Publisher",
            protocolLabel = "Built-in Signer",
            method = "signEvent",
            eventKind = targetKind,
            approved = true,
            summary = "Published Kind $targetKind manifest ($title)"
        )
        val publishSummary = relayPool.publishEvent(manifestEvent)

        val isNsite = targetKind == NostrKinds.NSITE_NAMED || targetKind == NostrKinds.NSITE_ROOT
        val appletId = "$targetKind:${active.pubkeyHex}:${if (targetKind == NostrKinds.NSITE_ROOT) "root" else cleanDTag}"
        val pathsJson = JSONObject().apply {
            put("/index.html", htmlRes.sha256)
            put("/style.css", cssRes.sha256)
            put("/app.js", jsRes.sha256)
        }.toString()

        dao.upsertApplet(
            InstalledAppletEntity(
                appletId = appletId,
                kind = targetKind,
                authorPubkey = active.pubkeyHex,
                authorNpub = active.npub,
                dTag = cleanDTag,
                title = title.trim(),
                description = description.trim(),
                iconGlyph = iconGlyph.ifBlank { if (isNsite) "🌐" else "🚀" },
                accentHex = if (isNsite) "#2EE59D" else "#A47BFF",
                isNsite = isNsite,
                enableNip07 = isNsite,
                blossomServer = blossomServer,
                indexSha256 = htmlRes.sha256,
                manifestPathsJson = pathsJson,
                inlineHtml = indexHtml,
                inlineCss = styleCss,
                inlineJs = appJs,
                isInstalled = true,
                pinOrder = 0
            )
        )

        val gatewayUrl = if (targetKind == NostrKinds.NSITE_ROOT) {
            "https://${active.npub}.nsite.lol/"
        } else {
            "https://$cleanDTag.${active.npub}.nsite.lol/"
        }

        PublishDeploymentReport(
            title = title.trim(),
            kind = targetKind,
            manifestEventId = manifestEvent.id,
            npub = active.npub,
            dTag = cleanDTag,
            uploadedFiles = uploads,
            acceptedRelays = publishSummary.acceptedCount,
            totalRelays = publishSummary.receipts.size,
            gatewayPreviewUrl = gatewayUrl
        )
    }

    suspend fun selfPublishShellAsNsite(
        asRootNsite: Boolean,
        customDTag: String,
        preset: GraphicTemplatePreset = GraphicTemplatePreset.BREBRE_SPECIMEN
    ): PublishDeploymentReport =
        withContext(Dispatchers.IO) {
            val active = dao.getActiveAccount() ?: error("No active Nostr identity")
            val relays = dao.getRelaysList().map { it.url }
            val pinned = dao.observeInstalledApplets().first()
            val bundle = StarterCatalog.buildSelfPublishedWebShell(active, relays, pinned, preset)
            val kind = if (asRootNsite) NostrKinds.NSITE_ROOT else NostrKinds.NSITE_NAMED
            publishProjectToBlossomAndRelays(
                title = "${active.displayName.ifBlank { "Sovereign" }} Shell",
                dTagRaw = customDTag.ifBlank { "shell" },
                description = "Self-published NIP-5A & NIP-5D SovereignShell (${preset.label}) with ${pinned.size} pinned apps.",
                iconGlyph = active.badgeGlyph.ifBlank { "☆" },
                targetKind = kind,
                indexHtml = bundle.indexHtml,
                styleCss = bundle.styleCss,
                appJs = bundle.appJs
            )
        }

    // --- Applet Pinning, Runtime Hydration & Scoped Storage ---

    suspend fun setAppletInstalled(appletId: String, installed: Boolean) =
        dao.setAppletInstalled(appletId, installed)

    suspend fun setAppletNip07(appletId: String, enabled: Boolean) =
        dao.setAppletNip07(appletId, enabled)

    suspend fun resolveAppletDocument(applet: InstalledAppletEntity): String = withContext(Dispatchers.IO) {
        val htmlBody = if (applet.inlineHtml.isNotBlank()) {
            applet.inlineHtml
        } else if (applet.indexSha256.isNotBlank()) {
            blossomClient.fetchBlobText(listOf(applet.blossomServer), applet.indexSha256)
                ?: "<div class='card'><h2>${applet.title}</h2><p>${applet.description}</p><p class='mono'>Blossom SHA-256: ${applet.indexSha256}</p></div>"
        } else {
            "<div class='card'><h2>${applet.title}</h2><p>${applet.description}</p></div>"
        }

        if (htmlBody.contains("<html", ignoreCase = true)) {
            htmlBody
        } else {
            """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <style>${applet.inlineCss.ifBlank { StarterCatalog.COMMON_APPLET_CSS }}</style>
</head>
<body>
  $htmlBody
  <script>${applet.inlineJs}</script>
</body>
</html>
            """.trimIndent()
        }
    }

    suspend fun napStorageGet(appletId: String, key: String): String? =
        dao.getAppletStorageValue(appletId, key)

    suspend fun napStorageSet(appletId: String, key: String, value: String) =
        dao.putAppletStorageValue(AppletStorageEntity(appletId = appletId, storageKey = key, storageValue = value))

    suspend fun napStorageRemove(appletId: String, key: String) =
        dao.removeAppletStorageValue(appletId, key)

    // --- Signer Policies & Audit ---

    suspend fun checkSavedPolicy(pubkeyHex: String, appletId: String, method: String, kind: Int): Boolean? {
        val key = "$pubkeyHex|$appletId|$method|$kind"
        return dao.getSignerPolicy(key)?.allowAlways
    }

    suspend fun saveAlwaysAllowPolicy(
        pubkeyHex: String,
        appletId: String,
        appletTitle: String,
        method: String,
        kind: Int
    ) {
        val key = "$pubkeyHex|$appletId|$method|$kind"
        dao.upsertSignerPolicy(
            SignerPolicyEntity(
                policyKey = key,
                pubkeyHex = pubkeyHex,
                appletId = appletId,
                appletTitle = appletTitle,
                method = method,
                eventKind = kind,
                allowAlways = true
            )
        )
    }

    suspend fun revokePolicy(policyKey: String) = dao.deleteSignerPolicy(policyKey)

    suspend fun recordAudit(
        account: VaultAccountEntity,
        appletId: String,
        appletTitle: String,
        protocolLabel: String,
        method: String,
        eventKind: Int,
        approved: Boolean,
        summary: String
    ) {
        dao.insertAuditEntry(
            SignerAuditEntity(
                pubkeyHex = account.pubkeyHex,
                appletId = appletId,
                appletTitle = appletTitle,
                protocolLabel = protocolLabel,
                method = method,
                eventKind = eventKind,
                approved = approved,
                summary = summary
            )
        )
    }
}
