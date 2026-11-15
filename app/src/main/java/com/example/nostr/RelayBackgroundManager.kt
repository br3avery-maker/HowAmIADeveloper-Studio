package com.example.nostr

import com.example.data.ShellDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

data class RelayBackgroundStatus(
    val isRunning: Boolean = false,
    val isCycling: Boolean = false,
    val cycleCount: Int = 0,
    val lastCycleAtMs: Long = 0L,
    val lastQueriedRelaysCount: Int = 0,
    val discoveredFromNip01Count: Int = 0,
    val adoptedRelaysCount: Int = 0,
    val droppedUnresponsiveCount: Int = 0,
    val averageLatencyMs: Long = 0L,
    val lastSummary: String = "Idle"
)

/**
 * In-process lifecycle-scoped background manager that:
 * 1. Actively queries connected relays via NIP-01 `REQ` for `Kind 10002` relay lists to discover
 *    author outbox/inbox relays across the network.
 * 2. Monitors per-relay latency and probes stale or errored configured relays.
 * 3. Drops dead/unresponsive relays and hung WebSocket connections cleanly without blocking UI.
 *
 * Uses [managerScope] bound to repository/ViewModel lifecycle (no persistent foreground services
 * or boot receivers, keeping Google Play Creator Track compliance intact).
 */
class RelayBackgroundManager(
    private val dao: ShellDao,
    private val networkEngine: AdaptiveNetworkEngine,
    private val relayPool: RelayPoolClient,
    private val managerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onRelayListDiscovered: (suspend (NostrEvent) -> Unit)? = null
) {
    private val _status = MutableStateFlow(RelayBackgroundStatus())
    val status: StateFlow<RelayBackgroundStatus> = _status.asStateFlow()

    private val cycleRunning = AtomicBoolean(false)
    private var loopJob: Job? = null

    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (loopJob?.isActive == true) return
        _status.update { it.copy(isRunning = true) }
        loopJob = managerScope.launch {
            while (isActive) {
                delay(intervalMs.coerceAtLeast(15_000L))
                runCatching { runOptimizationCycle() }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _status.update { it.copy(isRunning = false, isCycling = false) }
    }

    suspend fun runOptimizationCycle(
        extraCandidates: Collection<String> = emptyList(),
        queryTimeoutMs: Long = 2_400L,
        minKeepRelays: Int = 2,
        maxConfiguredCap: Int = 10
    ): RelayMaintenanceSummary = withContext(Dispatchers.IO) {
        if (!cycleRunning.compareAndSet(false, true)) {
            val currentConfigured = dao.getRelaysList()
            val liveMap = relayPool.relayStatuses.value
            val healthy = currentConfigured.count { liveMap[it.url]?.status == RelayConnectionStatus.CONNECTED }
            return@withContext RelayMaintenanceSummary(
                discoveredUrls = emptyList(),
                droppedUrls = emptyList(),
                closedSocketUrls = emptyList(),
                probedCount = 0,
                healthyCount = healthy
            )
        }

        _status.update { it.copy(isCycling = true) }
        try {
            networkEngine.withMaintenanceLock {
                val now = System.currentTimeMillis()
                val discoveredFromNip01 = LinkedHashSet<String>()
                extraCandidates.forEach { raw ->
                    val norm = RelayPoolClient.normalizeRelayUrl(raw)
                    if (AdaptiveNetworkEngine.isAutoDiscoverableRelayUrl(norm)) {
                        discoveredFromNip01.add(norm)
                    }
                }

                // 1. Monitor latency / probe stale or disconnected configured relays first so
                //    unresponsive nodes are accurately measured before pruning.
                val configured = dao.getRelaysList()
                val healthMap = dao.getAllRelayHealth().associateBy { it.url }
                val liveBefore = relayPool.relayStatuses.value
                var probed = 0

                val staleOrErrorRelays = configured.filter { entity ->
                    val liveStatus = liveBefore[entity.url]?.status
                    val lastProbed = healthMap[entity.url]?.lastProbedAt ?: 0L
                    val isError = liveStatus == RelayConnectionStatus.ERROR
                    val isUnconnectedStale = liveStatus != RelayConnectionStatus.CONNECTED && (now - lastProbed) > 60_000L
                    val isVeryStale = (now - lastProbed) > 120_000L
                    isError || isUnconnectedStale || isVeryStale
                }

                if (staleOrErrorRelays.isNotEmpty()) {
                    coroutineScope {
                        staleOrErrorRelays.map { entity ->
                            async {
                                val nip11 = networkEngine.probeRelayCapabilities(entity.url)
                                val nip11Ok = nip11 != null &&
                                    (nip11.supportedNipsCsv.isNotBlank() || nip11.softwareInfo.isNotBlank())
                                val liveAfterNip11 = relayPool.relayStatuses.value[entity.url]
                                if (!nip11Ok && liveAfterNip11?.status != RelayConnectionStatus.CONNECTED) {
                                    val startWs = System.currentTimeMillis()
                                    if (liveAfterNip11?.status != RelayConnectionStatus.CONNECTING) {
                                        relayPool.connectRelay(entity.url, isEphemeralOutbox = false)
                                    }
                                    val ok = relayPool.awaitSocketReady(entity.url, timeoutMs = 1_100L)
                                    val elapsed = (System.currentTimeMillis() - startWs).coerceAtLeast(1L)
                                    if (ok) {
                                        networkEngine.recordRelayOutcome(
                                            rawUrl = entity.url,
                                            success = true,
                                            verifiedPublish = false,
                                            latencyMs = elapsed,
                                            errorReason = null
                                        )
                                    } else {
                                        networkEngine.recordRelayOutcome(
                                            rawUrl = entity.url,
                                            success = false,
                                            verifiedPublish = false,
                                            latencyMs = null,
                                            errorReason = liveAfterNip11?.lastMessage?.ifBlank { null } ?: "Background probe timed out"
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    probed = staleOrErrorRelays.size
                }

                // 2. Query healthy relays via NIP-01 REQ for Kind 10002 relay lists to discover new relays
                val queryTargets = networkEngine.selectRelaysForOperation(
                    operation = NetworkOperationType.FETCH_METADATA,
                    limit = 5
                )
                val nip65Events = if (queryTargets.isNotEmpty()) {
                    val filter = JSONObject().apply {
                        put("kinds", JSONArray().put(NostrKinds.RELAY_LIST))
                        put("limit", 24)
                    }
                    relayPool.queryEventsOnce(
                        filterJson = filter,
                        timeoutMs = queryTimeoutMs,
                        specificRelays = queryTargets
                    )
                } else {
                    emptyList()
                }

                nip65Events.forEach { ev ->
                    if (ev.kind != NostrKinds.RELAY_LIST || !ev.verifySignature()) return@forEach
                    val extracted = extractValidRelayUrls(ev)
                    if (extracted.writeRelays.isNotEmpty() || extracted.readRelays.isNotEmpty()) {
                        networkEngine.recordAuthorNip65Relays(
                            pubkeyHex = ev.pubkey,
                            writeRelays = extracted.writeRelays,
                            readRelays = extracted.readRelays,
                            createdAtSec = ev.createdAt
                        )
                        discoveredFromNip01.addAll(extracted.writeRelays)
                        discoveredFromNip01.addAll(extracted.readRelays)
                        onRelayListDiscovered?.invoke(ev)
                    }
                }

                // 3. Drop dead/unresponsive relays and close hung/errored connections
                val (droppedUrls, closedSocketUrls) = networkEngine.dropDeadRelaysAndConnectionsLocked(
                    minKeepRelays = minKeepRelays,
                    nowMs = System.currentTimeMillis()
                )

                // 4. Adopt newly discovered healthy relays up to maxConfiguredCap
                val adoptedUrls = networkEngine.autoDiscoverAndAdoptRelaysLocked(
                    extraCandidates = discoveredFromNip01,
                    maxConfiguredCap = maxConfiguredCap
                )

                // Recompute metrics after optimization
                val refreshedRelays = dao.getRelaysList()
                val refreshedLive = relayPool.relayStatuses.value
                val refreshedHealth = dao.getAllRelayHealth().associateBy { it.url }

                val healthyCount = refreshedRelays.count { entity ->
                    refreshedLive[entity.url]?.status == RelayConnectionStatus.CONNECTED ||
                        !AdaptiveNetworkEngine.isRelayDead(
                            refreshedHealth[entity.url],
                            refreshedLive[entity.url],
                            System.currentTimeMillis()
                        )
                }

                val latencySamples = refreshedRelays.mapNotNull { entity ->
                    refreshedLive[entity.url]?.latencyMs?.takeIf { it > 0L }
                        ?: refreshedHealth[entity.url]?.avgLatencyMs?.takeIf { it > 0L }
                }
                val avgLatencyMs = if (latencySamples.isNotEmpty()) {
                    latencySamples.average().roundToLong()
                } else {
                    0L
                }

                val droppedTotal = (droppedUrls + closedSocketUrls).distinct().size
                val summaryText = buildString {
                    append("+${discoveredFromNip01.size} NIP-01 hints")
                    append(" • +${adoptedUrls.size} adopted")
                    append(" • -${droppedTotal} dropped")
                    if (avgLatencyMs > 0L) append(" • ${avgLatencyMs}ms avg")
                }

                _status.update { prev ->
                    prev.copy(
                        isCycling = false,
                        cycleCount = prev.cycleCount + 1,
                        lastCycleAtMs = now,
                        lastQueriedRelaysCount = queryTargets.size,
                        discoveredFromNip01Count = prev.discoveredFromNip01Count + discoveredFromNip01.size,
                        adoptedRelaysCount = prev.adoptedRelaysCount + adoptedUrls.size,
                        droppedUnresponsiveCount = prev.droppedUnresponsiveCount + droppedTotal,
                        averageLatencyMs = avgLatencyMs,
                        lastSummary = summaryText
                    )
                }

                RelayMaintenanceSummary(
                    discoveredUrls = adoptedUrls,
                    droppedUrls = droppedUrls,
                    closedSocketUrls = closedSocketUrls,
                    probedCount = probed,
                    healthyCount = healthyCount
                )
            }
        } finally {
            cycleRunning.set(false)
            _status.update { it.copy(isCycling = false) }
        }
    }

    private data class ExtractedNip65Urls(
        val writeRelays: List<String>,
        val readRelays: List<String>
    )

    private fun extractValidRelayUrls(event: NostrEvent): ExtractedNip65Urls {
        val writes = mutableListOf<String>()
        val reads = mutableListOf<String>()
        event.tags.forEach { tag ->
            if (tag.size < 2 || tag[0] != "r") return@forEach
            val norm = RelayPoolClient.normalizeRelayUrl(tag[1])
            val allowed = AdaptiveNetworkEngine.isAutoDiscoverableRelayUrl(norm) ||
                norm.startsWith("ws://127.0.0.1")
            if (!allowed) return@forEach
            when (tag.getOrNull(2)?.trim()?.lowercase().orEmpty()) {
                "" -> {
                    writes.add(norm)
                    reads.add(norm)
                }
                "write" -> writes.add(norm)
                "read" -> reads.add(norm)
            }
        }
        return ExtractedNip65Urls(
            writeRelays = writes.distinct(),
            readRelays = reads.distinct()
        )
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 75_000L
    }
}
