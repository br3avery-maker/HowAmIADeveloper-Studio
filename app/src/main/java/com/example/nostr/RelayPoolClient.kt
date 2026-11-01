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

    private val _relayStatuses = MutableStateFlow<Map<String, RelayStatusInfo>>(emptyMap())
    val relayStatuses: StateFlow<Map<String, RelayStatusInfo>> = _relayStatuses.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<Pair<String, NostrEvent>>(extraBufferCapacity = 768)
    val incomingEvents: SharedFlow<Pair<String, NostrEvent>> = _incomingEvents.asSharedFlow()

    fun syncRelays(urls: List<String>) {
        val normalizedSet = urls.map { normalizeRelayUrl(it) }.filter { it.isNotBlank() }.toSet()
        // Remove user-configured sockets no longer present (keep capped ephemeral outbox sockets)
        val existingKeys = sockets.keys.toList()
        for (existing in existingKeys) {
            if (existing !in normalizedSet && existing !in ephemeralOutboxUrls) {
                disconnectRelay(existing, removeFromMap = true)
            }
        }
        // Connect missing or disconnected sockets
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
            if (ephemeralOutboxUrls.size >= 6) return
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
            return
        }

        val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val rtt = System.currentTimeMillis() - (connectStartTimes[url] ?: System.currentTimeMillis())
                if (!isEphemeralOutbox) {
                    updateStatus(url) { prev ->
                        (prev ?: RelayStatusInfo(url = url)).copy(
                            status = RelayConnectionStatus.CONNECTED,
                            latencyMs = rtt.coerceAtLeast(1L),
                            lastMessage = "Connected (${rtt}ms)"
                        )
                    }
                }
                val suffix = url.hashCode().toUInt().toString(16)

                // 1. Priority subscription for tracked/featured creators (including user's pubkey)
                sendTrackedAuthorsSubscription(webSocket, "disc_tracked_$suffix")

                if (!isEphemeralOutbox) {
                    // 2. Split global subscriptions so Kind 15128 root spam never starves NIP-5D or Named Nsites
                    val filterNotes = JSONObject().apply {
                        put("kinds", JSONArray().put(NostrKinds.TEXT_NOTE))
                        put("limit", 20)
                    }
                    val filterNapplets = JSONObject().apply {
                        put(
                            "kinds",
                            JSONArray()
                                .put(NostrKinds.NAPPLET_NAMED)
                                .put(NostrKinds.NAPPLET_ROOT)
                                .put(NostrKinds.NAPPLET_SNAPSHOT)
                        )
                        put("limit", 100)
                    }
                    val filterNamedNsites = JSONObject().apply {
                        put("kinds", JSONArray().put(NostrKinds.NSITE_NAMED))
                        put("limit", 80)
                    }
                    val filterRootNsites = JSONObject().apply {
                        put("kinds", JSONArray().put(NostrKinds.NSITE_ROOT))
                        put("limit", 35)
                    }
                    val filterLegacyRootNsites = JSONObject().apply {
                        put("kinds", JSONArray().put(NostrKinds.NSITE_LEGACY_FILE))
                        put("#d", JSONArray().put("/index.html").put("index.html"))
                        put("limit", 25)
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
                    webSocket.send(
                        JSONArray().put("REQ").put("shell_nap_$suffix").put(filterNapplets).toString()
                    )
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
                if (!isEphemeralOutbox) {
                    updateStatus(url) { prev ->
                        (prev ?: RelayStatusInfo(url = url)).copy(
                            status = RelayConnectionStatus.ERROR,
                            lastMessage = t.localizedMessage?.take(60) ?: "Connection failed"
                        )
                    }
                }
            }
        })
        sockets[url] = ws
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

    /**
     * Registers `pubkeyHex` as a tracked creator and immediately queries all Nsites, Napplets,
     * Kind 0 metadata, NIP-65 outbox relays, and Blossom server lists across active + outbox relays.
     */
    fun trackAndQueryAuthor(pubkeyHex: String, extraOutboxRelays: List<String> = emptyList()) {
        val pk = pubkeyHex.trim().lowercase()
        if (pk.length != 64) return
        trackedAuthorPubkeys.add(pk)

        extraOutboxRelays.forEach { rawUrl ->
            val norm = normalizeRelayUrl(rawUrl)
            if (norm.startsWith("wss://") && !sockets.containsKey(norm)) {
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
        val filterNapplets = JSONObject().apply {
            put(
                "kinds",
                JSONArray()
                    .put(NostrKinds.NAPPLET_NAMED)
                    .put(NostrKinds.NAPPLET_ROOT)
                    .put(NostrKinds.NAPPLET_SNAPSHOT)
            )
            put("limit", 100)
        }
        val filterNamedNsites = JSONObject().apply {
            put("kinds", JSONArray().put(NostrKinds.NSITE_NAMED))
            put("limit", 80)
        }
        val filterLegacyRootNsites = JSONObject().apply {
            put("kinds", JSONArray().put(NostrKinds.NSITE_LEGACY_FILE))
            put("#d", JSONArray().put("/index.html").put("index.html"))
            put("limit", 25)
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
        val napMsg = JSONArray().put("REQ").put("disc_nap_$token").put(filterNapplets).toString()
        val nsiteMsg = JSONArray()
            .put("REQ")
            .put("disc_ns_$token")
            .put(filterNamedNsites)
            .put(filterLegacyRootNsites)
            .toString()
        val nip51Msg = JSONArray().put("REQ").put("disc_list_$token").put(filterNip51).toString()

        sockets.forEach { (url, ws) ->
            val st = _relayStatuses.value[url]?.status
            if (st == RelayConnectionStatus.CONNECTED || url in ephemeralOutboxUrls) {
                sendTrackedAuthorsSubscription(ws, "disc_tracked_$token")
                if (url !in ephemeralOutboxUrls) {
                    ws.send(napMsg)
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

    suspend fun queryEventsOnce(filterJson: JSONObject, timeoutMs: Long = 3200L): List<NostrEvent> {
        val activeSockets = sockets.values.toList()
        if (activeSockets.isEmpty()) return emptyList()

        val subId = "nap_q_${UUID.randomUUID().toString().take(8)}"
        val buffer = ConcurrentHashMap.newKeySet<NostrEvent>()
        val listHolder = java.util.Collections.synchronizedList(mutableListOf<NostrEvent>())
        queryBuffers[subId] = listHolder
        val signal = CompletableDeferred<Unit>()
        queryEoseSignals[subId] = signal

        val reqText = JSONArray().put("REQ").put(subId).put(filterJson).toString()
        activeSockets.forEach { ws -> ws.send(reqText) }

        withTimeoutOrNull(timeoutMs) { signal.await() }

        val closeText = JSONArray().put("CLOSE").put(subId).toString()
        activeSockets.forEach { ws -> ws.send(closeText) }
        queryEoseSignals.remove(subId)
        val collected = queryBuffers.remove(subId)?.toList().orEmpty()
        collected.forEach { buffer.add(it) }
        return buffer.distinctBy { it.id }.sortedByDescending { it.createdAt }
    }

    suspend fun publishEvent(event: NostrEvent, timeoutMs: Long = 4000L): RelayPublishSummary {
        val currentMap = _relayStatuses.value
        val targetUrls = currentMap.keys.toList()
        if (targetUrls.isEmpty()) {
            return RelayPublishSummary(event, emptyList())
        }

        targetUrls.forEach { url ->
            if (sockets[url] == null) {
                connectRelay(url)
            }
        }

        val payload = JSONArray().put("EVENT").put(event.toJsonObject()).toString()
        val deferredList = targetUrls.map { url ->
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
                        message = "Relay offline or queued failed"
                    )
                )
            }
            url to def
        }

        val receipts = deferredList.map { (url, def) ->
            val key = "$url|${event.id}"
            val result = withTimeoutOrNull(timeoutMs) { def.await() }
            pendingOkCallbacks.remove(key)
            result ?: RelayPublishReceipt(
                relayUrl = url,
                accepted = _relayStatuses.value[url]?.status == RelayConnectionStatus.CONNECTED,
                message = if (_relayStatuses.value[url]?.status == RelayConnectionStatus.CONNECTED) {
                    "Dispatched over active WebSocket"
                } else {
                    "Timed out waiting for OK"
                }
            )
        }
        return RelayPublishSummary(event = event, receipts = receipts)
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
                            message = msg.ifBlank { if (accepted) "OK" else "Rejected" }
                        )
                    )
                }
                "EOSE" -> {
                    val subId = arr.optString(1)
                    if (subId.startsWith("meta_") || subId.startsWith("disc_")) {
                        ws.send(JSONArray().put("CLOSE").put(subId).toString())
                    }
                    queryEoseSignals[subId]?.complete(Unit)
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
                        .put(NostrKinds.NAPPLET_NAMED)
                        .put(NostrKinds.NAPPLET_ROOT)
                        .put(NostrKinds.NAPPLET_SNAPSHOT)
                )
                put("limit", 100)
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
