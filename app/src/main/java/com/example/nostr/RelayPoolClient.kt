package com.example.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RelayPoolClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val ephemeralOutboxUrls = ConcurrentHashMap.newKeySet<String>()
    private val trackedAuthorPubkeys = ConcurrentHashMap.newKeySet<String>().apply {
        NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX
            .takeIf { it.length == 64 }
            ?.let { add(it) }
    }
    private val connectStartTimes = ConcurrentHashMap<String, Long>()
    private val pendingOkCallbacks = ConcurrentHashMap<String, CompletableDeferred<RelayPublishReceipt>>()
    private val queryBuffers = ConcurrentHashMap<String, MutableList<NostrEvent>>()
    private val queryEoseSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val queryTargetCounts = ConcurrentHashMap<String, Int>()
    private val queryEoseCounts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private val _relayStatuses = MutableStateFlow<Map<String, RelayStatusInfo>>(emptyMap())
    val relayStatuses: StateFlow<Map<String, RelayStatusInfo>> = _relayStatuses.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<Pair<String, NostrEvent>>(extraBufferCapacity = 768)
    val incomingEvents: SharedFlow<Pair<String, NostrEvent>> = _incomingEvents.asSharedFlow()

    var healthObserver: ((url: String, success: Boolean, latencyMs: Long?, errorReason: String?) -> Unit)? = null

    fun syncRelays(urls: List<String>) {
        val normalizedSet = urls.map { normalizeRelayUrl(it) }.filter { it.isNotBlank() }.toSet()
        val existingKeys = sockets.keys.toList()
        for (existing in existingKeys) {
            if (existing !in normalizedSet && existing !in ephemeralOutboxUrls) {
                disconnectRelay(existing, removeFromMap = true)
            }
        }
        for (url in normalizedSet) {
            val current = _relayStatuses.value[url]?.status
            if (current != RelayConnectionStatus.CONNECTED && current != RelayConnectionStatus.CONNECTING) {
                connectRelay(url)
            }
        }
    }

    fun connectRelay(rawUrl: String, isEphemeralOutbox: Boolean = false) {
        val url = normalizeRelayUrl(rawUrl)
        if (url.isBlank()) return
        if (isEphemeralOutbox) {
            if (sockets.containsKey(url)) return
            if (ephemeralOutboxUrls.size >= 8) return
            ephemeralOutboxUrls.add(url)
        }
        sockets.remove(url)?.cancel()
        connectStartTimes[url] = System.currentTimeMillis()
        if (!isEphemeralOutbox) {
            updateStatus(url) {
                RelayStatusInfo(
                    url = url,
                    status = RelayConnectionStatus.CONNECTING,
                    lastMessage = "Opening WebSocket…"
                )
            }
        }

        val request = runCatching {
            Request.Builder().url(url).build()
        }.getOrElse { err ->
            if (!isEphemeralOutbox) {
                updateStatus(url) {
                    RelayStatusInfo(
                        url = url,
                        status = RelayConnectionStatus.ERROR,
                        lastMessage = err.message ?: "Invalid relay URL"
                    )
                }
            }
            healthObserver?.invoke(url, false, null, err.message ?: "Invalid URL")
            return
        }

        val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val rtt = (System.currentTimeMillis() - (connectStartTimes[url] ?: System.currentTimeMillis()))
                    .coerceAtLeast(1L)
                if (!isEphemeralOutbox) {
                    updateStatus(url) { prev ->
                        (prev ?: RelayStatusInfo(url = url)).copy(
                            status = RelayConnectionStatus.CONNECTED,
                            latencyMs = rtt,
                            lastMessage = "Connected (${rtt}ms)"
                        )
                    }
                }
                healthObserver?.invoke(url, true, rtt, null)
                val suffix = url.hashCode().toUInt().toString(16)

                // 1. Priority subscription for tracked/featured Nsite publishers
                sendTrackedAuthorsSubscription(webSocket, "disc_tracked_$suffix")

                if (!isEphemeralOutbox) {
                    // 2. NIP-5A Nsite discovery subscriptions (Named 35128, Root 15128, Legacy 34128)
                    val filterNamedNsites = JSONObject().apply {
                        put("kinds", JSONArray().put(NostrKinds.NSITE_NAMED))
                        put("limit", 90)
                    }
                    val filterRootNsites = JSONObject().apply {
                        put("kinds", JSONArray().put(NostrKinds.NSITE_ROOT))
                        put("limit", 45)
                    }
                    val filterLegacyRootNsites = JSONObject().apply {
                        put("kinds", JSONArray().put(NostrKinds.NSITE_LEGACY_FILE))
                        put("#d", JSONArray().put("/index.html").put("index.html"))
                        put("limit", 30)
                    }
                    val filterNip51Lists = JSONObject().apply {
                        put(
                            "kinds",
                            JSONArray()
                                .put(NostrKinds.NIP51_BOOKMARK_SET)
                                .put(NostrKinds.NIP51_CATEGORIZED_LIST)
                        )
                        put("limit", 40)
                    }
                    val filterNotes = JSONObject().apply {
                        put("kinds", JSONArray().put(NostrKinds.TEXT_NOTE))
                        put("limit", 20)
                    }
                    webSocket.send(
                        JSONArray().put("REQ").put("shell_nsite_$suffix")
                            .put(filterNamedNsites)
                            .put(filterRootNsites)
                            .put(filterLegacyRootNsites)
                            .toString()
                    )
                    webSocket.send(
                        JSONArray().put("REQ").put("shell_nip51_$suffix").put(filterNip51Lists).toString()
                    )
                    webSocket.send(
                        JSONArray().put("REQ").put("shell_notes_$suffix").put(filterNotes).toString()
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRelayMessage(url, webSocket, text, isEphemeralOutbox)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                sockets.remove(url)
                if (!isEphemeralOutbox) {
                    updateStatus(url) { prev ->
                        (prev ?: RelayStatusInfo(url = url)).copy(
                            status = RelayConnectionStatus.DISCONNECTED,
                            lastMessage = reason.ifBlank { "Closed ($code)" }
                        )
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.remove(url)
                val msg = t.localizedMessage?.take(60) ?: "Connection failed"
                if (!isEphemeralOutbox) {
                    updateStatus(url) { prev ->
                        (prev ?: RelayStatusInfo(url = url)).copy(
                            status = RelayConnectionStatus.ERROR,
                            lastMessage = msg
                        )
                    }
                }
                healthObserver?.invoke(url, false, null, msg)
            }
        })
        sockets[url] = ws
    }

    /**
     * Fetches NIP-11 Relay Information Document (`Accept: application/nostr+json`) to inspect
     * supported NIPs, auth/payment policies, max message limits, and server software.
     */
    suspend fun probeRelayNip11(rawUrl: String): RelayNip11Info? = withContext(Dispatchers.IO) {
        val wsUrl = normalizeRelayUrl(rawUrl)
        if (wsUrl.isBlank()) return@withContext null
        val httpUrl = when {
            wsUrl.startsWith("wss://") -> "https://" + wsUrl.removePrefix("wss://")
            wsUrl.startsWith("ws://") -> "http://" + wsUrl.removePrefix("ws://")
            else -> return@withContext null
        }
        val probeClient = okHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        runCatching {
            val req = Request.Builder()
                .url(httpUrl)
                .header("Accept", "application/nostr+json")
                .get()
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                val json = JSONObject(body)
                val nipsArr = json.optJSONArray("supported_nips") ?: JSONArray()
                val nips = buildSet {
                    for (i in 0 until nipsArr.length()) {
                        val v = nipsArr.optInt(i, -1)
                        if (v >= 0) add(v)
                    }
                }
                val lim = json.optJSONObject("limitation")
                RelayNip11Info(
                    url = wsUrl,
                    name = json.optString("name"),
                    software = json.optString("software"),
                    supportedNips = nips,
                    authRequired = lim?.optBoolean("auth_required", false) ?: false,
                    paymentRequired = lim?.optBoolean("payment_required", false) ?: false,
                    maxMessageLength = lim?.optInt("max_message_length", 0) ?: 0
                )
            }
        }.getOrNull()
    }

    /**
     * Responds to a NIP-42 `AUTH` challenge on `rawUrl` with a signed Kind 22242 event.
     */
    suspend fun authenticateRelayNip42(
        rawUrl: String,
        signEvent: suspend (UnsignedNostrEvent) -> NostrEvent,
        pubkeyHex: String,
        timeoutMs: Long = 3500L
    ): Boolean {
        val url = normalizeRelayUrl(rawUrl)
        val challenge = _relayStatuses.value[url]?.authChallenge ?: return false
        val ws = sockets[url] ?: return false
        val unsigned = UnsignedNostrEvent(
            pubkey = pubkeyHex,
            kind = NostrKinds.NIP42_AUTH,
            tags = listOf(
                listOf("relay", url),
                listOf("challenge", challenge)
            ),
            content = ""
        )
        val signed = runCatching { signEvent(unsigned) }.getOrNull() ?: return false
        val key = "$url|${signed.id}"
        val def = CompletableDeferred<RelayPublishReceipt>()
        pendingOkCallbacks[key] = def
        val sent = ws.send(JSONArray().put("AUTH").put(signed.toJsonObject()).toString())
        if (!sent) {
            pendingOkCallbacks.remove(key)
            return false
        }
        val receipt = withTimeoutOrNull(timeoutMs) { def.await() }
        pendingOkCallbacks.remove(key)
        val ok = receipt?.accepted == true
        if (ok) {
            updateStatus(url) { prev ->
                (prev ?: RelayStatusInfo(url = url)).copy(
                    authenticated = true,
                    lastMessage = "NIP-42 Authenticated"
                )
            }
        }
        return ok
    }

    private fun sendTrackedAuthorsSubscription(ws: WebSocket, subId: String) {
        val authors = trackedAuthorPubkeys.filter { it.length == 64 }.distinct()
        if (authors.isEmpty()) return
        val (manifestsFilter, metaAndOutboxFilter) = buildAuthorBundleFilters(authors)
        val msg = JSONArray()
            .put("REQ")
            .put(subId)
            .put(manifestsFilter)
            .put(metaAndOutboxFilter)
            .toString()
        ws.send(msg)
    }

    fun disconnectRelay(rawUrl: String, removeFromMap: Boolean = false) {
        val url = normalizeRelayUrl(rawUrl)
        ephemeralOutboxUrls.remove(url)
        sockets.remove(url)?.close(1000, "Removed by user")
        if (removeFromMap) {
            _relayStatuses.update { it - url }
        } else {
            updateStatus(url) { prev ->
                (prev ?: RelayStatusInfo(url = url)).copy(
                    status = RelayConnectionStatus.DISCONNECTED,
                    lastMessage = "Disconnected"
                )
            }
        }
    }

    fun requestAuthorMetadata(pubkeyHex: String) {
        requestAuthorsMetadata(listOf(pubkeyHex))
    }

    fun requestAuthorsMetadata(pubkeys: Collection<String>) {
        val clean = pubkeys.map { it.trim().lowercase() }
            .filter { it.length == 64 && it.all { ch -> ch.isDigit() || ch in 'a'..'f' } }
            .distinct()
        if (clean.isEmpty()) return

        clean.chunked(40).forEach { chunk ->
            val subId = "meta_${UUID.randomUUID().toString().take(8)}"
            val authorsArr = JSONArray()
            chunk.forEach { authorsArr.put(it) }
            val filter = JSONObject().apply {
                put("kinds", JSONArray().put(NostrKinds.METADATA))
                put("authors", authorsArr)
                put("limit", chunk.size * 2)
            }
            val msg = JSONArray().put("REQ").put(subId).put(filter).toString()
            sockets.values.forEach { it.send(msg) }
        }
    }

    fun trackAndQueryAuthor(pubkeyHex: String, extraOutboxRelays: List<String> = emptyList()) {
        val pk = pubkeyHex.trim().lowercase()
        if (pk.length != 64) return
        trackedAuthorPubkeys.add(pk)

        extraOutboxRelays.forEach { rawUrl ->
            val norm = normalizeRelayUrl(rawUrl)
            if ((norm.startsWith("wss://") || norm.startsWith("ws://")) && !sockets.containsKey(norm)) {
                connectRelay(norm, isEphemeralOutbox = true)
            }
        }

        val subId = "disc_author_${UUID.randomUUID().toString().take(8)}"
        val (manifestsFilter, metaAndOutboxFilter) = buildAuthorBundleFilters(listOf(pk))
        val msg = JSONArray()
            .put("REQ")
            .put(subId)
            .put(manifestsFilter)
            .put(metaAndOutboxFilter)
            .toString()
        sockets.values.forEach { ws -> ws.send(msg) }
    }

    fun refreshDiscoveryCatalog() {
        val token = UUID.randomUUID().toString().take(6)
        val filterNamedNsites = JSONObject().apply {
            put("kinds", JSONArray().put(NostrKinds.NSITE_NAMED))
            put("limit", 90)
        }
        val filterRootNsites = JSONObject().apply {
            put("kinds", JSONArray().put(NostrKinds.NSITE_ROOT))
            put("limit", 45)
        }
        val filterLegacyRootNsites = JSONObject().apply {
            put("kinds", JSONArray().put(NostrKinds.NSITE_LEGACY_FILE))
            put("#d", JSONArray().put("/index.html").put("index.html"))
            put("limit", 30)
        }
        val filterNip51 = JSONObject().apply {
            put(
                "kinds",
                JSONArray()
                    .put(NostrKinds.NIP51_BOOKMARK_SET)
                    .put(NostrKinds.NIP51_CATEGORIZED_LIST)
            )
            put("limit", 40)
        }
        val nsiteMsg = JSONArray()
            .put("REQ")
            .put("disc_ns_$token")
            .put(filterNamedNsites)
            .put(filterRootNsites)
            .put(filterLegacyRootNsites)
            .toString()
        val nip51Msg = JSONArray().put("REQ").put("disc_list_$token").put(filterNip51).toString()

        sockets.forEach { (url, ws) ->
            val st = _relayStatuses.value[url]?.status
            if (st == RelayConnectionStatus.CONNECTED || url in ephemeralOutboxUrls) {
                sendTrackedAuthorsSubscription(ws, "disc_tracked_$token")
                if (url !in ephemeralOutboxUrls) {
                    ws.send(nsiteMsg)
                    ws.send(nip51Msg)
                }
            } else if (st != RelayConnectionStatus.CONNECTING) {
                connectRelay(url)
            }
        }
    }

    fun requestManifestsByCoordinates(coordinates: List<String>) {
        if (coordinates.isEmpty()) return
        val kinds = HashSet<Int>()
        val authors = HashSet<String>()
        val dTags = HashSet<String>()
        for (coord in coordinates.take(25)) {
            val parts = coord.split(':')
            if (parts.size >= 2) {
                val k = parts[0].toIntOrNull() ?: continue
                val pk = parts[1].trim()
                if (pk.length == 64) {
                    kinds.add(k)
                    authors.add(pk)
                    if (parts.size >= 3 && parts[2].isNotBlank() && parts[2] != "root") {
                        dTags.add(parts[2])
                    }
                }
            }
        }
        if (kinds.isEmpty() || authors.isEmpty()) return
        val subId = "disc_coord_${UUID.randomUUID().toString().take(6)}"
        val filter = JSONObject().apply {
            val kArr = JSONArray()
            kinds.forEach { kArr.put(it) }
            put("kinds", kArr)
            val aArr = JSONArray()
            authors.forEach { aArr.put(it) }
            put("authors", aArr)
            if (dTags.isNotEmpty()) {
                val dArr = JSONArray()
                dTags.forEach { dArr.put(it) }
                put("#d", dArr)
            }
            put("limit", 40)
        }
        val reqMsg = JSONArray().put("REQ").put(subId).put(filter).toString()
        sockets.values.forEach { ws -> ws.send(reqMsg) }
    }

    suspend fun queryEventsOnce(
        filterJson: JSONObject,
        timeoutMs: Long = 3200L,
        specificRelays: List<String> = emptyList()
    ): List<NostrEvent> {
        specificRelays.forEach { raw ->
            val norm = normalizeRelayUrl(raw)
            if (norm.isNotBlank() && !sockets.containsKey(norm)) {
                connectRelay(norm, isEphemeralOutbox = true)
            }
        }
        val targetSockets = if (specificRelays.isNotEmpty()) {
            val allowed = specificRelays.map { normalizeRelayUrl(it) }.toSet()
            sockets.filterKeys { it in allowed }.values.toList().ifEmpty { sockets.values.toList() }
        } else {
            sockets.values.toList()
        }
        if (targetSockets.isEmpty()) return emptyList()

        val subId = "ns_q_${UUID.randomUUID().toString().take(8)}"
        val listHolder = java.util.Collections.synchronizedList(mutableListOf<NostrEvent>())
        queryBuffers[subId] = listHolder
        val signal = CompletableDeferred<Unit>()
        queryEoseSignals[subId] = signal
        queryTargetCounts[subId] = targetSockets.size
        queryEoseCounts[subId] = java.util.concurrent.atomic.AtomicInteger(0)

        val reqText = JSONArray().put("REQ").put(subId).put(filterJson).toString()
        var sentCount = 0
        targetSockets.forEach { ws ->
            if (ws.send(reqText)) sentCount++
        }
        if (sentCount == 0) {
            queryEoseSignals.remove(subId)
            queryTargetCounts.remove(subId)
            queryEoseCounts.remove(subId)
            queryBuffers.remove(subId)
            return emptyList()
        }
        queryTargetCounts[subId] = sentCount

        withTimeoutOrNull(timeoutMs) { signal.await() }

        val closeText = JSONArray().put("CLOSE").put(subId).toString()
        targetSockets.forEach { ws -> ws.send(closeText) }
        queryEoseSignals.remove(subId)
        queryTargetCounts.remove(subId)
        queryEoseCounts.remove(subId)
        val collected = queryBuffers.remove(subId)?.toList().orEmpty()
        return collected
            .filter { it.verifySignature() }
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Publishes a signed event to target relays and optionally performs a round-trip `REQ`
     * verification to prove `VERIFIED_RETRIEVAL` vs simple `ACCEPTED`.
     */
    suspend fun publishEvent(
        event: NostrEvent,
        timeoutMs: Long = 4000L,
        targetRelayUrls: List<String>? = null,
        verifyRetrieval: Boolean = false
    ): RelayPublishSummary {
        val urls = (targetRelayUrls ?: _relayStatuses.value.keys.toList())
            .map { normalizeRelayUrl(it) }
            .filter { it.isNotBlank() }
            .distinct()

        if (urls.isEmpty()) {
            return RelayPublishSummary(event, emptyList())
        }

        urls.forEach { url ->
            if (sockets[url] == null) {
                connectRelay(url)
            }
        }

        val payload = JSONArray().put("EVENT").put(event.toJsonObject()).toString()
        val deferredList = urls.map { url ->
            val key = "$url|${event.id}"
            val def = CompletableDeferred<RelayPublishReceipt>()
            pendingOkCallbacks[key] = def
            val ws = sockets[url]
            val sent = ws?.send(payload) == true
            if (!sent) {
                pendingOkCallbacks.remove(key)
                def.complete(
                    RelayPublishReceipt(
                        relayUrl = url,
                        accepted = false,
                        message = "Relay offline or WebSocket queue full",
                        stage = DeliveryVerificationStage.DISCONNECTED
                    )
                )
            }
            url to def
        }

        val initialReceipts = deferredList.map { (url, def) ->
            val key = "$url|${event.id}"
            val result = withTimeoutOrNull(timeoutMs) { def.await() }
            pendingOkCallbacks.remove(key)
            val finalReceipt = result ?: RelayPublishReceipt(
                relayUrl = url,
                accepted = false,
                message = "Timed out waiting for relay OK",
                stage = if (_relayStatuses.value[url]?.status == RelayConnectionStatus.CONNECTED) {
                    DeliveryVerificationStage.CONNECTED
                } else {
                    DeliveryVerificationStage.DISCONNECTED
                }
            )
            healthObserver?.invoke(
                url,
                finalReceipt.accepted,
                _relayStatuses.value[url]?.latencyMs,
                if (finalReceipt.accepted) null else finalReceipt.message
            )
            finalReceipt
        }

        if (!verifyRetrieval) {
            return RelayPublishSummary(event = event, receipts = initialReceipts)
        }

        // Round-trip verification: query back by event ID on each relay that accepted it
        val verifiedReceipts = initialReceipts.map { receipt ->
            if (!receipt.accepted) {
                receipt
            } else {
                val retrieved = verifySingleRelayHasEvent(receipt.relayUrl, event.id, 2200L)
                if (retrieved) {
                    receipt.copy(
                        stage = DeliveryVerificationStage.VERIFIED_RETRIEVAL,
                        message = "${receipt.message} • Verified retrieval"
                    )
                } else {
                    receipt.copy(
                        stage = DeliveryVerificationStage.PERSISTED,
                        message = "${receipt.message} • Accepted (pending index)"
                    )
                }
            }
        }
        return RelayPublishSummary(event = event, receipts = verifiedReceipts)
    }

    suspend fun verifySingleRelayHasEvent(
        rawRelayUrl: String,
        eventId: String,
        timeoutMs: Long = 2200L
    ): Boolean {
        val url = normalizeRelayUrl(rawRelayUrl)
        val ws = sockets[url] ?: return false
        val subId = "ver_${UUID.randomUUID().toString().take(8)}"
        val listHolder = java.util.Collections.synchronizedList(mutableListOf<NostrEvent>())
        queryBuffers[subId] = listHolder
        val signal = CompletableDeferred<Unit>()
        queryEoseSignals[subId] = signal
        queryTargetCounts[subId] = 1
        queryEoseCounts[subId] = java.util.concurrent.atomic.AtomicInteger(0)

        val filter = JSONObject().apply {
            put("ids", JSONArray().put(eventId))
            put("limit", 1)
        }
        val sent = ws.send(JSONArray().put("REQ").put(subId).put(filter).toString())
        if (!sent) {
            queryEoseSignals.remove(subId)
            queryTargetCounts.remove(subId)
            queryEoseCounts.remove(subId)
            queryBuffers.remove(subId)
            return false
        }

        withTimeoutOrNull(timeoutMs) { signal.await() }
        ws.send(JSONArray().put("CLOSE").put(subId).toString())
        queryEoseSignals.remove(subId)
        queryTargetCounts.remove(subId)
        queryEoseCounts.remove(subId)
        val events = queryBuffers.remove(subId)?.toList().orEmpty()
        return events.any { it.id.equals(eventId, ignoreCase = true) && it.verifySignature() }
    }

    /**
     * Opens a NIP-46 Nostr Connect listener (`Kind 24133` tagged with `#p = [clientPubkeyHex]`)
     * across the specified bunker relays.
     */
    fun ensureNip46Subscription(bunkerRelays: List<String>, clientPubkeyHex: String): String {
        val subId = "nip46_${clientPubkeyHex.take(10)}"
        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(NostrKinds.NIP46_NOSTR_CONNECT))
            put("#p", JSONArray().put(clientPubkeyHex.lowercase()))
            put("since", (System.currentTimeMillis() / 1000L) - 30L)
        }
        val reqMsg = JSONArray().put("REQ").put(subId).put(filter).toString()
        bunkerRelays.forEach { raw ->
            val norm = normalizeRelayUrl(raw)
            if (norm.isNotBlank()) {
                if (!sockets.containsKey(norm)) {
                    connectRelay(norm, isEphemeralOutbox = true)
                }
                sockets[norm]?.send(reqMsg)
            }
        }
        return subId
    }

    private fun handleRelayMessage(
        url: String,
        ws: WebSocket,
        rawText: String,
        isEphemeralOutbox: Boolean
    ) {
        runCatching {
            val arr = JSONArray(rawText)
            when (arr.optString(0)) {
                "EVENT" -> {
                    val subId = arr.optString(1)
                    val evObj = arr.optJSONObject(2) ?: return
                    val event = NostrEvent.fromJson(evObj) ?: return
                    if (!isEphemeralOutbox) {
                        updateStatus(url) { prev ->
                            (prev ?: RelayStatusInfo(url = url)).copy(
                                status = RelayConnectionStatus.CONNECTED,
                                eventsReceived = (prev?.eventsReceived ?: 0) + 1
                            )
                        }
                    }
                    queryBuffers[subId]?.add(event)
                    scope.launch { _incomingEvents.emit(url to event) }
                }
                "OK" -> {
                    val eventId = arr.optString(1)
                    val accepted = arr.optBoolean(2, false)
                    val msg = arr.optString(3, if (accepted) "Accepted" else "Rejected")
                    val key = "$url|$eventId"
                    pendingOkCallbacks.remove(key)?.complete(
                        RelayPublishReceipt(
                            relayUrl = url,
                            accepted = accepted,
                            message = msg.ifBlank { if (accepted) "OK" else "Rejected" },
                            stage = if (accepted) DeliveryVerificationStage.ACCEPTED else DeliveryVerificationStage.REJECTED
                        )
                    )
                }
                "AUTH" -> {
                    val challenge = arr.optString(1)
                    updateStatus(url) { prev ->
                        (prev ?: RelayStatusInfo(url = url)).copy(
                            authChallenge = challenge,
                            lastMessage = "NIP-42 Auth challenge received"
                        )
                    }
                }
                "EOSE" -> {
                    val subId = arr.optString(1)
                    if (subId.startsWith("meta_") || subId.startsWith("disc_")) {
                        ws.send(JSONArray().put("CLOSE").put(subId).toString())
                    }
                    val counter = queryEoseCounts[subId]
                    val target = queryTargetCounts[subId] ?: 1
                    if (counter == null || counter.incrementAndGet() >= target) {
                        queryEoseSignals[subId]?.complete(Unit)
                    }
                }
                "NOTICE" -> {
                    if (!isEphemeralOutbox) {
                        val notice = arr.optString(1)
                        updateStatus(url) { prev ->
                            (prev ?: RelayStatusInfo(url = url)).copy(lastMessage = "Notice: ${notice.take(50)}")
                        }
                    }
                }
            }
        }
    }

    private fun updateStatus(url: String, transform: (RelayStatusInfo?) -> RelayStatusInfo) {
        _relayStatuses.update { map ->
            map + (url to transform(map[url]))
        }
    }

    companion object {
        val LEGACY_INITIAL_RELAYS = setOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net"
        )

        val LEGACY_FIVE_RELAYS = setOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://relay.nostr.band",
            "wss://offchain.pub"
        )

        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://purplepag.es",
            "wss://relay.nsite.lol",
            "wss://relay.nostr.band",
            "wss://offchain.pub"
        )

        fun buildAuthorBundleFilters(authorPubkeysHex: List<String>): Pair<JSONObject, JSONObject> {
            val authorsArr = JSONArray()
            authorPubkeysHex.forEach { authorsArr.put(it.lowercase()) }

            val manifestsFilter = JSONObject().apply {
                put("authors", authorsArr)
                put(
                    "kinds",
                    JSONArray()
                        .put(NostrKinds.NSITE_NAMED)
                        .put(NostrKinds.NSITE_ROOT)
                        .put(NostrKinds.NSITE_LEGACY_FILE)
                )
                put("limit", 120)
            }

            val metaAndOutboxFilter = JSONObject().apply {
                put("authors", authorsArr)
                put(
                    "kinds",
                    JSONArray()
                        .put(NostrKinds.METADATA)
                        .put(NostrKinds.RELAY_LIST)
                        .put(NostrKinds.USER_BLOSSOM_SERVERS)
                        .put(NostrKinds.NIP51_BOOKMARK_SET)
                        .put(NostrKinds.NIP51_CATEGORIZED_LIST)
                )
                put("limit", 40)
            }
            return manifestsFilter to metaAndOutboxFilter
        }

        fun normalizeRelayUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""
            return when {
                trimmed.startsWith("wss://", ignoreCase = true) -> "wss://" + trimmed.substring(6).lowercase()
                trimmed.startsWith("ws://", ignoreCase = true) -> "ws://" + trimmed.substring(5).lowercase()
                trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8).lowercase()
                trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7).lowercase()
                else -> "wss://${trimmed.lowercase()}"
            }
        }
    }
}
