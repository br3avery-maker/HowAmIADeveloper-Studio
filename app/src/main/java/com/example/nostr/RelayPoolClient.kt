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
    private val connectStartTimes = ConcurrentHashMap<String, Long>()
    private val pendingOkCallbacks = ConcurrentHashMap<String, CompletableDeferred<RelayPublishReceipt>>()
    private val queryBuffers = ConcurrentHashMap<String, MutableList<NostrEvent>>()
    private val queryEoseSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val _relayStatuses = MutableStateFlow<Map<String, RelayStatusInfo>>(emptyMap())
    val relayStatuses: StateFlow<Map<String, RelayStatusInfo>> = _relayStatuses.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<Pair<String, NostrEvent>>(extraBufferCapacity = 512)
    val incomingEvents: SharedFlow<Pair<String, NostrEvent>> = _incomingEvents.asSharedFlow()

    fun syncRelays(urls: List<String>) {
        val normalizedSet = urls.map { normalizeRelayUrl(it) }.filter { it.isNotBlank() }.toSet()
        // Remove sockets no longer configured
        val existingKeys = sockets.keys.toList()
        for (existing in existingKeys) {
            if (existing !in normalizedSet) {
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

    fun connectRelay(rawUrl: String) {
        val url = normalizeRelayUrl(rawUrl)
        if (url.isBlank()) return
        sockets.remove(url)?.cancel()
        connectStartTimes[url] = System.currentTimeMillis()
        updateStatus(url) {
            RelayStatusInfo(
                url = url,
                status = RelayConnectionStatus.CONNECTING,
                lastMessage = "Opening WebSocket…"
            )
        }

        val request = runCatching {
            Request.Builder().url(url).build()
        }.getOrElse { err ->
            updateStatus(url) {
                RelayStatusInfo(
                    url = url,
                    status = RelayConnectionStatus.ERROR,
                    lastMessage = err.message ?: "Invalid relay URL"
                )
            }
            return
        }

        val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val rtt = System.currentTimeMillis() - (connectStartTimes[url] ?: System.currentTimeMillis())
                updateStatus(url) { prev ->
                    (prev ?: RelayStatusInfo(url = url)).copy(
                        status = RelayConnectionStatus.CONNECTED,
                        latencyMs = rtt.coerceAtLeast(1L),
                        lastMessage = "Connected (${rtt}ms)"
                    )
                }
                // Split subscriptions so Kind 15128 root spam never starves NIP-5D Napplets (35129/15129/5129)
                val suffix = url.hashCode().toUInt().toString(16)
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
                    put("limit", 60)
                }
                val filterRootNsites = JSONObject().apply {
                    put("kinds", JSONArray().put(NostrKinds.NSITE_ROOT))
                    put("limit", 35)
                }
                webSocket.send(
                    JSONArray().put("REQ").put("shell_nap_$suffix").put(filterNapplets).toString()
                )
                webSocket.send(
                    JSONArray().put("REQ").put("shell_nsite_$suffix").put(filterNamedNsites).put(filterRootNsites).toString()
                )
                webSocket.send(
                    JSONArray().put("REQ").put("shell_notes_$suffix").put(filterNotes).toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRelayMessage(url, webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                sockets.remove(url)
                updateStatus(url) { prev ->
                    (prev ?: RelayStatusInfo(url = url)).copy(
                        status = RelayConnectionStatus.DISCONNECTED,
                        lastMessage = reason.ifBlank { "Closed ($code)" }
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.remove(url)
                updateStatus(url) { prev ->
                    (prev ?: RelayStatusInfo(url = url)).copy(
                        status = RelayConnectionStatus.ERROR,
                        lastMessage = t.localizedMessage?.take(60) ?: "Connection failed"
                    )
                }
            }
        })
        sockets[url] = ws
    }

    fun disconnectRelay(rawUrl: String, removeFromMap: Boolean = false) {
        val url = normalizeRelayUrl(rawUrl)
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
        val subId = "meta_${pubkeyHex.take(10)}"
        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(0))
            put("authors", JSONArray().put(pubkeyHex))
            put("limit", 1)
        }
        val msg = JSONArray().put("REQ").put(subId).put(filter).toString()
        sockets.values.forEach { it.send(msg) }
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
            put("limit", 60)
        }
        val napMsg = JSONArray().put("REQ").put("disc_nap_$token").put(filterNapplets).toString()
        val nsiteMsg = JSONArray().put("REQ").put("disc_ns_$token").put(filterNamedNsites).toString()
        sockets.forEach { (url, ws) ->
            val st = _relayStatuses.value[url]?.status
            if (st == RelayConnectionStatus.CONNECTED) {
                ws.send(napMsg)
                ws.send(nsiteMsg)
            } else if (st != RelayConnectionStatus.CONNECTING) {
                connectRelay(url)
            }
        }
    }

    suspend fun queryEventsOnce(filterJson: JSONObject, timeoutMs: Long = 3200L): List<NostrEvent> {
        val activeEntries = sockets.entries.filter {
            _relayStatuses.value[it.key]?.status == RelayConnectionStatus.CONNECTED
        }
        if (activeEntries.isEmpty()) return emptyList()

        val subId = "nap_q_${UUID.randomUUID().toString().take(8)}"
        val buffer = ConcurrentHashMap.newKeySet<NostrEvent>()
        val listHolder = java.util.Collections.synchronizedList(mutableListOf<NostrEvent>())
        queryBuffers[subId] = listHolder
        val signal = CompletableDeferred<Unit>()
        queryEoseSignals[subId] = signal

        val reqText = JSONArray().put("REQ").put(subId).put(filterJson).toString()
        activeEntries.forEach { (_, ws) -> ws.send(reqText) }

        withTimeoutOrNull(timeoutMs) { signal.await() }

        val closeText = JSONArray().put("CLOSE").put(subId).toString()
        activeEntries.forEach { (_, ws) -> ws.send(closeText) }
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

        // Ensure disconnected relays attempt reconnect
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

    private fun handleRelayMessage(url: String, ws: WebSocket, rawText: String) {
        runCatching {
            val arr = JSONArray(rawText)
            when (arr.optString(0)) {
                "EVENT" -> {
                    val subId = arr.optString(1)
                    val evObj = arr.optJSONObject(2) ?: return
                    val event = NostrEvent.fromJson(evObj) ?: return
                    updateStatus(url) { prev ->
                        (prev ?: RelayStatusInfo(url = url)).copy(
                            status = RelayConnectionStatus.CONNECTED,
                            eventsReceived = (prev?.eventsReceived ?: 0) + 1
                        )
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
                    val notice = arr.optString(1)
                    updateStatus(url) { prev ->
                        (prev ?: RelayStatusInfo(url = url)).copy(lastMessage = "Notice: ${notice.take(50)}")
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

        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://relay.nostr.band",
            "wss://offchain.pub"
        )

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
