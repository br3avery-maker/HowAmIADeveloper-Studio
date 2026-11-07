package com.example.data

import android.content.Context
import android.net.Uri
import com.example.nostr.AdaptiveNetworkEngine
import com.example.nostr.BlossomClient
import com.example.nostr.ExternalSignerManager
import com.example.nostr.ExternalSiteLauncher
import com.example.nostr.KeyVaultManager
import com.example.nostr.NetworkOperationType
import com.example.nostr.NostrCrypto
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.NsiteAddressResolver
import com.example.nostr.NsiteSpamFilter
import com.example.nostr.RelayPoolClient
import com.example.nostr.RelayPublishSummary
import com.example.nostr.RelayStatusInfo
import com.example.nostr.SignerType
import com.example.nostr.UnsignedNostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

data class ResolvedWebAsset(
    val bytes: ByteArray,
    val mimeType: String,
    val encoding: String?,
    val sha256: String = ""
)

data class ManagedRelayItem(
    val url: String,
    val readEnabled: Boolean,
    val writeEnabled: Boolean,
    val statusInfo: RelayStatusInfo,
    val health: RelayHealthEntity? = null
)

data class ManagedBlossomItem(
    val serverUrl: String,
    val enabled: Boolean,
    val isBackupMirror: Boolean,
    val isPrimary: Boolean,
    val health: BlossomServerHealthEntity? = null
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
    val dao: ShellDao = ShellDatabase.getInstance(context).shellDao(),
    val keyVault: KeyVaultManager = KeyVaultManager(context),
    val relayPool: RelayPoolClient = RelayPoolClient(),
    val blossomClient: BlossomClient = BlossomClient(
        cacheDir = File(context.applicationContext.cacheDir, "blossom_blobs")
    ),
    val prefsRepo: LauncherPreferencesRepository = LauncherPreferencesRepository(context),
    autoBootstrap: Boolean = true
) {
    private val appContext = context.applicationContext
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val networkEngine: AdaptiveNetworkEngine = AdaptiveNetworkEngine(
        dao = dao,
        relayPool = relayPool,
        blossomClient = blossomClient,
        engineScope = repoScope
    )

    val signerManager: ExternalSignerManager = ExternalSignerManager(
        keyVault = keyVault,
        relayPool = relayPool
    )

    val projectRepository: NsiteProjectRepository = NsiteProjectRepository(
        context = appContext,
        dao = dao,
        networkEngine = networkEngine,
        signerManager = signerManager,
        prefsRepo = prefsRepo
    )

    private val authorBlossomCache = ConcurrentHashMap<String, List<String>>()
    private val legacyNsitePathCache = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val metadataRequestedTimestamps = ConcurrentHashMap<String, Long>()

    val accountsFlow: Flow<List<VaultAccountEntity>> = dao.observeAccounts()
    val activeAccountFlow: Flow<VaultAccountEntity?> = dao.observeActiveAccount()
    val installedAppletsFlow: Flow<List<InstalledAppletEntity>> = dao.observeInstalledApplets()
    val rawCatalogAppletsFlow: Flow<List<InstalledAppletEntity>> = dao.observeAllCatalogApplets()

    val publisherProfilesFlow: Flow<Map<String, PublisherProfileEntity>> = combine(
        dao.observePublisherProfiles(),
        dao.observeAccounts()
    ) { remoteProfiles, localAccounts ->
        val map = LinkedHashMap<String, PublisherProfileEntity>(remoteProfiles.size + localAccounts.size)
        remoteProfiles.forEach { profile ->
            map[profile.pubkeyHex.lowercase()] = profile
        }
        localAccounts.forEach { acc ->
            val key = acc.pubkeyHex.lowercase()
            val existing = map[key]
            val localPic = acc.localAvatarPath.ifBlank { acc.remoteAvatarUrl }
            map[key] = PublisherProfileEntity(
                pubkeyHex = key,
                npub = acc.npub,
                displayName = acc.displayName.ifBlank { existing?.displayName.orEmpty() },
                username = existing?.username.orEmpty(),
                nip05 = existing?.nip05.orEmpty(),
                pictureUrl = localPic.ifBlank { existing?.pictureUrl.orEmpty() },
                bannerUrl = existing?.bannerUrl.orEmpty(),
                about = acc.bio.ifBlank { existing?.about.orEmpty() },
                website = existing?.website.orEmpty(),
                lud16 = existing?.lud16.orEmpty(),
                eventCreatedAtSec = existing?.eventCreatedAtSec ?: (acc.createdAt / 1000L)
            )
        }
        map
    }

    val filteredCatalogFlow: Flow<NsiteSpamFilter.CatalogFilterResult> = combine(
        dao.observeAllCatalogApplets(),
        dao.observeActiveAccount()
    ) { items, active ->
        NsiteSpamFilter.filterCatalogWithClustering(items, active?.pubkeyHex.orEmpty())
    }

    val catalogAppletsFlow: Flow<List<InstalledAppletEntity>> = combine(
        filteredCatalogFlow
    ) { arr -> arr.first().cleanItems }

    val curatedBookmarksFlow: Flow<List<CuratedBookmarkEntity>> = dao.observeCuratedBookmarks()

    val stackedGalleryFlow: Flow<List<StackedAppletGroup>> = combine(
        filteredCatalogFlow,
        dao.observeCuratedBookmarks(),
        dao.observeActiveAccount()
    ) { filtered, bookmarks, active ->
        CatalogStacker.buildStackedGroups(
            items = filtered.cleanItems,
            bookmarks = bookmarks,
            localPubkeyHex = active?.pubkeyHex.orEmpty()
        )
    }

    val notesFlow: Flow<List<NoteEntity>> = dao.observeNotes()
    val auditLogFlow: Flow<List<SignerAuditEntity>> = dao.observeAuditLog()
    val signerPoliciesFlow: Flow<List<SignerPolicyEntity>> = dao.observeSignerPolicies()

    val managedRelaysFlow: Flow<List<ManagedRelayItem>> = combine(
        dao.observeRelays(),
        relayPool.relayStatuses,
        dao.observeRelayHealth()
    ) { entities, statusMap, healthList ->
        val healthMap = healthList.associateBy { it.url }
        entities.map { entity ->
            val info = statusMap[entity.url] ?: RelayStatusInfo(url = entity.url)
            ManagedRelayItem(
                url = entity.url,
                readEnabled = entity.readEnabled,
                writeEnabled = entity.writeEnabled,
                statusInfo = info,
                health = healthMap[entity.url]
            )
        }
    }

    val managedBlossomServersFlow: Flow<List<ManagedBlossomItem>> = combine(
        dao.observeBlossomServers(),
        dao.observeBlossomHealth(),
        prefsRepo.prefsFlow
    ) { servers, healthList, prefs ->
        val cleanPrimary = BlossomClient.normalizeServerUrl(prefs.primaryBlossomServer)
        val healthMap = healthList.associateBy { it.serverUrl }
        servers.map { srv ->
            ManagedBlossomItem(
                serverUrl = srv.serverUrl,
                enabled = srv.enabled,
                isBackupMirror = srv.isBackupMirror && !srv.serverUrl.equals(cleanPrimary, ignoreCase = true),
                isPrimary = srv.serverUrl.equals(cleanPrimary, ignoreCase = true),
                health = healthMap[srv.serverUrl]
            )
        }
    }

    init {
        repoScope.launch {
            observeRelayEvents()
        }
        if (autoBootstrap) {
            repoScope.launch {
                bootstrapIfNeeded()
            }
        }
    }

    fun observeNsiteVersions(coordinate: String): Flow<List<NsiteVersionEntity>> =
        dao.observeVersionsForCoordinate(coordinate)

    suspend fun getNsiteVersionsList(coordinate: String): List<NsiteVersionEntity> =
        withContext(Dispatchers.IO) { dao.getVersionsForCoordinate(coordinate) }

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
                bio = "Building & hosting NIP-5A Nsites on Nostr + Blossom.",
                signerType = SignerType.LOCAL_KEYSTORE.name,
                isActive = true
            )
            dao.upsertAccount(initial)
            active = initial
        }

        // 2. Seed default relays & Blossom mirrors if empty
        val currentRelays = dao.getRelaysList()
        val currentUrlSet = currentRelays.map { it.url }.toSet()
        val relayUrls = if (
            currentRelays.isEmpty() ||
            currentUrlSet == RelayPoolClient.LEGACY_INITIAL_RELAYS ||
            currentUrlSet == RelayPoolClient.LEGACY_FIVE_RELAYS
        ) {
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

        val prefs = prefsRepo.prefsFlow.first()
        networkEngine.bootstrapDefaultBlossomServers(prefs.primaryBlossomServer)

        // 3. Ensure initial editable Nsite project exists in Studio
        projectRepository.ensureInitialProjectExists(active?.pubkeyHex.orEmpty())

        // 4. Purge non-NIP-5A rows, legacy fake seed items, and cached spam entries
        dao.purgeNonNsiteApplets()
        purgeLegacyFakeSeedApplets()
        purgeCachedSpamApplets(active?.pubkeyHex.orEmpty())

        // 5. Prioritize live NIP-5A Nsites & Kind 0 profile metadata
        if (NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX.length == 64) {
            relayPool.trackAndQueryAuthor(NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX)
        }
        active?.let {
            relayPool.requestAuthorMetadata(it.pubkeyHex)
            if (it.resolvedSignerType == SignerType.NIP46_BUNKER && it.bunkerUri.isNotBlank()) {
                ExternalSignerManager.parseBunkerUri(it.bunkerUri)?.let { bunker ->
                    if (it.bunkerClientPubkeyHex.length == 64) {
                        relayPool.ensureNip46Subscription(bunker.relays, it.bunkerClientPubkeyHex)
                    }
                }
            }
        }
        requestMissingPublisherProfiles()
    }

    suspend fun purgeLegacyFakeSeedApplets(): Int = withContext(Dispatchers.IO) {
        val all = dao.getAllCatalogAppletsList()
        val fakeIds = all.filter { item ->
            item.indexSha256.isBlank() && item.dTag in StarterCatalog.LEGACY_FAKE_SEED_DTAGS
        }.map { it.appletId }
        if (fakeIds.isNotEmpty()) {
            fakeIds.chunked(200).forEach { batch ->
                dao.deleteAppletsByIds(batch)
            }
        }
        // Also ensure any non-Nsite items from prior schema versions are cleaned up
        val nonNsitePurged = dao.purgeNonNsiteApplets()
        fakeIds.size + nonNsitePurged
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

    suspend fun requestMissingPublisherProfiles(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val candidatePubkeys = buildSet {
            if (NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX.length == 64) {
                add(NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX.lowercase())
            }
            dao.getAllCatalogAppletsList().forEach { app ->
                val pk = app.authorPubkey.trim().lowercase()
                if (pk.length == 64) add(pk)
            }
        }
        val toQuery = candidatePubkeys.filter { pk ->
            val lastReq = metadataRequestedTimestamps[pk] ?: 0L
            val ttlExpired = now - lastReq > 300_000L
            force || (ttlExpired && dao.getPublisherProfile(pk) == null)
        }
        if (toQuery.isNotEmpty()) {
            toQuery.forEach { metadataRequestedTimestamps[it] = now }
            relayPool.requestAuthorsMetadata(toQuery)
        }
    }

    private fun ensureAuthorMetadataRequested(pubkeyHex: String) {
        val pk = pubkeyHex.trim().lowercase()
        if (pk.length != 64) return
        val now = System.currentTimeMillis()
        val prev = metadataRequestedTimestamps[pk] ?: 0L
        if (now - prev > 300_000L) {
            metadataRequestedTimestamps[pk] = now
            relayPool.requestAuthorMetadata(pk)
        }
    }

    fun lookupPublisherAndSync(rawNpubOrHex: String): String? {
        val resolvedHex = NostrCrypto.resolvePubkeyHex(rawNpubOrHex) ?: return null
        metadataRequestedTimestamps[resolvedHex] = System.currentTimeMillis()
        relayPool.trackAndQueryAuthor(resolvedHex)
        return resolvedHex
    }

    suspend fun refreshCatalogFromRelays() = withContext(Dispatchers.IO) {
        val active = dao.getActiveAccount()
        purgeLegacyFakeSeedApplets()
        purgeCachedSpamApplets(active?.pubkeyHex.orEmpty())
        relayPool.refreshDiscoveryCatalog()
        if (NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX.length == 64) {
            relayPool.trackAndQueryAuthor(NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX)
        }
        requestMissingPublisherProfiles(force = true)
    }

    suspend fun ingestNostrEvent(event: NostrEvent) {
        when (event.kind) {
            NostrKinds.METADATA -> handleIncomingMetadata(event)
            NostrKinds.RELAY_LIST -> handleIncomingRelayList(event)
            NostrKinds.USER_BLOSSOM_SERVERS -> handleIncomingBlossomServerList(event)
            NostrKinds.TEXT_NOTE -> handleIncomingTextNote(event)
            NostrKinds.NIP51_BOOKMARK_SET, NostrKinds.NIP51_CATEGORIZED_LIST,
            NostrKinds.NIP51_BOOKMARKS -> handleIncomingNip51List(event)
            NostrKinds.NSITE_LEGACY_FILE -> handleIncomingLegacyNsiteFile(event)
            NostrKinds.NSITE_NAMED, NostrKinds.NSITE_ROOT -> handleIncomingManifest(event)
        }
    }

    private suspend fun observeRelayEvents() {
        relayPool.incomingEvents.collect { (_, event) ->
            ingestNostrEvent(event)
        }
    }

    private fun handleIncomingRelayList(event: NostrEvent) {
        val pk = event.pubkey.trim().lowercase()
        if (pk.length != 64) return
        val isFeatured = pk.equals(NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX, ignoreCase = true)
        if (!isFeatured && !metadataRequestedTimestamps.containsKey(pk)) return

        val writeRelays = event.tags
            .filter { row ->
                row.size >= 2 &&
                    row[0] == "r" &&
                    (row[1].trim().startsWith("wss://", ignoreCase = true) ||
                        row[1].trim().startsWith("ws://", ignoreCase = true)) &&
                    (row.size < 3 || row[2].isBlank() || row[2].equals("write", ignoreCase = true))
            }
            .map { RelayPoolClient.normalizeRelayUrl(it[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)

        if (writeRelays.isNotEmpty()) {
            relayPool.trackAndQueryAuthor(pk, writeRelays)
        }
    }

    private fun handleIncomingBlossomServerList(event: NostrEvent) {
        val pk = event.pubkey.trim().lowercase()
        if (pk.length != 64) return
        val servers = event.tags
            .filter {
                it.size >= 2 &&
                    (it[0] == "server" || it[0] == "r") &&
                    (it[1].startsWith("https://", ignoreCase = true) || it[1].startsWith("http://", ignoreCase = true))
            }
            .map { it[1].trim().trimEnd('/') }
            .distinct()
        if (servers.isNotEmpty()) {
            authorBlossomCache[pk] = servers
        }
    }

    private suspend fun handleIncomingLegacyNsiteFile(event: NostrEvent) {
        val pk = event.pubkey.trim().lowercase()
        if (pk.length != 64) return
        val path = event.tags.firstOrNull { it.size >= 2 && (it[0] == "d" || it[0] == "path") }
            ?.get(1)?.trim().orEmpty()
        val sha = event.tags.firstOrNull { it.size >= 2 && (it[0] == "x" || it[0] == "sha256") }
            ?.get(1)?.trim().orEmpty()
        if (path.isBlank() || sha.length != 64) return

        val normPath = "/" + path.trimStart('/')
        val authorMap = legacyNsitePathCache.getOrPut(pk) { ConcurrentHashMap() }
        authorMap[normPath] = sha.lowercase()

        val legacyId = "${NostrKinds.NSITE_LEGACY_FILE}:$pk:root"
        val existing = dao.getAppletById(legacyId)

        // If the legacy root Nsite already exists and a sibling file event arrives (/style.css, /js/game.js),
        // update its manifestPathsJson immediately so multi-file Kind 34128 sites stay complete!
        if (existing != null && normPath != "/index.html") {
            val updatedPathsJson = JSONObject().apply {
                authorMap.forEach { (k, v) -> put(k, v) }
            }.toString()
            dao.upsertApplet(existing.copy(manifestPathsJson = updatedPathsJson))
            return
        }

        if (normPath == "/index.html") {
            if (dao.getAppletById("${NostrKinds.NSITE_ROOT}:$pk:root") != null) return

            val isFeaturedCreator = pk.equals(NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX, ignoreCase = true)
            val localAcc = dao.getAccountByPubkey(pk)
            val npub = runCatching { NostrCrypto.encodeNpub(pk) }.getOrDefault(pk.take(16))
            val profile = dao.getPublisherProfile(pk)
            val fallbackTitle = profile?.displayName?.ifBlank { profile.username }?.takeIf { it.isNotBlank() }
                ?.let { "$it Nsite" }
                ?: "Nsite ${pk.take(6)}"
            val rawTitle = event.tags.firstOrNull { it.size >= 2 && (it[0] == "title" || it[0] == "name") }
                ?.get(1)?.ifBlank { null }
                ?: existing?.title?.ifBlank { null }
                ?: fallbackTitle
            val cleanTitle = NsiteSpamFilter.sanitizeSingleLine(rawTitle).take(80)
            val cleanDesc = NsiteSpamFilter.sanitizeSingleLine(
                event.content.ifBlank { "Decentralized NIP-5A Nsite on Blossom." }
            ).take(260)

            if (localAcc == null && !isFeaturedCreator && existing?.isInstalled != true) {
                if (NsiteSpamFilter.isIndividualSpam(NostrKinds.NSITE_ROOT, "root", cleanTitle, cleanDesc, authorMap.size.coerceAtLeast(1))) {
                    return
                }
            }

            val server = event.tags
                .firstOrNull {
                    it.size >= 2 &&
                        (it[0] == "server" || it[0] == "blossom") &&
                        (it[1].startsWith("https://", ignoreCase = true) || it[1].startsWith("http://", ignoreCase = true))
                }
                ?.get(1)?.trim()?.trimEnd('/')
                ?: authorBlossomCache[pk]?.firstOrNull()
                ?: "https://blossom.primal.net"

            val pathsJson = JSONObject().apply {
                authorMap.forEach { (k, v) -> put(k, v) }
            }.toString()

            val shouldAutoPin = isFeaturedCreator && existing == null && prefsRepo.markAppletAutoPinnedOnce(legacyId)
            dao.upsertApplet(
                InstalledAppletEntity(
                    appletId = legacyId,
                    kind = NostrKinds.NSITE_ROOT,
                    authorPubkey = pk,
                    authorNpub = npub,
                    dTag = "root",
                    title = cleanTitle,
                    description = cleanDesc,
                    iconGlyph = existing?.iconGlyph ?: "🌐",
                    accentHex = existing?.accentHex ?: "#FF1493",
                    isNsite = true,
                    enableNip07 = existing?.enableNip07 ?: true,
                    blossomServer = server,
                    indexSha256 = sha.lowercase(),
                    manifestPathsJson = pathsJson,
                    isInstalled = if (shouldAutoPin) true else (existing?.isInstalled ?: false),
                    pinOrder = if (shouldAutoPin) 1 else (existing?.pinOrder ?: 99),
                    updatedAt = event.createdAt * 1000L,
                    latestEventId = event.id
                )
            )
            dao.upsertNsiteVersion(
                NsiteVersionEntity(
                    eventId = event.id,
                    coordinate = "${NostrKinds.NSITE_ROOT}:$pk:root",
                    kind = NostrKinds.NSITE_ROOT,
                    authorPubkey = pk,
                    authorNpub = npub,
                    dTag = "root",
                    title = cleanTitle,
                    description = cleanDesc,
                    indexSha256 = sha.lowercase(),
                    fileCount = authorMap.size.coerceAtLeast(1),
                    blossomServersCsv = server,
                    manifestPathsJson = pathsJson,
                    rawEventJson = event.toJsonString(),
                    signatureVerified = event.verifySignature(),
                    createdAtSec = event.createdAt
                )
            )
            ensureAuthorMetadataRequested(pk)
        }
    }

    private suspend fun handleIncomingNip51List(event: NostrEvent) {
        val validKinds = setOf(
            NostrKinds.NSITE_NAMED,
            NostrKinds.NSITE_ROOT
        )
        val listSlug = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)?.ifBlank { null }
            ?: "bookmarks"
        val listTitle = event.tags.firstOrNull { it.size >= 2 && (it[0] == "title" || it[0] == "name") }
            ?.get(1)?.ifBlank { null } ?: listSlug
        val localAcc = dao.getAccountByPubkey(event.pubkey)
        val curatorNpub = localAcc?.npub
            ?: runCatching { NostrCrypto.encodeNpub(event.pubkey) }.getOrDefault(event.pubkey.take(16))

        val coordinates = event.tags
            .filter { it.size >= 2 && it[0] == "a" }
            .map { it[1].trim() }
            .filter { coord ->
                val kindPrefix = coord.substringBefore(':').toIntOrNull()
                kindPrefix in validKinds
            }
            .distinct()

        if (coordinates.isEmpty()) return

        val entities = coordinates.map { coord ->
            CuratedBookmarkEntity(
                coordinate = coord,
                curatorPubkey = event.pubkey,
                curatorNpub = curatorNpub,
                listSlug = listSlug,
                listTitle = listTitle,
                isLocalCurator = localAcc != null,
                createdAt = event.createdAt * 1000L
            )
        }
        dao.upsertCuratedBookmarks(entities)
        ensureAuthorMetadataRequested(event.pubkey)

        val missingCoords = coordinates.filter { dao.getAppletById(it) == null }
        if (missingCoords.isNotEmpty()) {
            relayPool.requestManifestsByCoordinates(missingCoords)
        }
    }

    private suspend fun handleIncomingMetadata(event: NostrEvent) {
        val pk = event.pubkey.trim().lowercase()
        if (pk.length != 64) return

        val existingProfile = dao.getPublisherProfile(pk)
        val parsedProfile = parseKind0Metadata(event, existingProfile)
        if (parsedProfile != null) {
            dao.upsertPublisherProfile(parsedProfile)
            if (parsedProfile.hasResolvedIdentity) {
                dao.updateCachedNotesAuthorProfile(
                    pubkeyHex = pk,
                    authorName = parsedProfile.bestName,
                    authorAvatar = parsedProfile.pictureUrl
                )
            }
        }

        val localAcc = dao.getAccountByPubkey(event.pubkey) ?: dao.getAccountByPubkey(pk)
        if (localAcc != null && parsedProfile != null) {
            val name = parsedProfile.displayName.ifBlank { parsedProfile.username }
            if (name.isNotBlank() || parsedProfile.about.isNotBlank() || parsedProfile.pictureUrl.isNotBlank()) {
                dao.upsertAccount(
                    localAcc.copy(
                        displayName = name.ifBlank { localAcc.displayName },
                        bio = parsedProfile.about.ifBlank { localAcc.bio },
                        remoteAvatarUrl = parsedProfile.pictureUrl.ifBlank { localAcc.remoteAvatarUrl }
                    )
                )
            }
        }
    }

    private suspend fun handleIncomingTextNote(event: NostrEvent) {
        if (event.content.isBlank()) return
        val pk = event.pubkey.trim().lowercase()
        val localAcc = dao.getAccountByPubkey(event.pubkey) ?: dao.getAccountByPubkey(pk)
        val cachedPublisher = dao.getPublisherProfile(pk)
        val npub = runCatching { NostrCrypto.encodeNpub(pk) }.getOrDefault(pk.take(16))
        val resolvedName = localAcc?.displayName?.ifBlank { null }
            ?: cachedPublisher?.takeIf { it.hasResolvedIdentity }?.bestName
            ?: "npub1…${npub.takeLast(6)}"
        val resolvedAvatar = localAcc?.localAvatarPath?.ifBlank { localAcc.remoteAvatarUrl }
            ?.ifBlank { null }
            ?: cachedPublisher?.pictureUrl.orEmpty()
        val note = NoteEntity(
            eventId = event.id,
            pubkeyHex = pk,
            npub = npub,
            authorName = resolvedName,
            authorAvatar = resolvedAvatar,
            content = event.content,
            createdAtSec = event.createdAt,
            sigHex = event.sig,
            acceptedRelaysCount = 1,
            rawEventJson = event.toJsonString(),
            isLocalUser = localAcc != null
        )
        dao.upsertNote(note)
        if (localAcc == null && cachedPublisher == null) {
            ensureAuthorMetadataRequested(pk)
        }
    }

    private suspend fun handleIncomingManifest(event: NostrEvent) {
        if (event.kind != NostrKinds.NSITE_NAMED && event.kind != NostrKinds.NSITE_ROOT) return
        val pk = event.pubkey.trim().lowercase()
        val dTag = when (event.kind) {
            NostrKinds.NSITE_ROOT -> "root"
            else -> event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)?.ifBlank { null } ?: "root"
        }
        val appletId = "${event.kind}:$pk:$dTag"
        val existing = dao.getAppletById(appletId) ?: dao.getAppletById("${event.kind}:${event.pubkey}:$dTag")
        val localAccount = dao.getAccountByPubkey(event.pubkey) ?: dao.getAccountByPubkey(pk)
        val isFeaturedCreator = pk.equals(NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX, ignoreCase = true)
        val parsed = parseManifestEventToEntity(
            event = event.copy(pubkey = pk),
            existing = existing,
            isLocalAuthor = localAccount != null || isFeaturedCreator
        ) ?: return

        // Record signed version history even if an older or newer version is also seen
        val manifestObj = runCatching { JSONObject(parsed.manifestPathsJson) }.getOrDefault(JSONObject())
        var fileCount = 0
        val keys = manifestObj.keys()
        while (keys.hasNext()) {
            if (!keys.next().startsWith("__")) fileCount++
        }
        val serversCsv = event.tags
            .filter { it.size >= 2 && (it[0] == "server" || it[0] == "blossom") }
            .map { it[1].trim().trimEnd('/') }
            .ifEmpty { listOf(parsed.blossomServer) }
            .distinct()
            .joinToString(",")

        dao.upsertNsiteVersion(
            NsiteVersionEntity(
                eventId = event.id,
                coordinate = appletId,
                kind = event.kind,
                authorPubkey = pk,
                authorNpub = parsed.authorNpub,
                dTag = dTag,
                title = parsed.title,
                description = parsed.description,
                indexSha256 = parsed.indexSha256,
                fileCount = fileCount.coerceAtLeast(1),
                blossomServersCsv = serversCsv,
                manifestPathsJson = parsed.manifestPathsJson,
                rawEventJson = event.toJsonString(),
                signatureVerified = event.verifySignature(),
                createdAtSec = event.createdAt
            )
        )

        // Only replace primary catalog row if this manifest is at least as recent as what's stored
        if (existing != null && (event.createdAt * 1000L) < existing.updatedAt) {
            return
        }

        val finalEntity = if (isFeaturedCreator && existing == null && prefsRepo.markAppletAutoPinnedOnce(parsed.appletId)) {
            parsed.copy(isInstalled = true, pinOrder = 1, latestEventId = event.id)
        } else {
            parsed.copy(latestEventId = event.id)
        }
        dao.upsertApplet(finalEntity)
        ensureAuthorMetadataRequested(pk)
    }

    // --- Manual NIP-5A Address Resolution, Download & Verification ---

    /**
     * Resolves a manually entered NIP-5A Nsite address (`https://...nsite.lol/`, `naddr1...`,
     * `npub1...`, `35128:pk:slug`, or `user@domain`), queries adaptive relays + hint relays
     * for its signed manifest, optionally prefetches & SHA-256 verifies its entry HTML + assets,
     * and pins/returns the ready-to-launch `InstalledAppletEntity`.
     */
    suspend fun resolveAndInstallManualNsiteAddress(
        rawAddress: String,
        pinToLibrary: Boolean = true
    ): InstalledAppletEntity = withContext(Dispatchers.IO) {
        val target = NsiteAddressResolver.resolveAddress(rawAddress)
            ?: throw IllegalArgumentException(
                "Unrecognized Nsite address. Enter an nsite.lol URL, naddr1…, npub1…, 35128:pubkey:slug, or NIP-05."
            )

        when (target) {
            is NsiteAddressResolver.ResolvedNsiteTarget.Nip5aCoordinate -> {
                val pk = target.authorPubkeyHex.lowercase()
                val filter = JSONObject().apply {
                    put("authors", JSONArray().put(pk))
                    if (target.kind == NostrKinds.NSITE_ROOT) {
                        put("kinds", JSONArray().put(NostrKinds.NSITE_ROOT).put(NostrKinds.NSITE_LEGACY_FILE))
                    } else {
                        put("kinds", JSONArray().put(NostrKinds.NSITE_NAMED))
                        put("#d", JSONArray().put(target.dTag))
                    }
                    put("limit", 40)
                }
                val candidateRelays = networkEngine.selectRelaysForOperation(
                    operation = NetworkOperationType.RESOLVE_MANIFEST,
                    extraCandidateRelays = target.relayHints
                )
                val events = relayPool.queryEventsOnce(
                    filterJson = filter,
                    timeoutMs = 3500L,
                    specificRelays = candidateRelays.take(8)
                )
                // Also fetch author Blossom servers (Kind 10063) concurrently if available
                fetchAuthorBlossomServers(pk)

                events.sortedBy { it.createdAt }.forEach { ev ->
                    ingestNostrEvent(ev)
                }

                val found = dao.getAppletById(target.coordinate)
                    ?: (if (target.kind == NostrKinds.NSITE_ROOT) {
                        dao.getAppletById("${NostrKinds.NSITE_LEGACY_FILE}:$pk:root")
                    } else null)
                    ?: error("No signed NIP-5A manifest found on relays for ${target.coordinate}")

                val verifiedEntity = prefetchAndVerifySiteAssets(found)
                val saved = if (pinToLibrary && !verifiedEntity.isInstalled) {
                    verifiedEntity.copy(isInstalled = true, pinOrder = 0)
                } else {
                    verifiedEntity
                }
                dao.upsertApplet(saved)
                saved
            }

            is NsiteAddressResolver.ResolvedNsiteTarget.AuthorAllSites -> {
                val pk = target.authorPubkeyHex.lowercase()
                relayPool.trackAndQueryAuthor(pk, target.relayHints)
                val filter = JSONObject().apply {
                    put("authors", JSONArray().put(pk))
                    put(
                        "kinds",
                        JSONArray()
                            .put(NostrKinds.NSITE_NAMED)
                            .put(NostrKinds.NSITE_ROOT)
                            .put(NostrKinds.NSITE_LEGACY_FILE)
                    )
                    put("limit", 50)
                }
                val candidateRelays = networkEngine.selectRelaysForOperation(
                    operation = NetworkOperationType.RESOLVE_MANIFEST,
                    extraCandidateRelays = target.relayHints
                )
                val events = relayPool.queryEventsOnce(
                    filterJson = filter,
                    timeoutMs = 3500L,
                    specificRelays = candidateRelays.take(8)
                )
                fetchAuthorBlossomServers(pk)
                events.sortedBy { it.createdAt }.forEach { ev -> ingestNostrEvent(ev) }

                val authorSites = dao.getAllCatalogAppletsList()
                    .filter { it.authorPubkey.equals(pk, ignoreCase = true) && it.isNsite }
                    .sortedByDescending { it.updatedAt }
                val primarySite = authorSites.firstOrNull { it.kind == NostrKinds.NSITE_ROOT }
                    ?: authorSites.firstOrNull()
                    ?: error("No NIP-5A Nsites found on relays for ${NostrCrypto.encodeNpub(pk).take(16)}…")

                val verified = prefetchAndVerifySiteAssets(primarySite)
                val saved = if (pinToLibrary && !verified.isInstalled) {
                    verified.copy(isInstalled = true, pinOrder = 0)
                } else {
                    verified
                }
                dao.upsertApplet(saved)
                saved
            }

            is NsiteAddressResolver.ResolvedNsiteTarget.DirectHttpsSite -> {
                val urlHash = NostrCrypto.sha256Hex(target.url).take(16)
                val hostLabel = runCatching { java.net.URI(target.url).host }.getOrNull().orEmpty().ifBlank { target.url }
                val pathsJson = JSONObject().put("__direct_url", target.url).toString()
                val entity = InstalledAppletEntity(
                    appletId = "${NostrKinds.NSITE_NAMED}:${"0".repeat(64)}:$urlHash",
                    kind = NostrKinds.NSITE_NAMED,
                    authorPubkey = "0".repeat(64),
                    authorNpub = hostLabel,
                    dTag = urlHash.take(12),
                    title = hostLabel,
                    description = "Direct web address: ${target.url}",
                    iconGlyph = "🌐",
                    accentHex = "#FF1493",
                    isNsite = true,
                    enableNip07 = false,
                    blossomServer = target.url,
                    manifestPathsJson = pathsJson,
                    isInstalled = pinToLibrary,
                    pinOrder = 0
                )
                dao.upsertApplet(entity)
                entity
            }
        }
    }

    /**
     * Downloads and SHA-256 verifies all files listed in an Nsite's manifest so offline caching
     * and integrity status (`cachedComplete`) are accurate.
     */
    suspend fun prefetchAndVerifySiteAssets(applet: InstalledAppletEntity): InstalledAppletEntity =
        withContext(Dispatchers.IO) {
            val manifestObj = runCatching {
                if (applet.manifestPathsJson.isNotBlank()) JSONObject(applet.manifestPathsJson) else JSONObject()
            }.getOrDefault(JSONObject())
            val servers = candidateBlossomServersFor(applet, manifestObj)

            val pathHashes = LinkedHashMap<String, String>()
            val keys = manifestObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!k.startsWith("__")) {
                    val sha = manifestObj.optString(k).trim().lowercase()
                    if (sha.length == 64) pathHashes[k] = sha
                }
            }
            if (pathHashes.isEmpty() && applet.indexSha256.length == 64) {
                pathHashes["/index.html"] = applet.indexSha256.lowercase()
            }
            if (pathHashes.isEmpty()) return@withContext applet

            var allVerified = true
            for ((path, sha) in pathHashes) {
                val ext = path.substringAfterLast('/', "").substringAfterLast('.', "")
                val outcome = networkEngine.fetchBlobAdaptively(
                    primaryServer = applet.blossomServer,
                    manifestAndAuthorServers = servers,
                    sha256 = sha,
                    extensionHint = ext
                )
                if (outcome !is BlossomClient.BlobFetchOutcome.Verified) {
                    allVerified = false
                }
            }
            val updated = applet.copy(cachedComplete = allVerified)
            dao.upsertApplet(updated)
            updated
        }

    // --- First-Class Nsite Forking ---

    suspend fun forkSiteIntoProject(
        sourceSite: InstalledAppletEntity,
        specificVersion: NsiteVersionEntity? = null
    ): ForkImportOutcome = withContext(Dispatchers.IO) {
        val active = dao.getActiveAccount()
        val extraServers = if (sourceSite.authorPubkey.length == 64) {
            fetchAuthorBlossomServers(sourceSite.authorPubkey)
        } else {
            emptyList()
        }
        val legacyMap = if (sourceSite.authorPubkey.length == 64) {
            fetchLegacyNsitePathMap(sourceSite.authorPubkey)
        } else {
            emptyMap()
        }
        projectRepository.forkPublishedNsite(
            ownerPubkeyHex = active?.pubkeyHex.orEmpty(),
            sourceSite = sourceSite,
            specificVersion = specificVersion,
            extraAuthorServers = extraServers,
            fallbackLegacyPathMap = legacyMap
        )
    }

    suspend fun forkByManualAddress(rawAddress: String): ForkImportOutcome = withContext(Dispatchers.IO) {
        val resolvedSite = resolveAndInstallManualNsiteAddress(rawAddress, pinToLibrary = false)
        forkSiteIntoProject(resolvedSite)
    }

    // --- Account, Login & Signer Management (Local Keystore + NIP-55 + NIP-46 + Read-Only npub) ---

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
            signerType = SignerType.LOCAL_KEYSTORE.name,
            isActive = true
        )
        dao.clearActiveAccounts()
        dao.upsertAccount(entity)
        entity
    }

    suspend fun importAccountFromKey(rawKeyInput: String, alias: String): VaultAccountEntity =
        withContext(Dispatchers.IO) {
            val trimmed = rawKeyInput.trim()
            // Support read-only npub1... login directly if user enters an npub!
            if (trimmed.startsWith("npub1", ignoreCase = true) || trimmed.startsWith("nprofile1", ignoreCase = true)) {
                return@withContext addReadOnlyNpubAccount(trimmed, alias)
            }
            val kp = NostrCrypto.importPrivateKey(trimmed)
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
                signerType = SignerType.LOCAL_KEYSTORE.name,
                isActive = true
            )
            dao.clearActiveAccounts()
            dao.upsertAccount(entity)
            relayPool.requestAuthorMetadata(kp.pubKeyHex)
            entity
        }

    suspend fun addReadOnlyNpubAccount(rawNpubOrHex: String, alias: String): VaultAccountEntity =
        withContext(Dispatchers.IO) {
            val pubHex = NostrCrypto.resolvePubkeyHex(rawNpubOrHex)
                ?: throw IllegalArgumentException("Enter a valid npub1… or 64-character hex public key")
            val npub = NostrCrypto.encodeNpub(pubHex)
            val existing = dao.getAccountByPubkey(pubHex)
            val entity = VaultAccountEntity(
                pubkeyHex = pubHex,
                npub = npub,
                encryptedPrivKeyBase64 = "",
                ivBase64 = "",
                displayName = alias.trim().ifBlank { existing?.displayName ?: "Watch ${npub.takeLast(5)}" },
                bio = existing?.bio.orEmpty(),
                remoteAvatarUrl = existing?.remoteAvatarUrl.orEmpty(),
                signerType = SignerType.NPUB_READONLY.name,
                isActive = true
            )
            dao.clearActiveAccounts()
            dao.upsertAccount(entity)
            relayPool.requestAuthorMetadata(pubHex)
            entity
        }

    suspend fun connectNip55SignerAccount(
        preferredPackage: String = "",
        alias: String = ""
    ): VaultAccountEntity = withContext(Dispatchers.IO) {
        val res = signerManager.requestNip55Login(preferredPackage)
        val existing = dao.getAccountByPubkey(res.pubkeyHex)
        val entity = VaultAccountEntity(
            pubkeyHex = res.pubkeyHex,
            npub = res.npub,
            encryptedPrivKeyBase64 = "",
            ivBase64 = "",
            displayName = alias.trim().ifBlank { existing?.displayName ?: "NIP-55 ${res.npub.takeLast(5)}" },
            bio = existing?.bio.orEmpty(),
            remoteAvatarUrl = existing?.remoteAvatarUrl.orEmpty(),
            signerType = SignerType.NIP55_ANDROID.name,
            signerPackage = res.signerPackage,
            isActive = true
        )
        dao.clearActiveAccounts()
        dao.upsertAccount(entity)
        relayPool.requestAuthorMetadata(res.pubkeyHex)
        entity
    }

    suspend fun connectNip46BunkerAccount(
        rawBunkerUri: String,
        alias: String = ""
    ): VaultAccountEntity = withContext(Dispatchers.IO) {
        val (userPubkeyHex, clientKp, _) = signerManager.connectNip46Bunker(rawBunkerUri)
        val encClient = keyVault.encryptPrivateKeyHex(clientKp.privKeyHex)
        val npub = NostrCrypto.encodeNpub(userPubkeyHex)
        val existing = dao.getAccountByPubkey(userPubkeyHex)
        val entity = VaultAccountEntity(
            pubkeyHex = userPubkeyHex,
            npub = npub,
            encryptedPrivKeyBase64 = encClient.ciphertextBase64,
            ivBase64 = encClient.ivBase64,
            displayName = alias.trim().ifBlank { existing?.displayName ?: "Bunker ${npub.takeLast(5)}" },
            bio = existing?.bio.orEmpty(),
            remoteAvatarUrl = existing?.remoteAvatarUrl.orEmpty(),
            signerType = SignerType.NIP46_BUNKER.name,
            bunkerUri = rawBunkerUri.trim(),
            bunkerClientPubkeyHex = clientKp.pubKeyHex,
            isActive = true
        )
        dao.clearActiveAccounts()
        dao.upsertAccount(entity)
        relayPool.requestAuthorMetadata(userPubkeyHex)
        entity
    }

    suspend fun switchAccount(pubkeyHex: String) = withContext(Dispatchers.IO) {
        dao.switchActiveAccount(pubkeyHex)
        val acc = dao.getAccountByPubkey(pubkeyHex)
        if (acc?.resolvedSignerType == SignerType.NIP46_BUNKER && acc.bunkerUri.isNotBlank()) {
            ExternalSignerManager.parseBunkerUri(acc.bunkerUri)?.let { bunker ->
                if (acc.bunkerClientPubkeyHex.length == 64) {
                    relayPool.ensureNip46Subscription(bunker.relays, acc.bunkerClientPubkeyHex)
                }
            }
        }
        relayPool.requestAuthorMetadata(pubkeyHex)
    }

    suspend fun exportActiveNsec(): String? = withContext(Dispatchers.IO) {
        val active = dao.getActiveAccount() ?: return@withContext null
        if (active.resolvedSignerType != SignerType.LOCAL_KEYSTORE || active.encryptedPrivKeyBase64.isBlank()) {
            return@withContext null
        }
        val privHex = keyVault.decryptPrivateKeyHex(active.encryptedPrivKeyBase64, active.ivBase64)
        NostrCrypto.encodeNsec(privHex)
    }

    suspend fun getActiveSigningAccount(): VaultAccountEntity? = withContext(Dispatchers.IO) {
        dao.getActiveAccount()?.takeIf { it.canSign }
    }

    suspend fun getActivePrivateKeyHex(): Pair<VaultAccountEntity, String>? = withContext(Dispatchers.IO) {
        val active = dao.getActiveAccount() ?: return@withContext null
        if (active.resolvedSignerType != SignerType.LOCAL_KEYSTORE || active.encryptedPrivKeyBase64.isBlank()) {
            return@withContext null
        }
        val privHex = keyVault.decryptPrivateKeyHex(active.encryptedPrivKeyBase64, active.ivBase64)
        active to privHex
    }

    // --- User Profile System (Kind 0) ---

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
        val active = getActiveSigningAccount()
            ?: error("Active identity cannot sign events. Switch to a signing identity.")
        val prefs = prefsRepo.prefsFlow.first()

        var pictureUrl = active.remoteAvatarUrl
        if (uploadAvatarToBlossom && localAvatarPath.isNotBlank()) {
            val file = File(localAvatarPath)
            if (file.exists()) {
                val uploadRes = networkEngine.uploadBlobWithRedundancy(
                    primaryPreferred = prefs.primaryBlossomServer,
                    bytes = file.readBytes(),
                    mimeType = "image/jpeg",
                    extension = "jpg",
                    pubkeyHex = active.pubkeyHex,
                    signAuthEvent = { unsigned -> signerManager.signEventForAccount(active, unsigned) }
                )
                if (uploadRes.uploadedLive) {
                    pictureUrl = uploadRes.url
                }
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

        val signedKind0 = signerManager.signEventForAccount(
            account = updatedAccount,
            unsigned = UnsignedNostrEvent(
                pubkey = updatedAccount.pubkeyHex,
                kind = NostrKinds.METADATA,
                content = metadataJson,
                tags = listOf(listOf("client", "SovereignShell"))
            )
        )
        recordAudit(
            account = updatedAccount,
            appletId = "shell:profile",
            appletTitle = "SovereignShell Profile",
            protocolLabel = updatedAccount.resolvedSignerType.label,
            method = "signEvent",
            eventKind = NostrKinds.METADATA,
            approved = true,
            summary = "Signed Kind 0 profile metadata (${updatedAccount.displayName})"
        )
        networkEngine.publishEventAdaptively(
            event = signedKind0,
            verifyRetrieval = true,
            signAuthIfChallenged = { authEv -> signerManager.signEventForAccount(updatedAccount, authEv) }
        )
    }

    // --- Adaptive Relay + Blossom Server Configuration ---

    suspend fun addRelayUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        val normalized = RelayPoolClient.normalizeRelayUrl(rawUrl)
        require(normalized.startsWith("wss://") || normalized.startsWith("ws://")) {
            "Relay URL must start with wss:// or ws://"
        }
        require(normalized.length > 7) {
            "Please enter a valid relay URL (e.g. wss://relay.damus.io)"
        }
        dao.upsertRelay(RelayEntity(url = normalized))
        relayPool.connectRelay(normalized)
        repoScope.launch { networkEngine.probeRelayCapabilities(normalized) }
        normalized
    }

    suspend fun removeRelayUrl(url: String) = withContext(Dispatchers.IO) {
        val normalized = RelayPoolClient.normalizeRelayUrl(url)
        dao.deleteRelay(normalized)
        relayPool.disconnectRelay(normalized, removeFromMap = true)
    }

    fun reconnectRelay(url: String) {
        relayPool.connectRelay(url)
        repoScope.launch { networkEngine.probeRelayCapabilities(url) }
    }

    suspend fun addBlossomServerUrl(rawUrl: String, asPrimary: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val clean = BlossomClient.normalizeServerUrl(rawUrl)
            require(clean.startsWith("https://") || clean.startsWith("http://")) {
                "Blossom URL must start with https:// or http://"
            }
            dao.upsertBlossomServer(
                ConfiguredBlossomServerEntity(
                    serverUrl = clean,
                    enabled = true,
                    isBackupMirror = !asPrimary
                )
            )
            if (asPrimary) {
                prefsRepo.setBlossomServer(clean)
            }
            repoScope.launch { networkEngine.probeBlossomCapabilities(clean) }
            clean
        }

    suspend fun setPrimaryBlossomServer(rawUrl: String) = withContext(Dispatchers.IO) {
        val clean = addBlossomServerUrl(rawUrl, asPrimary = true)
        prefsRepo.setBlossomServer(clean)
    }

    suspend fun removeBlossomServerUrl(rawUrl: String) = withContext(Dispatchers.IO) {
        val clean = BlossomClient.normalizeServerUrl(rawUrl)
        dao.deleteBlossomServer(clean)
    }

    suspend fun probeAllNetworkHealth() = withContext(Dispatchers.IO) {
        networkEngine.probeAllConfiguredEndpoints()
    }

    suspend fun publishRelayListNip65(): RelayPublishSummary = withContext(Dispatchers.IO) {
        val active = getActiveSigningAccount() ?: error("No active signing identity")
        val relays = dao.getRelaysList()
        val tags = relays.map { listOf("r", it.url) }
        val ev = signerManager.signEventForAccount(
            account = active,
            unsigned = UnsignedNostrEvent(
                pubkey = active.pubkeyHex,
                kind = NostrKinds.RELAY_LIST,
                content = "",
                tags = tags
            )
        )
        recordAudit(
            account = active,
            appletId = "shell:relays",
            appletTitle = "Relay Manager",
            protocolLabel = active.resolvedSignerType.label,
            method = "signEvent",
            eventKind = NostrKinds.RELAY_LIST,
            approved = true,
            summary = "Published NIP-65 Kind 10002 relay list (${relays.size} relays)"
        )
        networkEngine.publishEventAdaptively(
            event = ev,
            verifyRetrieval = true,
            signAuthIfChallenged = { authEv -> signerManager.signEventForAccount(active, authEv) }
        )
    }

    suspend fun publishBlossomServersKind10063(): RelayPublishSummary = withContext(Dispatchers.IO) {
        val active = getActiveSigningAccount() ?: error("No active signing identity")
        val prefs = prefsRepo.prefsFlow.first()
        val primary = BlossomClient.normalizeServerUrl(prefs.primaryBlossomServer)
        val configured = dao.getBlossomServersList().filter { it.enabled }.map { it.serverUrl }
        val ordered = (listOfNotNull(primary.takeIf { it.isNotBlank() }) + configured).distinct()
        require(ordered.isNotEmpty()) { "Configure at least one Blossom server first" }

        val tags = ordered.map { listOf("server", it) }
        val ev = signerManager.signEventForAccount(
            account = active,
            unsigned = UnsignedNostrEvent(
                pubkey = active.pubkeyHex,
                kind = NostrKinds.USER_BLOSSOM_SERVERS,
                content = "",
                tags = tags
            )
        )
        recordAudit(
            account = active,
            appletId = "shell:blossom",
            appletTitle = "Blossom Server List",
            protocolLabel = active.resolvedSignerType.label,
            method = "signEvent",
            eventKind = NostrKinds.USER_BLOSSOM_SERVERS,
            approved = true,
            summary = "Published Kind 10063 Blossom list (${ordered.size} servers)"
        )
        networkEngine.publishEventAdaptively(
            event = ev,
            verifyRetrieval = true,
            signAuthIfChallenged = { authEv -> signerManager.signEventForAccount(active, authEv) }
        )
    }

    // --- Create, Sign & Publish Nostr Notes (NIP-01 Kind 1) ---

    suspend fun createSignAndPublishNote(
        content: String,
        hashtags: List<String> = emptyList()
    ): RelayPublishSummary = withContext(Dispatchers.IO) {
        val trimmed = content.trim()
        require(trimmed.isNotBlank()) { "Note content cannot be empty" }
        val active = getActiveSigningAccount() ?: error("Active identity cannot sign notes")

        val tags = buildList {
            hashtags.map { it.trim().removePrefix("#") }
                .filter { it.isNotBlank() }
                .forEach { add(listOf("t", it)) }
            add(listOf("client", "SovereignShell"))
        }

        val signedNote = signerManager.signEventForAccount(
            account = active,
            unsigned = UnsignedNostrEvent(
                pubkey = active.pubkeyHex,
                kind = NostrKinds.TEXT_NOTE,
                content = trimmed,
                tags = tags
            )
        )
        recordAudit(
            account = active,
            appletId = "shell:notes",
            appletTitle = "Shell Note Composer",
            protocolLabel = active.resolvedSignerType.label,
            method = "signEvent",
            eventKind = NostrKinds.TEXT_NOTE,
            approved = true,
            summary = "Signed Kind 1 note: ${trimmed.take(42)}"
        )

        val summary = networkEngine.publishEventAdaptively(
            event = signedNote,
            verifyRetrieval = false,
            signAuthIfChallenged = { authEv -> signerManager.signEventForAccount(active, authEv) }
        )
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

    // --- Library Pinning, NIP-51 Curation & Runtime Asset Resolution ---

    suspend fun setAppletInstalled(appletId: String, installed: Boolean) =
        dao.setAppletInstalled(appletId, installed)

    suspend fun setAppletNip07(appletId: String, enabled: Boolean) =
        dao.setAppletNip07(appletId, enabled)

    suspend fun toggleLocalNip51Bookmark(applet: InstalledAppletEntity): Boolean =
        withContext(Dispatchers.IO) {
            val active = dao.getActiveAccount() ?: error("No active Nostr account")
            val coordinate = CatalogStacker.coordinateFor(applet)
            val existing = dao.getBookmark(coordinate, active.pubkeyHex)
            if (existing != null) {
                dao.deleteCuratedBookmark(coordinate, active.pubkeyHex)
                false
            } else {
                dao.upsertCuratedBookmark(
                    CuratedBookmarkEntity(
                        coordinate = coordinate,
                        curatorPubkey = active.pubkeyHex,
                        curatorNpub = active.npub,
                        listSlug = "sovereign-nsites",
                        listTitle = "${active.displayName.ifBlank { "Sovereign" }} Curated Nsites",
                        isLocalCurator = true
                    )
                )
                true
            }
        }

    suspend fun publishNip51CuratedList(): RelayPublishSummary = withContext(Dispatchers.IO) {
        val active = getActiveSigningAccount() ?: error("No active signing identity")
        val bookmarks = dao.getBookmarksByCurator(active.pubkeyHex)
        require(bookmarks.isNotEmpty()) { "Bookmark at least one Nsite to publish a NIP-51 list" }

        val tags = buildList {
            add(listOf("d", "sovereign-nsites"))
            add(listOf("title", "${active.displayName.ifBlank { "Sovereign" }} Curated Nsites"))
            add(listOf("description", "Curated NIP-5A Nsites from SovereignShell"))
            bookmarks.forEach { bm -> add(listOf("a", bm.coordinate)) }
            add(listOf("client", "SovereignShell"))
        }
        val signedList = signerManager.signEventForAccount(
            account = active,
            unsigned = UnsignedNostrEvent(
                pubkey = active.pubkeyHex,
                kind = NostrKinds.NIP51_BOOKMARK_SET,
                content = "",
                tags = tags
            )
        )
        recordAudit(
            account = active,
            appletId = "shell:gallery-nip51",
            appletTitle = "Curated Nsites (NIP-51)",
            protocolLabel = active.resolvedSignerType.label,
            method = "signEvent",
            eventKind = NostrKinds.NIP51_BOOKMARK_SET,
            approved = true,
            summary = "Published NIP-51 Kind 30003 curated Nsite list (${bookmarks.size} sites)"
        )
        networkEngine.publishEventAdaptively(
            event = signedList,
            verifyRetrieval = true,
            signAuthIfChallenged = { authEv -> signerManager.signEventForAccount(active, authEv) }
        )
    }

    suspend fun fetchAuthorBlossomServers(authorPubkey: String): List<String> = withContext(Dispatchers.IO) {
        val pk = authorPubkey.trim().lowercase()
        if (pk.length != 64) return@withContext emptyList()
        authorBlossomCache[pk]?.let { return@withContext it }

        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(NostrKinds.USER_BLOSSOM_SERVERS))
            put("authors", JSONArray().put(pk))
            put("limit", 1)
        }
        val events = runCatching { relayPool.queryEventsOnce(filter, timeoutMs = 2000L) }.getOrDefault(emptyList())
        val discovered = events.firstOrNull()
            ?.tags
            ?.filter {
                it.size >= 2 &&
                    (it[0] == "server" || it[0] == "r") &&
                    (it[1].startsWith("https://", ignoreCase = true) || it[1].startsWith("http://", ignoreCase = true))
            }
            ?.map { it[1].trim().trimEnd('/') }
            ?.distinct()
            .orEmpty()

        authorBlossomCache[pk] = discovered
        discovered
    }

    private suspend fun fetchLegacyNsitePathMap(authorPubkey: String): Map<String, String> = withContext(Dispatchers.IO) {
        val pk = authorPubkey.trim().lowercase()
        if (pk.length != 64) return@withContext emptyMap()
        val cached = legacyNsitePathCache[pk]
        if (cached != null && cached.size > 1) return@withContext cached

        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(NostrKinds.NSITE_LEGACY_FILE))
            put("authors", JSONArray().put(pk))
            put("limit", 120)
        }
        val events = runCatching { relayPool.queryEventsOnce(filter, timeoutMs = 2400L) }.getOrDefault(emptyList())
        val map = ConcurrentHashMap<String, String>()
        cached?.let { map.putAll(it) }
        events.sortedByDescending { it.createdAt }.forEach { ev ->
            val path = ev.tags.firstOrNull { it.size >= 2 && (it[0] == "d" || it[0] == "path") }?.get(1)?.trim().orEmpty()
            val sha = ev.tags.firstOrNull { it.size >= 2 && (it[0] == "x" || it[0] == "sha256") }?.get(1)?.trim().orEmpty()
            if (path.isNotBlank() && sha.length == 64) {
                val norm = "/" + path.trimStart('/')
                map.putIfAbsent(norm, sha.lowercase())
            }
        }
        legacyNsitePathCache[pk] = map
        map
    }

    private suspend fun candidateBlossomServersFor(applet: InstalledAppletEntity, manifestObj: JSONObject?): List<String> {
        val fromManifestArr = buildList {
            if (applet.blossomServer.isNotBlank()) add(applet.blossomServer)
            val arr = manifestObj?.optJSONArray("__servers")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotBlank()) add(s)
                }
            }
        }
        val authorServers = if (applet.inlineHtml.isBlank() && applet.authorPubkey.length == 64) {
            fetchAuthorBlossomServers(applet.authorPubkey)
        } else {
            emptyList()
        }
        return (fromManifestArr + authorServers).distinct()
    }

    suspend fun resolveAppletDocument(applet: InstalledAppletEntity): String = withContext(Dispatchers.IO) {
        val manifestObj = runCatching {
            if (applet.manifestPathsJson.isNotBlank()) JSONObject(applet.manifestPathsJson) else null
        }.getOrNull()
        val directUrl = manifestObj?.optString("__direct_url").orEmpty()
        val servers = candidateBlossomServersFor(applet, manifestObj)

        var resolvedSha = applet.indexSha256
        if (resolvedSha.isBlank() && applet.inlineHtml.isBlank() && applet.authorPubkey.length == 64) {
            val legacyMap = fetchLegacyNsitePathMap(applet.authorPubkey)
            resolvedSha = legacyMap["/index.html"] ?: legacyMap["index.html"] ?: ""
        }

        val fetchedHtml = when {
            applet.inlineHtml.isNotBlank() -> applet.inlineHtml
            resolvedSha.isNotBlank() || directUrl.isNotBlank() -> {
                val outcome = networkEngine.fetchBlobAdaptively(
                    primaryServer = applet.blossomServer,
                    manifestAndAuthorServers = servers,
                    sha256 = resolvedSha,
                    extensionHint = "html",
                    directUrl = directUrl
                )
                when (outcome) {
                    is BlossomClient.BlobFetchOutcome.Verified -> String(outcome.bytes, Charsets.UTF_8)
                    is BlossomClient.BlobFetchOutcome.HashMismatch -> {
                        return@withContext buildUnreachableBundleDiagnosticHtml(
                            applet = applet,
                            sha256 = resolvedSha,
                            directUrl = directUrl,
                            triedServers = servers,
                            integrityReason = "SHA-256 integrity mismatch from ${outcome.offendingServer} (expected ${resolvedSha.take(12)}…, received ${outcome.actualSha256.take(12)}…)"
                        )
                    }
                    is BlossomClient.BlobFetchOutcome.Missing -> null
                }
            }
            else -> null
        }

        if (fetchedHtml == null) {
            return@withContext buildUnreachableBundleDiagnosticHtml(applet, resolvedSha, directUrl, servers)
        }

        if (fetchedHtml.contains("<html", ignoreCase = true) || fetchedHtml.contains("<!doctype", ignoreCase = true)) {
            fetchedHtml
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
  $fetchedHtml
  <script>${applet.inlineJs}</script>
</body>
</html>
            """.trimIndent()
        }
    }

    suspend fun resolveAppletAsset(
        applet: InstalledAppletEntity,
        rawPath: String,
        isMainFrame: Boolean,
        transformRootHtml: (String) -> String = { it }
    ): ResolvedWebAsset? = withContext(Dispatchers.IO) {
        val stripped = rawPath.substringBefore('?').substringBefore('#').trim()
        val decoded = runCatching { URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
        val cleanPath = "/" + decoded.trimStart('/')

        // 1. Root HTML entry point
        if (cleanPath == "/" || cleanPath.equals("/index.html", ignoreCase = true)) {
            val html = transformRootHtml(resolveAppletDocument(applet))
            return@withContext ResolvedWebAsset(
                bytes = html.toByteArray(Charsets.UTF_8),
                mimeType = "text/html",
                encoding = "utf-8",
                sha256 = applet.indexSha256
            )
        }

        // 2. Inline CSS & JS bundles
        if (cleanPath.equals("/style.css", ignoreCase = true) && applet.inlineCss.isNotBlank()) {
            return@withContext ResolvedWebAsset(
                bytes = applet.inlineCss.toByteArray(Charsets.UTF_8),
                mimeType = "text/css",
                encoding = "utf-8"
            )
        }
        if (cleanPath.equals("/app.js", ignoreCase = true) && applet.inlineJs.isNotBlank()) {
            return@withContext ResolvedWebAsset(
                bytes = applet.inlineJs.toByteArray(Charsets.UTF_8),
                mimeType = "text/javascript",
                encoding = "utf-8"
            )
        }

        val manifestObj = runCatching {
            if (applet.manifestPathsJson.isNotBlank()) JSONObject(applet.manifestPathsJson) else null
        }.getOrNull()

        // 3. Look up subresource SHA-256 in NIP-5A manifest path map
        var assetSha = lookupPathShaInJson(manifestObj, cleanPath)

        // 4. Fallback to NIP-5A v1 Kind 34128 per-path events if not in manifestPathsJson
        if (assetSha.isBlank() && applet.authorPubkey.length == 64) {
            val legacyMap = fetchLegacyNsitePathMap(applet.authorPubkey)
            val noSlash = cleanPath.removePrefix("/")
            assetSha = legacyMap[cleanPath]
                ?: legacyMap[noSlash]
                ?: legacyMap["$cleanPath/index.html"]
                ?: ""
        }

        val extHint = cleanPath.substringAfterLast('/', "").substringAfterLast('.', "")
        if (assetSha.isNotBlank()) {
            val servers = candidateBlossomServersFor(applet, manifestObj)
            val outcome = networkEngine.fetchBlobAdaptively(
                primaryServer = applet.blossomServer,
                manifestAndAuthorServers = servers,
                sha256 = assetSha,
                extensionHint = extHint
            )
            if (outcome is BlossomClient.BlobFetchOutcome.Verified) {
                val (mime, enc) = guessMimeType(cleanPath, outcome.bytes)
                val finalBytes = if (mime == "text/html" && isMainFrame) {
                    transformRootHtml(String(outcome.bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                } else {
                    outcome.bytes
                }
                return@withContext ResolvedWebAsset(
                    bytes = finalBytes,
                    mimeType = mime,
                    encoding = enc,
                    sha256 = assetSha
                )
            }
        }

        // 5. Direct gateway / URL relative asset fallback if manifest specified ["url", ...]
        val directUrl = manifestObj?.optString("__direct_url").orEmpty()
        if (directUrl.startsWith("https://", ignoreCase = true) || directUrl.startsWith("http://", ignoreCase = true)) {
            val resolvedRelative = runCatching {
                java.net.URI(directUrl).resolve(cleanPath).toString()
            }.getOrNull()
            if (!resolvedRelative.isNullOrBlank()) {
                val bytes = blossomClient.fetchRawUrlBytes(resolvedRelative)
                if (bytes != null) {
                    val (mime, enc) = guessMimeType(cleanPath, bytes)
                    return@withContext ResolvedWebAsset(bytes = bytes, mimeType = mime, encoding = enc)
                }
            }
        }

        // 6. Single-Page Application (SPA) route fallback for main-frame navigations without file extensions
        val lastSegment = cleanPath.substringAfterLast('/')
        if (isMainFrame && !lastSegment.contains('.')) {
            val html = transformRootHtml(resolveAppletDocument(applet))
            return@withContext ResolvedWebAsset(
                bytes = html.toByteArray(Charsets.UTF_8),
                mimeType = "text/html",
                encoding = "utf-8",
                sha256 = applet.indexSha256
            )
        }

        null
    }

    private fun lookupPathShaInJson(manifestObj: JSONObject?, cleanPath: String): String {
        if (manifestObj == null) return ""
        val noLeadingSlash = cleanPath.removePrefix("/")
        val candidates = listOf(
            cleanPath,
            noLeadingSlash,
            "./$noLeadingSlash",
            "${cleanPath.trimEnd('/')}/index.html",
            "$cleanPath.html"
        )
        for (key in candidates) {
            val v = manifestObj.optString(key).trim().lowercase()
            if (v.length == 64 && !key.startsWith("__")) return v
        }
        return ""
    }

    private fun buildUnreachableBundleDiagnosticHtml(
        applet: InstalledAppletEntity,
        sha256: String,
        directUrl: String,
        triedServers: List<String>,
        integrityReason: String? = null
    ): String {
        val safeTitle = applet.title.replace("<", "&lt;").replace(">", "&gt;")
        val serversList = (triedServers + BlossomClient.DEFAULT_BLOSSOM_SERVERS).distinct().joinToString(", ")
        val detailLine = integrityReason
            ?: "Could not fetch verified bundle bytes for <strong>$safeTitle</strong> from Blossom mirrors."
        return """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <style>
    body { margin:0; padding:24px; background:#000; color:#fff; font-family:system-ui,-apple-system,sans-serif; }
    .box { max-width:520px; margin:24px auto; padding:20px; border-radius:16px; background:#141414; border:1px solid #FF1493; }
    h2 { margin:0 0 8px; font-size:18px; color:#FF1493; }
    p { margin:0 0 10px; font-size:13px; line-height:1.5; color:#ccc; }
    .mono { font-family:monospace; font-size:11px; word-break:break-all; color:#FFB6D9; background:#090909; padding:10px; border-radius:10px; }
  </style>
</head>
<body>
  <div class="box">
    <h2>${if (integrityReason != null) "Blossom SHA-256 Verification Failed" else "Blossom Blob Unreachable"}</h2>
    <p>$detailLine</p>
    <div class="mono">SHA-256: ${sha256.ifBlank { directUrl.ifBlank { "(none)" } }}<br/><br/>Tried servers: $serversList</div>
  </div>
</body>
</html>
        """.trimIndent()
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

        fun parseKind0Metadata(
            event: NostrEvent,
            existing: PublisherProfileEntity? = null
        ): PublisherProfileEntity? {
            if (event.kind != NostrKinds.METADATA || event.content.isBlank()) return null
            val pk = event.pubkey.trim().lowercase()
            if (pk.length != 64) return null
            if (existing != null && event.createdAt < existing.eventCreatedAtSec) {
                return existing
            }
            val obj = runCatching { JSONObject(event.content) }.getOrNull() ?: return null
            val npub = existing?.npub?.takeIf { it.startsWith("npub1") }
                ?: runCatching { NostrCrypto.encodeNpub(pk) }.getOrDefault(pk.take(16))

            val displayName = NsiteSpamFilter.sanitizeSingleLine(
                obj.optString("display_name").ifBlank { obj.optString("displayName") }
            ).take(60)
            val username = NsiteSpamFilter.sanitizeSingleLine(
                obj.optString("name").ifBlank { obj.optString("username") }
            ).take(50)
            val nip05 = NsiteSpamFilter.sanitizeSingleLine(obj.optString("nip05")).take(80)
            val picture = obj.optString("picture").ifBlank { obj.optString("image") }.trim()
                .takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
                .orEmpty()
            val banner = obj.optString("banner").trim()
                .takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
                .orEmpty()
            val about = NsiteSpamFilter.sanitizeSingleLine(
                obj.optString("about").ifBlank { obj.optString("bio") }
            ).take(280)
            val website = obj.optString("website").trim().take(120)
            val lud16 = obj.optString("lud16").trim().take(80)

            return PublisherProfileEntity(
                pubkeyHex = pk,
                npub = npub,
                displayName = displayName.ifBlank { existing?.displayName.orEmpty() },
                username = username.ifBlank { existing?.username.orEmpty() },
                nip05 = nip05.ifBlank { existing?.nip05.orEmpty() },
                pictureUrl = picture.ifBlank { existing?.pictureUrl.orEmpty() },
                bannerUrl = banner.ifBlank { existing?.bannerUrl.orEmpty() },
                about = about.ifBlank { existing?.about.orEmpty() },
                website = website.ifBlank { existing?.website.orEmpty() },
                lud16 = lud16.ifBlank { existing?.lud16.orEmpty() },
                eventCreatedAtSec = event.createdAt,
                updatedAt = System.currentTimeMillis()
            )
        }

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
                val sha = tag[2].trim().lowercase()
                if (route.isNotBlank() && sha.isNotBlank()) {
                    pathMap.put(route, sha)
                    if (route == "/index.html" || route == "index.html" || route == "/") {
                        pathIndexSha = sha
                    } else if (fallbackHtmlSha.isBlank() && route.endsWith(".html", ignoreCase = true)) {
                        fallbackHtmlSha = sha
                    }
                }
            }

            val xTagSha = event.tags
                .firstOrNull { it.size >= 2 && (it[0] == "x" || it[0] == "sha256") && SHA256_HEX_REGEX.matches(it[1].trim()) }
                ?.get(1)?.trim()?.lowercase().orEmpty()

            val urlTagNames = if (isNsite) setOf("url", "u") else setOf("url", "u", "r")
            val rawDirectUrl = event.tags
                .firstOrNull {
                    it.size >= 2 &&
                        it[0] in urlTagNames &&
                        (it[1].startsWith("https://", ignoreCase = true) || it[1].startsWith("http://", ignoreCase = true))
                }
                ?.get(1)?.trim().orEmpty()
            val directUrl = if (rawDirectUrl.isNotBlank()) {
                ExternalSiteLauncher.normalizeGatewayUrl(rawDirectUrl).orEmpty()
            } else ""

            val trimmedContent = event.content.trim()
            val contentLooksLikeHtml = trimmedContent.startsWith("<!DOCTYPE", ignoreCase = true) ||
                trimmedContent.startsWith("<html", ignoreCase = true) ||
                trimmedContent.startsWith("<div", ignoreCase = true) ||
                trimmedContent.startsWith("<main", ignoreCase = true) ||
                trimmedContent.startsWith("<section", ignoreCase = true)

            val jsonContentMeta = if (trimmedContent.startsWith("{")) {
                runCatching { JSONObject(trimmedContent) }.getOrNull()
            } else null

            jsonContentMeta?.optJSONObject("paths")?.let { pObj ->
                val keys = pObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = pObj.optString(k).trim().lowercase()
                    if (k.isNotBlank() && SHA256_HEX_REGEX.matches(v) && !pathMap.has(k)) {
                        pathMap.put(k, v)
                    }
                }
            }
            if (pathIndexSha.isBlank()) {
                pathIndexSha = pathMap.optString("/index.html").ifBlank { pathMap.optString("index.html") }
            }

            val resolvedIndexSha = pathIndexSha
                .ifBlank { fallbackHtmlSha }
                .ifBlank { xTagSha }

            if (resolvedIndexSha.isBlank() && pathMap.length() == 0 && directUrl.isBlank() && !contentLooksLikeHtml) {
                return null
            }

            val rawTitle = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)?.ifBlank { null }
                ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "name" }?.get(1)?.ifBlank { null }
                ?: jsonContentMeta?.optString("title")?.ifBlank { null }
                ?: jsonContentMeta?.optString("name")?.ifBlank { null }
                ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "alt" }?.get(1)?.ifBlank { null }
                ?: humanizeSlug(dTag, isNsite, event.pubkey)
            val cleanTitle = NsiteSpamFilter.sanitizeSingleLine(rawTitle).take(80)

            val rawDesc = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)?.ifBlank { null }
                ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "summary" }?.get(1)?.ifBlank { null }
                ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "about" }?.get(1)?.ifBlank { null }
                ?: jsonContentMeta?.optString("description")?.ifBlank { null }
                ?: jsonContentMeta?.optString("about")?.ifBlank { null }
                ?: if (!contentLooksLikeHtml && trimmedContent.isNotBlank() && jsonContentMeta == null) {
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

            val serversFromTags = event.tags
                .filter {
                    it.size >= 2 &&
                        (it[0] == "server" || it[0] == "blossom") &&
                        (it[1].startsWith("https://", ignoreCase = true) || it[1].startsWith("http://", ignoreCase = true))
                }
                .map { it[1].trim().trimEnd('/') }
                .distinct()
            if (serversFromTags.size > 1) {
                val srvArr = JSONArray()
                serversFromTags.forEach { srvArr.put(it) }
                pathMap.put("__servers", srvArr)
            }

            val serverFromDirectUrl = if (directUrl.isNotBlank()) {
                runCatching {
                    val uri = java.net.URI(directUrl)
                    if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else null
                }.getOrNull()
            } else null
            val server = serversFromTags.firstOrNull() ?: serverFromDirectUrl ?: "https://blossom.primal.net"

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
                accentHex = existing?.accentHex ?: "#FF1493",
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
                updatedAt = event.createdAt * 1000L,
                latestEventId = event.id
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
                "2048" in hay || "game" in hay || "chess" in hay || "play" in hay || "mines" in hay || "puzzle" in hay -> "🎮"
                "music" in hay || "radio" in hay || "audio" in hay || "synth" in hay || "beat" in hay -> "🎵"
                "draw" in hay || "pixel" in hay || "art" in hay || "canvas" in hay || "meme" in hay -> "🎨"
                "poll" in hay || "vote" in hay || "stats" in hay || "analytics" in hay -> "📊"
                "note" in hay || "blog" in hay || "zine" in hay || "wiki" in hay || "read" in hay -> "📖"
                "calc" in hay || "convert" in hay || "tool" in hay || "json" in hay -> "🧮"
                isNsite -> "🌐"
                else -> "🧩"
            }
        }

        fun guessMimeType(path: String, bytes: ByteArray? = null): Pair<String, String?> {
            val clean = path.substringBefore('?').substringBefore('#').lowercase()
            val ext = clean.substringAfterLast('/', "").substringAfterLast('.', "")
            return when (ext) {
                "html", "htm" -> "text/html" to "utf-8"
                "js", "mjs", "cjs" -> "text/javascript" to "utf-8"
                "css" -> "text/css" to "utf-8"
                "json", "webmanifest", "map" -> "application/json" to "utf-8"
                "svg" -> "image/svg+xml" to "utf-8"
                "xml" -> "application/xml" to "utf-8"
                "txt", "md" -> "text/plain" to "utf-8"
                "png" -> "image/png" to null
                "jpg", "jpeg" -> "image/jpeg" to null
                "webp" -> "image/webp" to null
                "gif" -> "image/gif" to null
                "avif" -> "image/avif" to null
                "ico" -> "image/x-icon" to null
                "wasm" -> "application/wasm" to null
                "woff2" -> "font/woff2" to null
                "woff" -> "font/woff" to null
                "ttf" -> "font/ttf" to null
                "otf" -> "font/otf" to null
                "mp3" -> "audio/mpeg" to null
                "wav" -> "audio/wav" to null
                "ogg" -> "audio/ogg" to null
                "mp4" -> "video/mp4" to null
                "webm" -> "video/webm" to null
                else -> {
                    val head = bytes?.let {
                        String(it, 0, minOf(it.size, 64), Charsets.UTF_8).trimStart()
                    }.orEmpty()
                    if (head.startsWith("<!DOCTYPE", ignoreCase = true) || head.startsWith("<html", ignoreCase = true)) {
                        "text/html" to "utf-8"
                    } else {
                        "application/octet-stream" to null
                    }
                }
            }
        }
    }
}
