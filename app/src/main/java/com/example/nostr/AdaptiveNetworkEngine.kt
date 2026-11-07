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

enum class NetworkOperationType {
    DISCOVER_NSITES,
    RESOLVE_MANIFEST,
    PUBLISH_MANIFEST,
    UPLOAD_BLOB,
    RETRIEVE_BLOB
}

/**
 * Shared Adaptive Relay + Blossom Networking Engine.
 *
 * - Discovers and probes Nostr relays (NIP-11, NIP-65) and Blossom servers (BUD-01/02, Kind 10063).
 * - Scores and selects destinations per operation using latency, capability flags, and
 *   time-decayed failure telemetry so temporary glitches never become permanent bans.
 * - Distinguishes `CONNECTED`, `ACCEPTED`, `PERSISTED`, and `VERIFIED_RETRIEVAL`.
 * - Enforces Blossom upload verification (requiring >= 1 verified server) and mirrors blobs
 *   to backup servers for content redundancy and recovery.
 */
class AdaptiveNetworkEngine(
    private val dao: ShellDao,
    val relayPool: RelayPoolClient,
    val blossomClient: BlossomClient,
    private val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
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
        relays.forEach { r ->
            probeRelayCapabilities(r.url)
        }
        val servers = dao.getBlossomServersList()
        servers.forEach { s ->
            probeBlossomCapabilities(s.serverUrl)
        }
    }

    suspend fun probeRelayCapabilities(rawUrl: String): RelayHealthEntity? = withContext(Dispatchers.IO) {
        val url = RelayPoolClient.normalizeRelayUrl(rawUrl)
        if (url.isBlank()) return@withContext null
        val nip11 = relayPool.probeRelayNip11(url)
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
     * Ranks relays for the requested operation. Temporary failures decay over a 2-minute half-life
     * so transient network drops never permanently exclude a relay.
     */
    suspend fun selectRelaysForOperation(
        operation: NetworkOperationType,
        extraCandidateRelays: List<String> = emptyList(),
        hasActiveSigner: Boolean = true
    ): List<String> = withContext(Dispatchers.IO) {
        val configured: List<RelayEntity> = dao.getRelaysList()
        val healthMap = dao.getAllRelayHealth().associateBy { it.url }
        val liveMap = relayPool.relayStatuses.value
        val now = System.currentTimeMillis()

        val candidates = buildList {
            configured.forEach { entity ->
                val allowed = when (operation) {
                    NetworkOperationType.PUBLISH_MANIFEST -> entity.writeEnabled
                    else -> entity.readEnabled
                }
                if (allowed) add(entity.url)
            }
            extraCandidateRelays.map { RelayPoolClient.normalizeRelayUrl(it) }
                .filter { it.isNotBlank() }
                .forEach { add(it) }
        }.distinct()

        candidates.sortedByDescending { url ->
            scoreRelay(
                url = url,
                health = healthMap[url],
                live = liveMap[url],
                operation = operation,
                hasActiveSigner = hasActiveSigner,
                nowMs = now
            )
        }
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
     * Publishes a signed Nostr event across adaptively ranked write relays, automatically
     * handling NIP-42 `AUTH` challenges when `signEvent` is available, and performing
     * round-trip `REQ` verification to distinguish `ACCEPTED` from `VERIFIED_RETRIEVAL`.
     */
    suspend fun publishEventAdaptively(
        event: NostrEvent,
        extraOutboxRelays: List<String> = emptyList(),
        verifyRetrieval: Boolean = true,
        signAuthIfChallenged: (suspend (UnsignedNostrEvent) -> NostrEvent)? = null
    ): RelayPublishSummary = withContext(Dispatchers.IO) {
        val rankedRelays = selectRelaysForOperation(
            operation = NetworkOperationType.PUBLISH_MANIFEST,
            extraCandidateRelays = extraOutboxRelays,
            hasActiveSigner = signAuthIfChallenged != null
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
     * - Requires at least **1 verified server** (`uploadedLive && verifiedRetrieval`, or `uploadedLive` if server is write-only CDN that passes HTTP 2xx + SHA check) before allowing manifest publication.
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
                // Keep accepted upload as fallback while looking for full GET verification
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

        // Mirror to backup servers concurrently for content redundancy
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

        private const val TRANSIENT_DECAY_HALF_LIFE_MS = 120_000L // 2 minutes

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
            nowMs: Long
        ): Double {
            var score = 100.0
            if (live?.status == RelayConnectionStatus.CONNECTED) score += 35.0
            if (url.contains("nsite", ignoreCase = true) &&
                (operation == NetworkOperationType.DISCOVER_NSITES || operation == NetworkOperationType.PUBLISH_MANIFEST)
            ) {
                score += 20.0
            }
            if (health != null) {
                val total = health.successCount + health.failureCount
                if (total > 0) {
                    score += (health.successCount.toDouble() / total.toDouble()) * 30.0
                }
                score += (health.verifiedPublishCount.coerceAtMost(10) * 2.5)
                if (health.avgLatencyMs > 0L) {
                    score -= (health.avgLatencyMs.coerceAtMost(3000L) / 150.0)
                }
                if (health.paymentRequired && operation == NetworkOperationType.PUBLISH_MANIFEST) {
                    score -= 60.0
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
                // Hash mismatches are integrity violations and receive a strong penalty
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
