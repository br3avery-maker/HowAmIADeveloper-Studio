package com.example.nostr

import com.example.data.InstalledAppletEntity
import org.json.JSONObject

/**
 * Multi-layered spam detector for NIP-5A Nsites and NIP-5D Napplets.
 *
 * Implements the exact signals documented in Nsite spam analyses:
 * 1. Known bot-farm description opening prefixes (40.99%+ prevalence on public indexers):
 *    - "independent web researcher"
 *    - "practical technology researcher"
 *    - "community writer sharing"
 *    - "online publishing specialist"
 *    - "digital resources editor"
 * 2. Repeated SEO/gambling link-farm sentence structure:
 *    [Claimed generic professional role] -> [Generic promise of practical resources / useful notes] -> [Unrelated external domain]
 * 3. Structural 4-signal co-occurrence:
 *    Single-page manifest + '|' pipe in title + root npub site (kind 15128 / d="root") + external domain or template phrase.
 * 4. Cross-account template clustering across supposedly independent pubkeys.
 */
object NsiteSpamFilter {

    /** The five primary spam description opening phrases + close variants observed on relays. */
    val KNOWN_SPAM_DESCRIPTION_PREFIXES = listOf(
        "independent web researcher",
        "practical technology researcher",
        "community writer sharing",
        "online publishing specialist",
        "digital resources editor",
        "independent digital researcher",
        "technical documentation writer sharing",
        "online content specialist sharing",
        "web publishing editor sharing",
        "independent researcher sharing"
    )

    private val GENERIC_RESOURCE_PHRASES = listOf(
        "sharing practical resources",
        "useful notes related to",
        "practical resources and useful notes",
        "practical notes and useful resources",
        "helpful notes related to",
        "curated resources related to",
        "practical guides related to",
        "compartiendo recursos prácticos",
        "notas útiles relacionadas con"
    )

    private val MULTILINGUAL_PIPE_NOTE_TITLES = listOf(
        "| a note about ",
        "| una nota sobre ",
        "| un billet sur ",
        "| eine notiz über ",
        "| uma nota sobre ",
        "| una nota su ",
        "| notatka o ",
        "| een notitie over ",
        "| notes on ",
        "| practical notes on "
    )

    private val GAMBLING_OR_SEO_SPAM_TOKENS = listOf(
        "sportium",
        "-apostar-",
        "apostar-",
        "-bet-",
        "betting",
        "1xbet",
        "mostbet",
        "pin-up",
        "pinup",
        "parimatch",
        "melbet",
        "betano",
        "casino",
        "slots-",
        "tragamonedas",
        "bukmacher",
        "bonus-code",
        "promo-code"
    )

    private val EXTERNAL_DOMAIN_REGEX = Regex(
        """\b[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])?\.(?:com|net|org|io|co|mx|ca|es|br|ar|cl|pe|uk|de|fr|it|pl|ru|bet|casino|online|site|xyz|info|top|vip|win|pro|club)\b""",
        RegexOption.IGNORE_CASE
    )

    private val CLAIMED_ROLE_TO_RESOURCE_REGEX = Regex(
        """^(?:independent|practical|community|online|digital|freelance|technical|web)\s+[a-z]+\s+(?:researcher|specialist|editor|writer|contributor|curator|analyst)\b.*?\b(?:sharing|notes|resources|related\s+to)\b""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val WHITESPACE_REGEX = Regex("""\s+""")

    data class CatalogFilterResult(
        val cleanItems: List<InstalledAppletEntity>,
        val blockedSpamCount: Int
    )

    fun sanitizeSingleLine(raw: String): String =
        raw.replace(WHITESPACE_REGEX, " ").trim()

    /**
     * Evaluates a single manifest's metadata before inserting into SQLite or rendering.
     */
    fun isIndividualSpam(
        kind: Int,
        dTag: String,
        title: String,
        description: String,
        pathCount: Int
    ): Boolean {
        val normTitle = sanitizeSingleLine(title).lowercase()
        val normDesc = sanitizeSingleLine(description).lowercase()
        val combined = "$normTitle $normDesc ${dTag.lowercase()}"

        // 1. Direct match against the 5 known spam opening prefixes
        if (KNOWN_SPAM_DESCRIPTION_PREFIXES.any { prefix -> normDesc.startsWith(prefix) || normDesc.contains(prefix) }) {
            return true
        }

        // 2. Known multilingual pipe-title SEO templates (e.g., "Noah Foster | A note about Sportium...")
        if (MULTILINGUAL_PIPE_NOTE_TITLES.any { pattern -> normTitle.contains(pattern) }) {
            return true
        }

        // 3. Gambling / betting link-farm keywords
        if (GAMBLING_OR_SEO_SPAM_TOKENS.any { token -> combined.contains(token) }) {
            return true
        }

        // 4. Generic resource-sharing boilerplate paired with an external domain
        val hasExternalDomain = EXTERNAL_DOMAIN_REGEX.containsMatchIn(normDesc) ||
            EXTERNAL_DOMAIN_REGEX.containsMatchIn(normTitle)
        if (hasExternalDomain && GENERIC_RESOURCE_PHRASES.any { phrase -> normDesc.contains(phrase) }) {
            return true
        }

        // 5. Claimed professional identity -> generic resource promise sentence structure
        if (CLAIMED_ROLE_TO_RESOURCE_REGEX.containsMatchIn(normDesc)) {
            return true
        }

        // 6. Composite 4-signal structural check from infographic:
        //    [Single page] + ['|' in title] + [Root npub site] + [External domain]
        val isSinglePage = pathCount <= 1
        val hasPipeInTitle = normTitle.contains('|')
        val isRootAddress = kind == NostrKinds.NSITE_ROOT || dTag.equals("root", ignoreCase = true)
        if (isSinglePage && hasPipeInTitle && isRootAddress && hasExternalDomain) {
            return true
        }

        return false
    }

    fun isEntityIndividualSpam(entity: InstalledAppletEntity): Boolean {
        val pathCount = countPaths(entity.manifestPathsJson)
        return isIndividualSpam(
            kind = entity.kind,
            dTag = entity.dTag,
            title = entity.title,
            description = entity.description,
            pathCount = pathCount
        )
    }

    /**
     * Applies both individual fingerprint rules AND cross-account template clustering across
     * distinct Nostr pubkeys (the strongest bot-farm signal).
     * Never blocks locally authored items or items explicitly pinned by the user.
     */
    fun filterCatalogWithClustering(
        items: List<InstalledAppletEntity>,
        localPubkeyHex: String
    ): CatalogFilterResult {
        if (items.isEmpty()) return CatalogFilterResult(emptyList(), 0)

        val protectedIds = HashSet<String>()
        val individuallyFlaggedIds = HashSet<String>()

        val featuredHex = NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX
        for (item in items) {
            val isLocalOrPinnedOrTrusted = item.isInstalled ||
                (localPubkeyHex.isNotBlank() && item.authorPubkey.equals(localPubkeyHex, ignoreCase = true)) ||
                (featuredHex.isNotBlank() && item.authorPubkey.equals(featuredHex, ignoreCase = true))
            if (isLocalOrPinnedOrTrusted) {
                protectedIds.add(item.appletId)
            } else if (isEntityIndividualSpam(item)) {
                individuallyFlaggedIds.add(item.appletId)
            }
        }

        // Cross-account clustering on remaining external items:
        // Group by normalized description template skeleton and pipe-title skeleton.
        val descTemplateToPubkeys = HashMap<String, MutableSet<String>>()
        val descTemplateToIds = HashMap<String, MutableList<String>>()

        val pipeTitleTemplateToPubkeys = HashMap<String, MutableSet<String>>()
        val pipeTitleTemplateToIds = HashMap<String, MutableList<String>>()

        for (item in items) {
            if (item.appletId in protectedIds || item.appletId in individuallyFlaggedIds) continue

            buildDescriptionClusterKey(item)?.let { key ->
                descTemplateToPubkeys.getOrPut(key) { HashSet() }.add(item.authorPubkey.lowercase())
                descTemplateToIds.getOrPut(key) { ArrayList() }.add(item.appletId)
            }

            buildPipeTitleClusterKey(item.title)?.let { key ->
                pipeTitleTemplateToPubkeys.getOrPut(key) { HashSet() }.add(item.authorPubkey.lowercase())
                pipeTitleTemplateToIds.getOrPut(key) { ArrayList() }.add(item.appletId)
            }
        }

        val clusterFlaggedIds = HashSet<String>()
        descTemplateToPubkeys.forEach { (key, pubkeys) ->
            // If >= 2 distinct pubkeys publish the exact same multi-word description template
            if (pubkeys.size >= 2) {
                descTemplateToIds[key]?.let { clusterFlaggedIds.addAll(it) }
            }
        }
        pipeTitleTemplateToPubkeys.forEach { (key, pubkeys) ->
            if (pubkeys.size >= 2) {
                pipeTitleTemplateToIds[key]?.let { clusterFlaggedIds.addAll(it) }
            }
        }

        val allBlocked = individuallyFlaggedIds + clusterFlaggedIds
        if (allBlocked.isEmpty()) {
            return CatalogFilterResult(items, 0)
        }

        val clean = items.filterNot { it.appletId in allBlocked }
        return CatalogFilterResult(
            cleanItems = clean,
            blockedSpamCount = allBlocked.size
        )
    }

    fun findSpamAppletIds(items: List<InstalledAppletEntity>, localPubkeyHex: String): List<String> {
        val result = filterCatalogWithClustering(items, localPubkeyHex)
        if (result.blockedSpamCount == 0) return emptyList()
        val cleanIds = result.cleanItems.mapTo(HashSet()) { it.appletId }
        return items.asSequence()
            .map { it.appletId }
            .filter { it !in cleanIds }
            .toList()
    }

    private fun buildDescriptionClusterKey(item: InstalledAppletEntity): String? {
        val clean = sanitizeSingleLine(item.description).lowercase()
        if (clean.isBlank() ||
            clean == "decentralized nostr web manifest" ||
            clean == "nostr web applet"
        ) {
            return null
        }
        // Replace domains/URLs with <dom> and digits with <n> to expose shared bot templates
        val masked = clean
            .replace(Regex("""https?://\S+"""), "<url>")
            .replace(EXTERNAL_DOMAIN_REGEX, "<dom>")
            .replace(Regex("""\d+"""), "<n>")
            .replace(Regex("""[^\p{L}\p{N}<>\s]"""), " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        val tokens = masked.split(' ').filter { it.isNotBlank() }
        // Require at least 5 words so short 2-word genuine descriptions ("nostr chat") don't collide
        if (tokens.size < 5) return null
        return tokens.take(6).joinToString(" ")
    }

    private fun buildPipeTitleClusterKey(title: String): String? {
        val clean = sanitizeSingleLine(title)
        val pipeIdx = clean.indexOf('|')
        if (pipeIdx <= 0 || pipeIdx >= clean.length - 2) return null
        val rhs = clean.substring(pipeIdx + 1)
            .lowercase()
            .replace(EXTERNAL_DOMAIN_REGEX, "<dom>")
            .replace(Regex("""[^\p{L}\p{N}<>\s]"""), " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        val words = rhs.split(' ').filter { it.isNotBlank() }
        if (words.size < 3) return null
        return "pipe:" + words.take(3).joinToString(" ")
    }

    private fun countPaths(manifestPathsJson: String): Int {
        if (manifestPathsJson.isBlank()) return 0
        return runCatching { JSONObject(manifestPathsJson).length() }.getOrDefault(0)
    }
}
