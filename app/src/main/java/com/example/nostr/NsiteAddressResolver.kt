package com.example.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Resolves manually entered NIP-5A Nsite addresses into canonical `(kind, authorPubkeyHex, dTag, relayHints, subPath)`
 * coordinates so users can paste `nsite.lol` gateway URLs, `naddr1...`, `npub1...`, `kind:pubkey:dTag`,
 * or `user@domain` NIP-05 identifiers directly into the Nsite Launcher or Forker.
 */
object NsiteAddressResolver {

    sealed interface ResolvedNsiteTarget {
        data class Nip5aCoordinate(
            val kind: Int,
            val authorPubkeyHex: String,
            val dTag: String,
            val relayHints: List<String> = emptyList(),
            val initialPath: String = "/index.html"
        ) : ResolvedNsiteTarget {
            val coordinate: String
                get() = "$kind:${authorPubkeyHex.lowercase()}:${if (kind == NostrKinds.NSITE_ROOT) "root" else dTag}"
        }

        data class AuthorAllSites(
            val authorPubkeyHex: String,
            val relayHints: List<String> = emptyList()
        ) : ResolvedNsiteTarget

        data class DirectHttpsSite(
            val url: String
        ) : ResolvedNsiteTarget
    }

    /**
     * Synchronously parses non-NIP05 addresses (`nsite` gateway URLs, `naddr1...`, `npub1...`, `35128:pk:dTag`).
     */
    fun parseSync(rawInput: String): ResolvedNsiteTarget? {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return null

        val withoutNostrPrefix = trimmed
            .removePrefix("nostr://")
            .removePrefix("nostr:")
            .removePrefix("NOSTR:")
            .trim()

        // 1. NIP-19 naddr1...
        if (withoutNostrPrefix.startsWith("naddr1", ignoreCase = true)) {
            val decoded = NostrCrypto.decodeNaddr(withoutNostrPrefix) ?: return null
            val kind = if (decoded.kind == NostrKinds.NSITE_ROOT || decoded.dTag.isBlank() || decoded.dTag == "root") {
                NostrKinds.NSITE_ROOT
            } else {
                NostrKinds.NSITE_NAMED
            }
            return ResolvedNsiteTarget.Nip5aCoordinate(
                kind = kind,
                authorPubkeyHex = decoded.pubkeyHex,
                dTag = if (kind == NostrKinds.NSITE_ROOT) "root" else decoded.dTag,
                relayHints = decoded.relays
            )
        }

        // 2. Explicit coordinate `35128:<pubkey>:<dTag>` or `15128:<pubkey>:root`
        val coordParts = withoutNostrPrefix.split(':')
        if (coordParts.size >= 2) {
            val k = coordParts[0].toIntOrNull()
            val pk = NostrCrypto.resolvePubkeyHex(coordParts[1])
            if (k != null && pk != null && k in setOf(NostrKinds.NSITE_NAMED, NostrKinds.NSITE_ROOT, NostrKinds.NSITE_LEGACY_FILE)) {
                val d = coordParts.getOrNull(2)?.ifBlank { "root" } ?: "root"
                val normKind = if (k == NostrKinds.NSITE_ROOT || d == "root") NostrKinds.NSITE_ROOT else NostrKinds.NSITE_NAMED
                return ResolvedNsiteTarget.Nip5aCoordinate(
                    kind = normKind,
                    authorPubkeyHex = pk,
                    dTag = if (normKind == NostrKinds.NSITE_ROOT) "root" else d
                )
            }
        }

        // 3. Bare npub1 / nprofile1 / 64-char hex pubkey
        NostrCrypto.resolvePubkeyHex(withoutNostrPrefix)?.let { pk ->
            return ResolvedNsiteTarget.AuthorAllSites(authorPubkeyHex = pk)
        }

        // 4. HTTPS / HTTP URLs or bare `<subdomain>.nsite.lol` hostnames
        val urlCandidate = when {
            trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.contains(".nsite.", ignoreCase = true) || trimmed.endsWith(".nsite.lol", ignoreCase = true) -> "https://$trimmed"
            else -> null
        }

        if (urlCandidate != null) {
            val uri = runCatching { URI(urlCandidate) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val path = uri.rawPath?.takeIf { it.isNotBlank() && it != "/" } ?: "/index.html"

            // Detect NIP-5A gateway hosts:
            // - `<npub1...>.<gateway>` -> Root Nsite (Kind 15128)
            // - `<50-char-pubkeyB36><1..13-char-dTag>.<gateway>` -> Named Nsite (Kind 35128)
            // - `<dTag>.<npub1...>.<gateway>` -> Legacy multi-label Named Nsite (Kind 35128)
            val labels = host.split('.')
            if (labels.size >= 3) {
                val first = labels[0]
                val second = labels[1]
                if (first.startsWith("npub1")) {
                    val pk = NostrCrypto.decodeNpubToHex(first)
                    if (pk != null) {
                        return ResolvedNsiteTarget.Nip5aCoordinate(
                            kind = NostrKinds.NSITE_ROOT,
                            authorPubkeyHex = pk,
                            dTag = "root",
                            initialPath = path
                        )
                    }
                }
                if (second.startsWith("npub1")) {
                    val pk = NostrCrypto.decodeNpubToHex(second)
                    if (pk != null) {
                        return ResolvedNsiteTarget.Nip5aCoordinate(
                            kind = NostrKinds.NSITE_NAMED,
                            authorPubkeyHex = pk,
                            dTag = first,
                            initialPath = path
                        )
                    }
                }
                if (first.length in 51..63 && first.all { it in '0'..'9' || it in 'a'..'z' || it == '-' }) {
                    val b36Part = first.substring(0, 50)
                    val dTagPart = first.substring(50)
                    if (b36Part.all { it in '0'..'9' || it in 'a'..'z' } &&
                        ExternalSiteLauncher.isValidNamedNsiteDTag(dTagPart)
                    ) {
                        val pk = runCatching { NostrCrypto.decodePubkeyBase36(b36Part) }.getOrNull()
                        if (pk != null) {
                            return ResolvedNsiteTarget.Nip5aCoordinate(
                                kind = NostrKinds.NSITE_NAMED,
                                authorPubkeyHex = pk,
                                dTag = dTagPart,
                                initialPath = path
                            )
                        }
                    }
                }
            }
            return ResolvedNsiteTarget.DirectHttpsSite(urlCandidate)
        }

        return null
    }

    /**
     * Resolves any address including asynchronous NIP-05 (`name@domain.tld` or `domain.tld` -> `_@domain.tld`).
     */
    suspend fun resolveAddress(
        rawInput: String,
        httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    ): ResolvedNsiteTarget? = withContext(Dispatchers.IO) {
        parseSync(rawInput)?.let { return@withContext it }

        val trimmed = rawInput.trim()
        val (name, domain) = when {
            '@' in trimmed && !trimmed.contains('/') -> {
                val n = trimmed.substringBefore('@').ifBlank { "_" }
                val d = trimmed.substringAfter('@').trim()
                n to d
            }
            '.' in trimmed && !trimmed.contains(' ') && !trimmed.contains('/') -> {
                "_" to trimmed
            }
            else -> return@withContext null
        }
        if (domain.isBlank() || !domain.contains('.')) return@withContext null

        runCatching {
            val url = "https://$domain/.well-known/nostr.json?name=$name"
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                val namesObj = json.optJSONObject("names") ?: return@use null
                val pubHex = namesObj.optString(name)
                    .ifBlank { namesObj.optString(name.lowercase()) }
                    .let { NostrCrypto.resolvePubkeyHex(it) }
                    ?: return@use null

                val relaysObj = json.optJSONObject("relays")?.optJSONArray(pubHex)
                val relayHints = buildList {
                    if (relaysObj != null) {
                        for (i in 0 until relaysObj.length()) {
                            val r = RelayPoolClient.normalizeRelayUrl(relaysObj.optString(i))
                            if (r.isNotBlank()) add(r)
                        }
                    }
                }
                if (name != "_") {
                    ResolvedNsiteTarget.AuthorAllSites(pubHex, relayHints)
                } else {
                    ResolvedNsiteTarget.Nip5aCoordinate(
                        kind = NostrKinds.NSITE_ROOT,
                        authorPubkeyHex = pubHex,
                        dTag = "root",
                        relayHints = relayHints
                    )
                }
            }
        }.getOrNull()
    }
}
