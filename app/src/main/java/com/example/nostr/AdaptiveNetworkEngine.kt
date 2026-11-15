package com.example.nostr

import com.example.data.BlossomServerHealthEntity
import com.example.data.ConfiguredBlossomServerEntity
import com.example.data.RelayEntity
import com.example.data.RelayHealthEntity
import com.example.data.ShellDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

enum class NetworkOperationType {
    DISCOVER_NSITES,
    RESOLVE_MANIFEST,
    PUBLISH_MANIFEST,
    FETCH_METADATA,
    PUBLISH_NOTE,
    UPLOAD_BLOB,
    RETRIEVE_BLOB
}

data class AuthorRelayPreferences(
    val writeRelays: List<String>,
    val readRelays: List<String>,
    val updatedAtSec: Long
)

data class RelayMaintenanceSummary(
    val discoveredUrls: List<String>,
    val droppedUrls: List<String>,
    val closedSocketUrls: List<String>,
    val probedCount: Int,
    val healthyCount: Int
)

/**
 * Shared Adaptive Relay + Blossom Networking Engine.
 *
 * - Discovers and probes Nostr relays (NIP-11, NIP-65, naddr/manifest hints) and Blossom servers.
 * - Actively drops dead, errored, stalled-connecting, or idle ephemeral connections.
 * - Targets relevant relays per operation using NIP-65 outbox/inbox roles, NIP-11 capabilities,
 *   domain affinity, and time-decayed failure telemetry.
 * - Enforces Blossom upload verification (requiring >= 1 verified server) and mirrors blobs
 *   to backup servers for content redundancy and recovery.
 */
class AdaptiveNetworkEngine(
    private val dao: ShellDao,
    val relayPool: RelayPoolClient,
    val blossomClient: BlossomClient,
    private val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val authorNip65Cache = ConcurrentHashMap<String, AuthorRelayPreferences>()
    private val discoveredCandidates = ConcurrentHashMap<String, String>()
    private val lastProbeAttemptMs = ConcurrentHashMap<String, Long>()

    init {
        relayPool.healthObserver = { url, success, latencyMs, errorReason ->
            engineScope.launch {
                recordRelayOutcome(
                    rawUrl = url,
                    success = success,
                    verifiedPublish = false,
                    latencyMs = latencyMs,
                    errorReason = errorReason
                )
            }
        }
        blossomClient.healthObserver = { serverUrl, isUpload, success, verifiedRetrieval, hashMismatch, latencyMs, msg ->
            engineScope.launch {
                recordBlossomOutcome(
                    rawServerUrl = serverUrl,
                    isUpload = isUpload,
                    success = success,
                    verifiedRetrieval = verifiedRetrieval,
                    hashMismatch = hashMismatch,
                    latencyMs = latencyMs,
                    statusMessage = msg
                )
            }
        }
    }

    fun recordAuthorNip65Relays(
        pubkeyHex: String,
        writeRelays: List<String>,
        readRelays: List<String>,
        createdAtSec: Long
    ) {
        val pk = pubkeyHex.trim().lowercase()
        if (pk.length != 64) return
        val existing = authorNip65Cache[pk]
        if (existing != null && createdAtSec < existing.updatedAtSec) return

        val cleanWrite = writeRelays.map { RelayPoolClient.normalizeRelayUrl(it) }
            .filter { isAutoDiscoverableRelayUrl(it) || it.startsWith("ws://127.0.0.1") }
            .distinct()
        val cleanRead = readRelays.map { RelayPoolClient.normalizeRelayUrl(it) }
            .filter { isAutoDiscoverableRelayUrl(it) || it.startsWith("ws://127.0.0.1") }
            .distinct()

        authorNip65Cache[pk] = AuthorRelayPreferences(
            writeRelays = cleanWrite,
            readRelays = cleanRead,
            updatedAtSec = createdAtSec
        )
        recordRelayHints(cleanWrite + cleanRead, "NIP-65:${pk.take(8)}")
    }

    fun getAuthorWriteRelays(pubkeyHex: String): List<String> =
        authorNip65Cache[pubkeyHex.trim().lowercase()]?.writeRelays.orEmpty()

    fun getAuthorReadRelays(pubkeyHex: String): List<String> =
        authorNip65Cache[pubkeyHex.trim().lowercase()]?.readRelays.orEmpty()

    fun recordRelayHints(urls: Collection<String>, source: String = "hint") {
        urls.forEach { raw ->
            val clean = RelayPoolClient.normalizeRelayUrl(raw)
            if (isAutoDiscoverableRelayUrl(clean)) {
                discoveredCandidates.putIfAbsent(clean, source)
            }
        }
    }

    suspend fun bootstrapDefaultBlossomServers(primaryServer: String) = withContext(Dispatchers.IO) {
        val cleanPrimary = BlossomClient.normalizeServerUrl(primaryServer)
            .ifBlank { "https://blossom.primal.net" }
        val existing = dao.getBlossomServersList()
        val existingUrls = existing.map { it.serverUrl }.toSet()

        if (cleanPrimary !in existingUrls) {
            dao.upsertBlossomServer(
                ConfiguredBlossomServerEntity(
                    serverUrl = cleanPrimary,
                    enabled = true,
                    isBackupMirror = false
                )
            )
        }
        if (existing.isEmpty()) {
            DEFAULT_BACKUP_MIRRORS.forEach { mirror ->
                if (mirror != cleanPrimary) {
                    dao.upsertBlossomServer(
                        ConfiguredBlossomServerEntity(
                            serverUrl = mirror,
                            enabled = true,
                            isBackupMirror = true
                        )
                    )
                }
            }
        }
    }

    suspend fun probeAllConfiguredEndpoints() = withContext(Dispatchers.IO) {
        val relays = dao.getRelaysList()
        coroutineScope {
            relays.map { r ->
                async { probeRelayCapabilities(r.url) }
            }.awaitAll()
        }
        val servers = dao.getBlossomServersList()
        coroutineScope {
            servers.map { s ->
                async { probeBlossomCapabilities(s.serverUrl) }
            }.awaitAll()
        }
    }

    suspend fun probeRelayCapabilities(rawUrl: String): RelayHealthEntity? = withContext(Dispatchers.IO) {
        val url = RelayPoolClient.normalizeRelayUrl(rawUrl)
        if (url.isBlank()) return@withContext null
        val startMs = System.currentTimeMillis()
        val nip11 = relayPool.probeRelayNip11(url)
        val probeElapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        val now = System.currentTimeMillis()
        val prev = dao.getRelayHealth(url) ?: RelayHealthEntity(url = url)
        val updated = if (nip11 != null) {
            prev.copy(
                supportedNipsCsv = nip11.supportedNips.sorted().joinToString(","),
                softwareInfo = listOfNotNull(
                    nip11.name.takeIf { it.isNotBlank() },
                    nip11.software.takeIf { it.isNotBlank() }
                ).joinToString(" • ").take(80),
                authRequired = nip11.authRequired,
                paymentRequired = nip11.paymentRequired,
                maxMessageBytes = nip11.maxMessageLength,
                avgLatencyMs = blendLatency(prev.avgLatencyMs, probeElapsedMs),
                lastProbedAt = now
            )
        } else {
            prev.copy(lastProbedAt = now)
        }
        dao.upsertRelayHealth(updated)
        updated
    }

    suspend fun probeBlossomCapabilities(rawServerUrl: String): BlossomServerHealthEntity? =
        withContext(Dispatchers.IO) {
            val clean = BlossomClient.normalizeServerUrl(rawServerUrl)
            if (clean.isBlank()) return@withContext null
            val caps = blossomClient.probeServerCapabilities(clean)
            val now = System.currentTimeMillis()
            val prev = dao.getBlossomHealth(clean) ?: BlossomServerHealthEntity(serverUrl = clean)
            val updated = prev.copy(
                requiresAuth = caps.requiresAuth,
                maxUploadBytes = if (caps.maxUploadBytes > 0L) caps.maxUploadBytes else prev.maxUploadBytes,
                supportsMirror = caps.supportsMirror || prev.supportsMirror,
                avgLatencyMs = blendLatency(prev.avgLatencyMs, caps.latencyMs),
                consecutiveFailures = if (caps.reachable) 0 else prev.consecutiveFailures + 1,
                lastSuccessAt = if (caps.reachable) now else prev.lastSuccessAt,
                lastFailureAt = if (!caps.reachable) now else prev.lastFailureAt,
                lastStatusMessage = caps.statusDetail,
                lastProbedAt = now
            )
            dao.upsertBlossomHealth(updated)
            updated
        }

    /**
     * Ranks and filters relays specifically relevant to [operation], combining:
     * - NIP-65 author write relays (outbox) for querying/publishing author events
     * - NIP-65 recipient read relays (inbox) for targeting notes/mentions
     * - NIP-11 capability flags (NIP-50 search, NIP-65, auth/payment gating)
     * - Domain specialization (Nsite relays vs metadata indexers vs general relays)
     * - Time-decayed failure telemetry, dropping dead relays when healthy ones exist.
     */
    suspend fun selectRelaysForOperation(
        operation: NetworkOperationType,
        extraCandidateRelays: List<String> = emptyList(),
        targetAuthorPubkeys: Collection<String> = emptyList(),
        mentionedPubkeys: Collection<String> = emptyList(),
        hasActiveSigner: Boolean = true,
        limit: Int = 8
    ): List<String> = withContext(Dispatchers.IO) {
        val configured: List<RelayEntity> = dao.getRelaysList()
        val healthMap = dao.getAllRelayHealth().associateBy { it.url }
        val liveMap = relayPool.relayStatuses.value
        val now = System.currentTimeMillis()

        val authorWriteSet = buildSet {
            targetAuthorPubkeys.forEach { pk ->
                getAuthorWriteRelays(pk).forEach { add(it) }
            }
        }
        val mentionedReadSet = buildSet {
            mentionedPubkeys.forEach { pk ->
                getAuthorReadRelays(pk).forEach { add(it) }
            }
        }
        val extraSet = extraCandidateRelays
            .map { RelayPoolClient.normalizeRelayUrl(it) }
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .toSet()

        val rawCandidates = buildList {
            configured.forEach { entity ->
                val allowed = when (operation) {
                    NetworkOperationType.PUBLISH_MANIFEST,
                    NetworkOperationType.PUBLISH_NOTE -> entity.writeEnabled
                    else -> entity.readEnabled
                }
                if (allowed) add(entity.url)
            }
            addAll(authorWriteSet)
            if (operation == NetworkOperationType.PUBLISH_NOTE) {
                addAll(mentionedReadSet)
            }
            addAll(extraSet)
        }.distinct()

        val nonDead = rawCandidates.filter { url ->
            !isRelayDead(healthMap[url], liveMap[url], now)
        }
        val effectivePool = nonDead.ifEmpty { rawCandidates }

        effectivePool
            .sortedByDescending { url ->
                scoreRelay(
                    url = url,
                    health = healthMap[url],
                    live = liveMap[url],
                    operation = operation,
                    hasActiveSigner = hasActiveSigner,
                    nowMs = now,
                    isTargetAuthorOutbox = url in authorWriteSet,
                    isMentionedInbox = url in mentionedReadSet,
                    isExplicitHint = url in extraSet
                )
            }
            .take(limit.coerceAtLeast(1))
    }

    /**
     * Drops persistently dead/failing relays from the active pool and closes hung, errored,
     * or idle ephemeral WebSocket connections via [RelayPoolClient.dropDeadConnections].
     */
    suspend fun dropDeadRelaysAndConnections(
        minKeepRelays: Int = 2,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        val configured = dao.getRelaysList()
        val healthMap = dao.getAllRelayHealth().associateBy { it.url }
        val liveMap = relayPool.relayStatuses.value

        val deadCandidates = configured
            .mapNotNull { entity ->
                val health = healthMap[entity.url]
                val live = liveMap[entity.url]
                if (isRelayDead(health, live, nowMs)) {
                    val severity = (health?.consecutiveFailures ?: 0) * 10 +
                        if (live?.status == RelayConnectionStatus.ERROR) 5 else 0
                    entity.url to severity
                } else {
                    null
                }
            }
            .sortedByDescending { it.second }
            .map { it.first }

        val maxDroppable = (configured.size - minKeepRelays).coerceAtLeast(0)
        val droppedConfigured = deadCandidates.take(maxDroppable)

        val dropReport = relayPool.dropDeadConnections(
            explicitDeadUrls = droppedConfigured.toSet(),
            nowMs = nowMs
        )

        droppedConfigured.forEach { url ->
            dao.deleteRelay(url)
            relayPool.disconnectRelay(url, removeFromMap = true)
        }

        val allClosed = (dropReport.closedSockets + dropReport.expiredEphemeral).distinct()
        droppedConfigured to allClosed
    }

    /**
     * Auto-discovers healthy Nostr relays from incoming NIP-65 relay lists, manifest/naddr hints,
     * and curated bootstrap candidates. Validates URLs, probes NIP-11 / WebSocket handshakes,
     * filters out payment-gated or unreachable nodes, and adopts responsive relays up to [maxConfiguredCap].
     */
    suspend fun autoDiscoverAndAdoptRelays(
        extraCandidates: Collection<String> = emptyList(),
        maxConfiguredCap: Int = 10,
        maxProbesPerPass: Int = 6
    ): List<String> = withContext(Dispatchers.IO) {
        recordRelayHints(extraCandidates, "manual-hint")
        val configured = dao.getRelaysList()
        val configuredSet = configured.map { it.url }.toSet()
        val availableSlots = (maxConfiguredCap - configured.size).coerceAtLeast(0)
        if (availableSlots == 0) return@withContext emptyList()

        val healthMap = dao.getAllRelayHealth().associateBy { it.url }
        val liveMap = relayPool.relayStatuses.value
        val now = System.currentTimeMillis()

        val poolCandidates = buildList {
            extraCandidates.map { RelayPoolClient.normalizeRelayUrl(it) }.forEach { add(it) }
            addAll(discoveredCandidates.keys)
            authorNip65Cache.values.forEach { pref ->
                addAll(pref.writeRelays)
                addAll(pref.readRelays)
            }
            if (configured.size < 6) {
                addAll(AUTO_DISCOVERY_FALLBACK_RELAYS)
            }
        }
            .filter { url ->
                isAutoDiscoverableRelayUrl(url) &&
                    url !in configuredSet &&
                    !isRelayDead(healthMap[url], liveMap[url], now) &&
                    (now - (lastProbeAttemptMs[url] ?: 0L)) >= 180_000L
            }
            .distinct()
            .take(maxProbesPerPass)

        if (poolCandidates.isEmpty()) return@withContext emptyList()

        val probeResults = coroutineScope {
            poolCandidates.map { candidateUrl ->
                async {
                    lastProbeAttemptMs[candidateUrl] = System.currentTimeMillis()
                    val nip11Health = probeRelayCapabilities(candidateUrl)
                    val nip11Ok = nip11Health != null &&
                        nip11Health.lastProbedAt > 0L &&
                        (nip11Health.supportedNipsCsv.isNotBlank() || nip11Health.softwareInfo.isNotBlank())
                    if (nip11Health?.paymentRequired == true) {
                        return@async null
                    }
                    val wsReady = if (nip11Ok) {
                        true
                    } else {
                        // Some valid Nostr relays (or local dev relays) don't serve NIP-11 JSON — test WS handshake
                        relayPool.connectRelay(candidateUrl, isEphemeralOutbox = true)
                        val ok = relayPool.awaitSocketReady(candidateUrl, timeoutMs = 1300L)
                        if (!ok) {
                            relayPool.disconnectRelay(candidateUrl, removeFromMap = true)
                        }
                        ok
                    }
                    if (wsReady) {
                        candidateUrl
                    } else {
                        recordRelayOutcome(
                            rawUrl = candidateUrl,
                            success = false,
                            verifiedPublish = false,
                            latencyMs = null,
                            errorReason = "Auto-discovery probe unreachable"
                        )
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val adopted = probeResults.take(availableSlots)
        adopted.forEach { newUrl ->
            dao.upsertRelay(RelayEntity(url = newUrl))
            relayPool.connectRelay(newUrl, isEphemeralOutbox = false)
        }
        adopted
    }

    /**
     * Full relay hygiene cycle:
     * 1. Drops dead/stalled relays and closes dead WebSocket connections.
     * 2. Probes and adopts newly discovered healthy relays up to pool capacity.
     * 3. Probes stale configured relays & Blossom mirrors.
     */
    suspend fun maintainRelays(
        extraCandidates: Collection<String> = emptyList()
    ): RelayMaintenanceSummary = withContext(Dispatchers.IO) {
        val (droppedUrls, closedSocketUrls) = dropDeadRelaysAndConnections()
        val discoveredUrls = autoDiscoverAndAdoptRelays(extraCandidates = extraCandidates)
        val relays = dao.getRelaysList()
        val now = System.currentTimeMillis()
        val healthMap = dao.getAllRelayHealth().associateBy { it.url }
        var probed = 0
        relays.forEach { entity ->
            val lastProbed = healthMap[entity.url]?.lastProbedAt ?: 0L
            if (now - lastProbed > 120_000L) {
                probeRelayCapabilities(entity.url)
                probed++
            }
        }
        val liveStatuses = relayPool.relayStatuses.value
        val healthyCount = relays.count { entity ->
            liveStatuses[entity.url]?.status == RelayConnectionStatus.CONNECTED ||
                !isRelayDead(dao.getRelayHealth(entity.url), liveStatuses[entity.url], now)
        }
        RelayMaintenanceSummary(
            discoveredUrls = discoveredUrls,
            droppedUrls = droppedUrls,
            closedSocketUrls = closedSocketUrls,
            probedCount = probed,
            healthyCount = healthyCount
        )
    }

    /**
     * Ranks Blossom servers for upload or retrieval, respecting max file size limits and
     * decaying temporary failures while heavily penalizing servers that served SHA-256 mismatches.
     */
    suspend fun selectBlossomServers(
        operation: NetworkOperationType,
        primaryPreferred: String,
        manifestOrAuthorServers: List<String> = emptyList(),
        payloadSizeBytes: Long = 0L
    ): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        val cleanPrimary = BlossomClient.normalizeServerUrl(primaryPreferred)
        val configured = dao.getBlossomServersList().filter { it.enabled }
        val healthMap = dao.getAllBlossomHealth().associateBy { it.serverUrl }
        val now = System.currentTimeMillis()

        val primaries = buildList {
            if (cleanPrimary.isNotBlank()) add(cleanPrimary)
            if (operation == NetworkOperationType.RETRIEVE_BLOB) {
                manifestOrAuthorServers.map { BlossomClient.normalizeServerUrl(it) }
                    .filter { it.isNotBlank() }
                    .forEach { add(it) }
            }
            configured.filter { !it.isBackupMirror }.forEach { add(it.serverUrl) }
        }.distinct()

        val mirrors = buildList {
            configured.filter { it.isBackupMirror }.forEach { add(it.serverUrl) }
            if (operation == NetworkOperationType.RETRIEVE_BLOB) {
                BlossomClient.DEFAULT_BLOSSOM_SERVERS.forEach { add(it) }
            } else {
                DEFAULT_BACKUP_MIRRORS.forEach { add(it) }
            }
        }.map { BlossomClient.normalizeServerUrl(it) }
            .filter { it.isNotBlank() && it !in primaries }
            .distinct()

        val eligibleFilter: (String) -> Boolean = { srv ->
            val h = healthMap[srv]
            !(payloadSizeBytes > 0L && h != null && h.maxUploadBytes in 1 until payloadSizeBytes)
        }

        val rankedPrimaries = primaries
            .filter(eligibleFilter)
            .sortedByDescending { srv ->
                val bonus = if (srv == cleanPrimary) 25.0 else 0.0
                bonus + scoreBlossomServer(healthMap[srv], operation, now)
            }

        val rankedMirrors = mirrors
            .filter(eligibleFilter)
            .sortedByDescending { srv -> scoreBlossomServer(healthMap[srv], operation, now) }

        rankedPrimaries to rankedMirrors
    }

    /**
     * Publishes a signed Nostr event across adaptively ranked, operation-relevant write relays,
     * automatically handling NIP-42 `AUTH` challenges when `signEvent` is available, and performing
     * round-trip `REQ` verification to distinguish `ACCEPTED` from `VERIFIED_RETRIEVAL`.
     */
    suspend fun publishEventAdaptively(
        event: NostrEvent,
        extraOutboxRelays: List<String> = emptyList(),
        verifyRetrieval: Boolean = true,
        signAuthIfChallenged: (suspend (UnsignedNostrEvent) -> NostrEvent)? = null
    ): RelayPublishSummary = withContext(Dispatchers.IO) {
        val op = if (event.kind == NostrKinds.TEXT_NOTE) {
            NetworkOperationType.PUBLISH_NOTE
        } else {
            NetworkOperationType.PUBLISH_MANIFEST
        }
        val mentionedPubkeys = event.tags
            .filter { it.size >= 2 && it[0] == "p" }
            .map { it[1].trim().lowercase() }
            .filter { it.length == 64 }

        val rankedRelays = selectRelaysForOperation(
            operation = op,
            extraCandidateRelays = extraOutboxRelays,
            targetAuthorPubkeys = listOf(event.pubkey.lowercase()),
            mentionedPubkeys = mentionedPubkeys,
            hasActiveSigner = signAuthIfChallenged != null,
            limit = 8
        )
        if (rankedRelays.isEmpty()) {
            return@withContext RelayPublishSummary(event, emptyList())
        }

        var summary = relayPool.publishEvent(
            event = event,
            targetRelayUrls = rankedRelays,
            verifyRetrieval = verifyRetrieval
        )

        // Automatic NIP-42 AUTH + retry for relays that rejected with auth-required:
        if (signAuthIfChallenged != null) {
            val authNeededRelays = summary.receipts
                .filter { !it.accepted && (it.authRequired || relayPool.relayStatuses.value[it.relayUrl]?.authChallenge != null) }
                .map { it.relayUrl }

            if (authNeededRelays.isNotEmpty()) {
                val authenticatedUrls = authNeededRelays.filter { relayUrl ->
                    relayPool.authenticateRelayNip42(
                        rawUrl = relayUrl,
                        signEvent = signAuthIfChallenged,
                        pubkeyHex = event.pubkey
                    )
                }
                if (authenticatedUrls.isNotEmpty()) {
                    val retrySummary = relayPool.publishEvent(
                        event = event,
                        targetRelayUrls = authenticatedUrls,
                        verifyRetrieval = verifyRetrieval
                    )
                    val retryMap = retrySummary.receipts.associateBy { it.relayUrl }
                    summary = RelayPublishSummary(
                        event = event,
                        receipts = summary.receipts.map { prev -> retryMap[prev.relayUrl] ?: prev }
                    )
                }
            }
        }

        summary.receipts.forEach { r ->
            recordRelayOutcome(
                rawUrl = r.relayUrl,
                success = r.accepted,
                verifiedPublish = r.stage == DeliveryVerificationStage.VERIFIED_RETRIEVAL,
                latencyMs = relayPool.relayStatuses.value[r.relayUrl]?.latencyMs,
                errorReason = if (r.accepted) null else r.message
            )
        }
        summary
    }

    /**
     * Uploads a blob enforcing the confirmed redundancy policy:
     * - Requires at least **1 verified server** before allowing manifest publication.
     * - Automatically falls back across healthy servers if the primary server rejects or fails.
     * - Mirrors the blob to up to `maxBackupMirrors` secondary Blossom servers for redundancy.
     */
    suspend fun uploadBlobWithRedundancy(
        primaryPreferred: String,
        bytes: ByteArray,
        mimeType: String,
        extension: String,
        pubkeyHex: String,
        maxBackupMirrors: Int = 2,
        signAuthEvent: suspend (UnsignedNostrEvent) -> NostrEvent
    ): BlossomClient.BlobUploadResult = withContext(Dispatchers.IO) {
        val (primaries, mirrors) = selectBlossomServers(
            operation = NetworkOperationType.UPLOAD_BLOB,
            primaryPreferred = primaryPreferred,
            payloadSizeBytes = bytes.size.toLong()
        )
        val orderedCandidates = (primaries + mirrors).distinct()
        val sha256 = NostrCrypto.sha256Hex(bytes).lowercase()

        if (orderedCandidates.isEmpty()) {
            return@withContext BlossomClient.BlobUploadResult(
                sha256 = sha256,
                url = "",
                serverUrl = primaryPreferred,
                uploadedLive = false,
                statusDetail = "No Blossom servers configured",
                stage = DeliveryVerificationStage.FAILED
            )
        }

        var winningResult: BlossomClient.BlobUploadResult? = null
        val failureDetails = mutableListOf<String>()

        for (candidate in orderedCandidates) {
            val res = blossomClient.uploadBlobWithSigner(
                serverUrl = candidate,
                bytes = bytes,
                mimeType = mimeType,
                extension = extension,
                pubkeyHex = pubkeyHex,
                verifyRetrieval = true,
                signAuthEvent = signAuthEvent
            )
            if (res.uploadedLive && res.verifiedRetrieval) {
                winningResult = res
                break
            } else if (res.uploadedLive && winningResult == null) {
                winningResult = res
            } else {
                failureDetails.add("${candidate.removePrefix("https://")}: ${res.statusDetail}")
            }
        }

        val primaryWin = winningResult ?: return@withContext BlossomClient.BlobUploadResult(
            sha256 = sha256,
            url = "${orderedCandidates.first()}/$sha256",
            serverUrl = orderedCandidates.first(),
            uploadedLive = false,
            statusDetail = failureDetails.joinToString("; ").ifBlank { "All Blossom uploads failed" },
            stage = DeliveryVerificationStage.FAILED
        )

        val mirrorTargets = (mirrors + primaries)
            .filter { it != primaryWin.serverUrl }
            .distinct()
            .take(maxBackupMirrors)

        val mirrored = if (mirrorTargets.isNotEmpty()) {
            coroutineScope {
                mirrorTargets.map { mirrorUrl ->
                    async {
                        val mRes = blossomClient.uploadBlobWithSigner(
                            serverUrl = mirrorUrl,
                            bytes = bytes,
                            mimeType = mimeType,
                            extension = extension,
                            pubkeyHex = pubkeyHex,
                            verifyRetrieval = true,
                            signAuthEvent = signAuthEvent
                        )
                        if (mRes.uploadedLive) mirrorUrl else null
                    }
                }.awaitAll().filterNotNull()
            }
        } else {
            emptyList()
        }

        primaryWin.copy(
            mirroredServers = mirrored,
            statusDetail = buildString {
                append(primaryWin.statusDetail)
                if (mirrored.isNotEmpty()) {
                    append(" • Mirrored to ${mirrored.size} backup server(s)")
                }
            }
        )
    }

    /**
     * Fetches a blob across adaptively ranked Blossom mirrors, discriminating verified bytes,
     * corrupt SHA-256 mismatches, and missing blobs.
     */
    suspend fun fetchBlobAdaptively(
        primaryServer: String,
        manifestAndAuthorServers: List<String>,
        sha256: String,
        extensionHint: String = "",
        directUrl: String = ""
    ): BlossomClient.BlobFetchOutcome = withContext(Dispatchers.IO) {
        val (primaries, mirrors) = selectBlossomServers(
            operation = NetworkOperationType.RETRIEVE_BLOB,
            primaryPreferred = primaryServer,
            manifestOrAuthorServers = manifestAndAuthorServers
        )
        blossomClient.fetchBlobDetailed(
            servers = (primaries + mirrors).distinct(),
            sha256 = sha256,
            extensionHint = extensionHint,
            directUrl = directUrl
        )
    }

    suspend fun recordRelayOutcome(
        rawUrl: String,
        success: Boolean,
        verifiedPublish: Boolean,
        latencyMs: Long?,
        errorReason: String?
    ) = withContext(Dispatchers.IO) {
        val url = RelayPoolClient.normalizeRelayUrl(rawUrl)
        if (url.isBlank()) return@withContext
        val now = System.currentTimeMillis()
        val prev = dao.getRelayHealth(url) ?: RelayHealthEntity(url = url)
        val authReq = prev.authRequired || (errorReason?.startsWith("auth-required:", ignoreCase = true) == true)
        val payReq = prev.paymentRequired ||
            (errorReason?.contains("payment-required", ignoreCase = true) == true) ||
            (errorReason?.contains("restricted: pay", ignoreCase = true) == true)

        val updated = if (success) {
            prev.copy(
                avgLatencyMs = blendLatency(prev.avgLatencyMs, latencyMs ?: prev.avgLatencyMs),
                successCount = prev.successCount + 1,
                consecutiveFailures = 0,
                verifiedPublishCount = prev.verifiedPublishCount + (if (verifiedPublish) 1 else 0),
                lastSuccessAt = now,
                authRequired = authReq,
                paymentRequired = payReq
            )
        } else {
            prev.copy(
                failureCount = prev.failureCount + 1,
                consecutiveFailures = (prev.consecutiveFailures + 1).coerceAtMost(20),
                lastFailureAt = now,
                lastErrorReason = errorReason?.take(120).orEmpty(),
                authRequired = authReq,
                paymentRequired = payReq
            )
        }
        dao.upsertRelayHealth(updated)
    }

    suspend fun recordBlossomOutcome(
        rawServerUrl: String,
        isUpload: Boolean,
        success: Boolean,
        verifiedRetrieval: Boolean,
        hashMismatch: Boolean,
        latencyMs: Long?,
        statusMessage: String
    ) = withContext(Dispatchers.IO) {
        val clean = BlossomClient.normalizeServerUrl(rawServerUrl)
        if (clean.isBlank() || clean == "local-cache") return@withContext
        val now = System.currentTimeMillis()
        val prev = dao.getBlossomHealth(clean) ?: BlossomServerHealthEntity(serverUrl = clean)
        val updated = if (success) {
            prev.copy(
                avgLatencyMs = blendLatency(prev.avgLatencyMs, latencyMs ?: prev.avgLatencyMs),
                uploadSuccessCount = prev.uploadSuccessCount + (if (isUpload) 1 else 0),
                verifiedRetrievalCount = prev.verifiedRetrievalCount + (if (verifiedRetrieval) 1 else 0),
                consecutiveFailures = 0,
                lastSuccessAt = now,
                lastStatusMessage = statusMessage.take(120)
            )
        } else {
            prev.copy(
                uploadFailureCount = prev.uploadFailureCount + (if (isUpload) 1 else 0),
                hashMismatchCount = prev.hashMismatchCount + (if (hashMismatch) 1 else 0),
                consecutiveFailures = (prev.consecutiveFailures + 1).coerceAtMost(20),
                lastFailureAt = now,
                lastStatusMessage = statusMessage.take(120)
            )
        }
        dao.upsertBlossomHealth(updated)
    }

    companion object {
        val DEFAULT_BACKUP_MIRRORS = listOf(
            "https://blossom.band",
            "https://nostr.download",
            "https://cdn.satellite.earth"
        )

        val AUTO_DISCOVERY_FALLBACK_RELAYS = listOf(
            "wss://relay.nsite.lol",
            "wss://purplepag.es",
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://relay.nostr.band",
            "wss://offchain.pub",
            "wss://nostr.wine"
        )

        private const val TRANSIENT_DECAY_HALF_LIFE_MS = 120_000L // 2 minutes
        private const val DEAD_RELAY_COOLDOWN_MS = 180_000L // 3 minutes

        fun isAutoDiscoverableRelayUrl(rawUrl: String): Boolean {
            val clean = RelayPoolClient.normalizeRelayUrl(rawUrl)
            if (!clean.startsWith("wss://") && !clean.startsWith("ws://")) return false
            if (clean.contains('#') || clean.contains(' ')) return false
            val authority = clean.substringAfter("://").substringBefore('/')
            if (authority.isBlank() || authority.contains('@')) return false
            val host = authority.substringBefore(':').lowercase()
            if (host.isBlank() || host.endsWith(".onion") || host.endsWith(".i2p")) return false
            if (host == "example.com" || host.endsWith(".example") || host.endsWith(".invalid")) return false
            val uri = runCatching { URI(clean) }.getOrNull() ?: return false
            return !uri.host.isNullOrBlank()
        }

        fun isRelayDead(
            health: RelayHealthEntity?,
            live: RelayStatusInfo?,
            nowMs: Long = System.currentTimeMillis()
        ): Boolean {
            if (live?.status == RelayConnectionStatus.CONNECTED) return false
            if (health == null) {
                return live?.status == RelayConnectionStatus.ERROR
            }
            val recentFailure = health.lastFailureAt > 0L && (nowMs - health.lastFailureAt) < DEAD_RELAY_COOLDOWN_MS
            if (health.consecutiveFailures >= 3 && (recentFailure || live?.status == RelayConnectionStatus.ERROR)) {
                return true
            }
            if (live?.status == RelayConnectionStatus.ERROR && health.consecutiveFailures >= 2) {
                return true
            }
            if (health.failureCount >= 4 && health.successCount == 0 && live?.status != RelayConnectionStatus.CONNECTING) {
                return true
            }
            return false
        }

        internal fun decayedPenalty(consecutiveFailures: Int, lastFailureAt: Long, nowMs: Long): Double {
            if (consecutiveFailures <= 0 || lastFailureAt <= 0L) return 0.0
            val elapsed = (nowMs - lastFailureAt).coerceAtLeast(0L)
            val halfLives = elapsed.toDouble() / TRANSIENT_DECAY_HALF_LIFE_MS.toDouble()
            val decayFactor = Math.pow(0.5, halfLives.coerceAtMost(10.0))
            return (consecutiveFailures * 18.0) * decayFactor
        }

        internal fun scoreRelay(
            url: String,
            health: RelayHealthEntity?,
            live: RelayStatusInfo?,
            operation: NetworkOperationType,
            hasActiveSigner: Boolean,
            nowMs: Long,
            isTargetAuthorOutbox: Boolean = false,
            isMentionedInbox: Boolean = false,
            isExplicitHint: Boolean = false
        ): Double {
            var score = 100.0
            if (live?.status == RelayConnectionStatus.CONNECTED) score += 35.0
            else if (live?.status == RelayConnectionStatus.ERROR) score -= 45.0

            // Role-targeted affinity (NIP-65 outbox / inbox / explicit naddr hints)
            if (isTargetAuthorOutbox &&
                (operation == NetworkOperationType.RESOLVE_MANIFEST ||
                    operation == NetworkOperationType.FETCH_METADATA ||
                    operation == NetworkOperationType.DISCOVER_NSITES ||
                    operation == NetworkOperationType.PUBLISH_MANIFEST ||
                    operation == NetworkOperationType.PUBLISH_NOTE)
            ) {
                score += 55.0
            }
            if (isMentionedInbox && operation == NetworkOperationType.PUBLISH_NOTE) {
                score += 52.0
            }
            if (isExplicitHint) {
                score += 25.0
            }

            // Domain specialization by operation
            val lowerUrl = url.lowercase()
            when (operation) {
                NetworkOperationType.DISCOVER_NSITES,
                NetworkOperationType.RESOLVE_MANIFEST -> {
                    if ("nsite" in lowerUrl) score += 28.0
                    if ("damus" in lowerUrl || "nos.lol" in lowerUrl || "primal" in lowerUrl) score += 12.0
                }
                NetworkOperationType.FETCH_METADATA -> {
                    if ("purplepag.es" in lowerUrl || "nostr.band" in lowerUrl || "primal" in lowerUrl) {
                        score += 35.0
                    }
                }
                NetworkOperationType.PUBLISH_MANIFEST -> {
                    if ("nsite" in lowerUrl) score += 28.0
                }
                NetworkOperationType.PUBLISH_NOTE -> {
                    if ("damus" in lowerUrl || "nos.lol" in lowerUrl || "primal" in lowerUrl || "offchain" in lowerUrl) {
                        score += 20.0
                    }
                    if ("purplepag.es" in lowerUrl) score -= 25.0 // Profile indexer, not general Kind 1
                }
                else -> Unit
            }

            if (health != null) {
                val nips = health.supportedNipsCsv
                    .split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .toSet()

                if (operation == NetworkOperationType.DISCOVER_NSITES && 50 in nips) {
                    score += 15.0 // NIP-50 search relay helps discovery
                }
                if (operation == NetworkOperationType.FETCH_METADATA && (11 in nips || 65 in nips)) {
                    score += 10.0
                }
                if (operation == NetworkOperationType.PUBLISH_MANIFEST && (33 in nips || 11 in nips)) {
                    score += 8.0
                }

                val total = health.successCount + health.failureCount
                if (total > 0) {
                    score += (health.successCount.toDouble() / total.toDouble()) * 30.0
                }
                score += (health.verifiedPublishCount.coerceAtMost(10) * 2.5)
                if (health.avgLatencyMs > 0L) {
                    score -= (health.avgLatencyMs.coerceAtMost(3000L) / 150.0)
                }
                if (health.paymentRequired &&
                    (operation == NetworkOperationType.PUBLISH_MANIFEST || operation == NetworkOperationType.PUBLISH_NOTE)
                ) {
                    score -= 80.0
                }
                if (health.authRequired && !hasActiveSigner) {
                    score -= 50.0
                }
                score -= decayedPenalty(health.consecutiveFailures, health.lastFailureAt, nowMs)
            }
            return score
        }

        internal fun scoreBlossomServer(
            health: BlossomServerHealthEntity?,
            operation: NetworkOperationType,
            nowMs: Long
        ): Double {
            var score = 100.0
            if (health != null) {
                score += (health.verifiedRetrievalCount.coerceAtMost(20) * 3.0)
                if (operation == NetworkOperationType.UPLOAD_BLOB) {
                    score += (health.uploadSuccessCount.coerceAtMost(20) * 2.5)
                }
                if (health.avgLatencyMs > 0L) {
                    score -= (health.avgLatencyMs.coerceAtMost(4000L) / 200.0)
                }
                score -= (health.hashMismatchCount * 45.0)
                score -= decayedPenalty(health.consecutiveFailures, health.lastFailureAt, nowMs)
            }
            return score
        }

        private fun blendLatency(prevMs: Long, sampleMs: Long): Long {
            if (sampleMs <= 0L) return prevMs
            return if (prevMs <= 0L) sampleMs else ((prevMs * 3L + sampleMs) / 4L)
        }
    }
}

