package com.example.data

import android.content.Context
import android.net.Uri
import com.example.nostr.BlossomClient
import com.example.nostr.KeyVaultManager
import com.example.nostr.NostrCrypto
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.NsiteSpamFilter
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
    val rawCatalogAppletsFlow: Flow<List<InstalledAppletEntity>> = dao.observeAllCatalogApplets()
    val filteredCatalogFlow: Flow<NsiteSpamFilter.CatalogFilterResult> = combine(
        dao.observeAllCatalogApplets(),
        dao.observeActiveAccount()
    ) { items, active ->
        NsiteSpamFilter.filterCatalogWithClustering(items, active?.pubkeyHex.orEmpty())
    }
    val catalogAppletsFlow: Flow<List<InstalledAppletEntity>> = combine(
        filteredCatalogFlow
    ) { arr -> arr.first().cleanItems }
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

        // 2. Seed default relays if empty (or upgrade untouched 3-relay initial set with Napplet indexers)
        val currentRelays = dao.getRelaysList()
        val currentUrlSet = currentRelays.map { it.url }.toSet()
        val relayUrls = if (currentRelays.isEmpty() || currentUrlSet == RelayPoolClient.LEGACY_INITIAL_RELAYS) {
            RelayPoolClient.DEFAULT_RELAYS.forEach { url ->
                if (url !in currentUrlSet) {
                    dao.upsertRelay(RelayEntity(url = url))
                }
            }
            RelayPoolClient.DEFAULT_RELAYS
        } else {
            currentRelays.map { it.url }
        }
        relayPool.syncRelays(relayUrls)

        // 3. Seed starter Napplets & Nsites catalog if empty, and purge any cached spam bot entries
        if (dao.countApplets() == 0 && active != null) {
            val starters = StarterCatalog.defaultCatalog(active.pubkeyHex, active.npub)
            dao.upsertApplets(starters)
        } else {
            purgeCachedSpamApplets(active?.pubkeyHex.orEmpty())
        }

        active?.let { relayPool.requestAuthorMetadata(it.pubkeyHex) }
    }

    suspend fun purgeCachedSpamApplets(localPubkeyHex: String): Int = withContext(Dispatchers.IO) {
        val all = dao.getAllCatalogAppletsList()
        val spamIds = NsiteSpamFilter.findSpamAppletIds(all, localPubkeyHex)
        if (spamIds.isNotEmpty()) {
            spamIds.chunked(200).forEach { batch ->
                dao.deleteUninstalledApplets(batch)
            }
        }
        spamIds.size
    }

    suspend fun refreshCatalogFromRelays() = withContext(Dispatchers.IO) {
        val active = dao.getActiveAccount()
        purgeCachedSpamApplets(active?.pubkeyHex.orEmpty())
        relayPool.refreshDiscoveryCatalog()
    }

    private suspend fun observeRelayEvents() {
        relayPool.incomingEvents.collect { (_, event) ->
            when (event.kind) {
                NostrKinds.METADATA -> handleIncomingMetadata(event)
                NostrKinds.TEXT_NOTE -> handleIncomingTextNote(event)
                NostrKinds.NAPPLET_NAMED, NostrKinds.NAPPLET_ROOT, NostrKinds.NAPPLET_SNAPSHOT,
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
        val dTag = when (event.kind) {
            NostrKinds.NAPPLET_SNAPSHOT -> event.id.take(10)
            NostrKinds.NAPPLET_ROOT, NostrKinds.NSITE_ROOT -> "root"
            else -> event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)?.ifBlank { null } ?: "root"
        }
        val appletId = "${event.kind}:${event.pubkey}:$dTag"
        val existing = dao.getAppletById(appletId)
        val localAccount = dao.getAccountByPubkey(event.pubkey)
        val parsed = parseManifestEventToEntity(
            event = event,
            existing = existing,
            isLocalAuthor = localAccount != null
        ) ?: return
        dao.upsertApplet(parsed)
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
        val directUrl = runCatching {
            if (applet.manifestPathsJson.isNotBlank()) {
                JSONObject(applet.manifestPathsJson).optString("__direct_url")
            } else ""
        }.getOrDefault("")

        val htmlBody = if (applet.inlineHtml.isNotBlank()) {
            applet.inlineHtml
        } else if (applet.indexSha256.isNotBlank() || directUrl.isNotBlank()) {
            blossomClient.fetchBlobText(
                servers = listOf(applet.blossomServer),
                sha256 = applet.indexSha256,
                directUrl = directUrl
            ) ?: "<div class='card'><h2>${applet.title}</h2><p>${applet.description}</p><p class='mono'>Blossom SHA-256: ${applet.indexSha256.ifBlank { directUrl }}</p></div>"
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

    companion object {
        private val SHA256_HEX_REGEX = Regex("""^[0-9a-fA-F]{64}$""")

        /**
         * Parses both NIP-5D Napplet manifests (Kinds 35129, 15129, 5129 — which use `["x", "<sha256>"]`,
         * `["url", ...]`, or inline HTML) AND NIP-5A Nsite manifests (Kinds 35128, 15128 — which use
         * `["path", "/index.html", "<sha256>"]`), while filtering SEO/gambling spam bots.
         */
        fun parseManifestEventToEntity(
            event: NostrEvent,
            existing: InstalledAppletEntity? = null,
            isLocalAuthor: Boolean = false
        ): InstalledAppletEntity? {
            val isNsite = event.kind == NostrKinds.NSITE_NAMED || event.kind == NostrKinds.NSITE_ROOT
            val dTag = when (event.kind) {
                NostrKinds.NAPPLET_SNAPSHOT -> event.id.take(10)
                NostrKinds.NAPPLET_ROOT, NostrKinds.NSITE_ROOT -> "root"
                else -> event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }
                    ?.get(1)?.trim()?.ifBlank { null } ?: "root"
            }

            val pathMap = JSONObject()
            var pathIndexSha = ""
            var fallbackHtmlSha = ""
            event.tags.filter { it.size >= 3 && it[0] == "path" }.forEach { tag ->
                val route = tag[1].trim()
                val sha = tag[2].trim()
                if (route.isNotBlank() && sha.isNotBlank()) {
                    pathMap.put(route, sha)
                    if (route == "/index.html" || route == "index.html" || route == "/") {
                        pathIndexSha = sha
                    } else if (fallbackHtmlSha.isBlank() && route.endsWith(".html", ignoreCase = true)) {
                        fallbackHtmlSha = sha
                    }
                }
            }

            // NIP-5D standard: single-file bundle hash is stored in ["x", "<64-char-sha256>"]
            val xTagSha = event.tags
                .firstOrNull { it.size >= 2 && (it[0] == "x" || it[0] == "sha256") && SHA256_HEX_REGEX.matches(it[1].trim()) }
                ?.get(1)?.trim().orEmpty()

            val directUrl = event.tags
                .firstOrNull {
                    it.size >= 2 &&
                        (it[0] == "url" || it[0] == "u" || it[0] == "r") &&
                        (it[1].startsWith("https://", ignoreCase = true) || it[1].startsWith("http://", ignoreCase = true))
                }
                ?.get(1)?.trim().orEmpty()

            val trimmedContent = event.content.trim()
            val contentLooksLikeHtml = trimmedContent.startsWith("<!DOCTYPE", ignoreCase = true) ||
                trimmedContent.startsWith("<html", ignoreCase = true) ||
                trimmedContent.startsWith("<div", ignoreCase = true) ||
                trimmedContent.startsWith("<main", ignoreCase = true) ||
                trimmedContent.startsWith("<section", ignoreCase = true)

            val resolvedIndexSha = pathIndexSha
                .ifBlank { fallbackHtmlSha }
                .ifBlank { xTagSha }

            // Reject empty shell events that carry neither Blossom SHA, path map, URL, nor HTML
            if (resolvedIndexSha.isBlank() && pathMap.length() == 0 && directUrl.isBlank() && !contentLooksLikeHtml) {
                return null
            }

            val rawTitle = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)?.ifBlank { null }
                ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "name" }?.get(1)?.ifBlank { null }
                ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "alt" }?.get(1)?.ifBlank { null }
                ?: humanizeSlug(dTag, isNsite, event.pubkey)
            val cleanTitle = NsiteSpamFilter.sanitizeSingleLine(rawTitle).take(80)

            val rawDesc = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)?.ifBlank { null }
                ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "summary" }?.get(1)?.ifBlank { null }
                ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "about" }?.get(1)?.ifBlank { null }
                ?: if (!contentLooksLikeHtml && trimmedContent.isNotBlank() && !trimmedContent.startsWith("{")) {
                    trimmedContent
                } else {
                    if (isNsite) "Decentralized NIP-5A static Nsite on Blossom." else "Sandboxed NIP-5D Nostr web applet."
                }
            val cleanDesc = NsiteSpamFilter.sanitizeSingleLine(rawDesc).take(260)

            val effectivePathCount = pathMap.length().let { if (it == 0 && resolvedIndexSha.isNotBlank()) 1 else it }
            if (!isLocalAuthor && existing?.isInstalled != true) {
                if (NsiteSpamFilter.isIndividualSpam(event.kind, dTag, cleanTitle, cleanDesc, effectivePathCount)) {
                    return null
                }
            }

            if (directUrl.isNotBlank()) {
                pathMap.put("__direct_url", directUrl)
            }

            val serverFromTag = event.tags
                .firstOrNull {
                    it.size >= 2 &&
                        (it[0] == "server" || it[0] == "blossom") &&
                        (it[1].startsWith("https://", ignoreCase = true) || it[1].startsWith("http://", ignoreCase = true))
                }
                ?.get(1)?.trim()
            val serverFromDirectUrl = if (directUrl.isNotBlank()) {
                runCatching {
                    val uri = java.net.URI(directUrl)
                    if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else null
                }.getOrNull()
            } else null
            val server = serverFromTag ?: serverFromDirectUrl ?: "https://blossom.primal.net"

            val npub = runCatching { NostrCrypto.encodeNpub(event.pubkey) }.getOrDefault(event.pubkey.take(16))
            val appletId = "${event.kind}:${event.pubkey}:$dTag"

            return InstalledAppletEntity(
                appletId = appletId,
                kind = event.kind,
                authorPubkey = event.pubkey,
                authorNpub = npub,
                dTag = dTag,
                title = cleanTitle,
                description = cleanDesc,
                iconGlyph = existing?.iconGlyph ?: pickGlyphForManifest(cleanTitle, cleanDesc, isNsite),
                accentHex = existing?.accentHex ?: if (isNsite) "#5CC8FF" else "#FF1493",
                isNsite = isNsite,
                enableNip07 = existing?.enableNip07 ?: isNsite,
                blossomServer = server,
                indexSha256 = resolvedIndexSha,
                manifestPathsJson = pathMap.toString(),
                inlineHtml = existing?.inlineHtml?.ifBlank { null }
                    ?: if (contentLooksLikeHtml) trimmedContent else "",
                inlineCss = existing?.inlineCss.orEmpty(),
                inlineJs = existing?.inlineJs.orEmpty(),
                isInstalled = existing?.isInstalled ?: false,
                pinOrder = existing?.pinOrder ?: 99,
                updatedAt = event.createdAt * 1000L
            )
        }

        private fun humanizeSlug(dTag: String, isNsite: Boolean, pubkey: String): String {
            if (dTag == "root" || dTag.isBlank()) {
                return if (isNsite) "Nsite ${pubkey.take(6)}" else "Napplet ${pubkey.take(6)}"
            }
            return dTag.split('-', '_')
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
                }
                .ifBlank { dTag }
        }

        private fun pickGlyphForManifest(title: String, desc: String, isNsite: Boolean): String {
            val hay = "$title $desc".lowercase()
            return when {
                "zap" in hay || "lightning" in hay || "wallet" in hay || "sats" in hay -> "⚡"
                "chat" in hay || "dm" in hay || "message" in hay || "irc" in hay -> "💬"
                "game" in hay || "chess" in hay || "play" in hay || "mines" in hay || "word" in hay || "puzzle" in hay -> "🎮"
                "music" in hay || "radio" in hay || "audio" in hay || "synth" in hay || "beat" in hay -> "🎵"
                "draw" in hay || "pixel" in hay || "art" in hay || "canvas" in hay || "meme" in hay -> "🎨"
                "poll" in hay || "vote" in hay || "stats" in hay || "analytics" in hay -> "📊"
                "poll" in hay || "note" in hay || "blog" in hay || "zine" in hay || "wiki" in hay || "read" in hay -> "📖"
                "calc" in hay || "convert" in hay || "tool" in hay || "json" in hay -> "🧮"
                isNsite -> "🌐"
                else -> "🧩"
            }
        }
    }
}
