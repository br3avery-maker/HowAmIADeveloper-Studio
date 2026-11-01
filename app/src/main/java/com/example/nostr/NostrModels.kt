package com.example.nostr

import org.json.JSONArray
import org.json.JSONObject

object NostrKinds {
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val NAPPLET_SNAPSHOT = 5129
    const val RELAY_LIST = 10002
    const val NIP51_BOOKMARKS = 10003
    const val USER_BLOSSOM_SERVERS = 10063
    const val NSITE_ROOT = 15128
    const val NAPPLET_ROOT = 15129
    const val BLOSSOM_AUTH = 24242
    const val NIP51_CATEGORIZED_LIST = 30001
    const val NIP51_BOOKMARK_SET = 30003
    const val NSITE_LEGACY_FILE = 34128
    const val NSITE_NAMED = 35128
    const val NAPPLET_NAMED = 35129
}

data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        val tagsArr = JSONArray()
        tags.forEach { row ->
            val inner = JSONArray()
            row.forEach { inner.put(it) }
            tagsArr.put(inner)
        }
        put("tags", tagsArr)
        put("content", content)
        put("sig", sig)
    }

    fun toJsonString(): String = toJsonObject().toString()

    companion object {
        fun computeCanonicalCommitment(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String
        ): String {
            return buildString {
                append("[0,")
                append(escapeJsonString(pubkey.lowercase()))
                append(',')
                append(createdAt)
                append(',')
                append(kind)
                append(",[")
                tags.forEachIndexed { rIdx, row ->
                    if (rIdx > 0) append(',')
                    append('[')
                    row.forEachIndexed { cIdx, cell ->
                        if (cIdx > 0) append(',')
                        append(escapeJsonString(cell))
                    }
                    append(']')
                }
                append("],")
                append(escapeJsonString(content))
                append(']')
            }
        }

        fun createAndSign(
            privKeyHex: String,
            kind: Int,
            content: String,
            tags: List<List<String>> = emptyList(),
            createdAt: Long = System.currentTimeMillis() / 1000L
        ): NostrEvent {
            val pubKeyHex = NostrCrypto.derivePublicKeyHex(privKeyHex)
            val commitment = computeCanonicalCommitment(pubKeyHex, createdAt, kind, tags, content)
            val eventId = NostrCrypto.sha256Hex(commitment)
            val sig = NostrCrypto.signSchnorrHex(eventId, privKeyHex)
            return NostrEvent(
                id = eventId,
                pubkey = pubKeyHex,
                createdAt = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                sig = sig
            )
        }

        fun fromJson(json: JSONObject): NostrEvent? = runCatching {
            val tagsJson = json.optJSONArray("tags") ?: JSONArray()
            val tags = buildList {
                for (i in 0 until tagsJson.length()) {
                    val rowArr = tagsJson.optJSONArray(i) ?: continue
                    val row = buildList {
                        for (j in 0 until rowArr.length()) {
                            add(rowArr.optString(j, ""))
                        }
                    }
                    add(row)
                }
            }
            NostrEvent(
                id = json.getString("id"),
                pubkey = json.getString("pubkey"),
                createdAt = json.getLong("created_at"),
                kind = json.getInt("kind"),
                tags = tags,
                content = json.optString("content", ""),
                sig = json.getString("sig")
            )
        }.getOrNull()

        private fun escapeJsonString(s: String): String = buildString {
            append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            append("\\u%04x".format(ch.code))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
            append('"')
        }
    }
}

enum class RelayConnectionStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

data class RelayStatusInfo(
    val url: String,
    val status: RelayConnectionStatus = RelayConnectionStatus.DISCONNECTED,
    val latencyMs: Long? = null,
    val eventsReceived: Int = 0,
    val lastMessage: String? = null
)

data class RelayPublishReceipt(
    val relayUrl: String,
    val accepted: Boolean,
    val message: String
)

data class RelayPublishSummary(
    val event: NostrEvent,
    val receipts: List<RelayPublishReceipt>
) {
    val acceptedCount: Int get() = receipts.count { it.accepted }
}

enum class SignDecision {
    ALLOW_ONCE,
    ALLOW_ALWAYS_KIND,
    REJECT
}

data class PendingSignRequest(
    val requestId: String,
    val appletId: String,
    val appletTitle: String,
    val protocolLabel: String, // "NIP-5D NAP-SIGNER", "NIP-07 window.nostr", or "Built-in Shell"
    val method: String, // "signEvent", "nip04Encrypt", "nip04Decrypt"
    val eventKind: Int,
    val contentPreview: String,
    val rawPayloadJson: String,
    val activePubkeyHex: String,
    val activeNpub: String,
    val activeDisplayName: String
)
