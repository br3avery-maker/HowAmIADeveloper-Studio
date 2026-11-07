package com.example.data

import com.example.nostr.NostrCrypto
import com.example.nostr.NsiteSpamFilter

enum class GalleryCategory(val label: String, val glyph: String) {
    ALL("All", "✦"),
    FEATURED("Featured", "☆"),
    NIP51_CURATED("NIP-51 Lists", "🔖"),
    NSITES("Nsites", "🌐"),
    SOCIAL("Social", "💬"),
    TOOLS("Tools", "⚡"),
    GAMES_ART("Games & Art", "🎮"),
    ZINES("Zines & Docs", "📖")
}

enum class VariantRelation(val badgeLabel: String) {
    PRIMARY("Primary"),
    IDENTICAL_CLONE("Identical Clone"),
    SAME_AUTHOR_VERSION("Author Version"),
    COMMUNITY_FORK("Community Fork")
}

data class StackedVariant(
    val applet: InstalledAppletEntity,
    val relation: VariantRelation,
    val isLocalStarred: Boolean,
    val curatorNpubs: List<String>
)

data class StackedAppletGroup(
    val groupId: String,
    val primary: InstalledAppletEntity,
    val variants: List<StackedVariant>,
    val isFeatured: Boolean,
    val isCuratedNip51: Boolean,
    val isLocalStarred: Boolean,
    val curatorCount: Int,
    val categories: Set<GalleryCategory>,
    val anyInstalled: Boolean
) {
    val stackCount: Int get() = variants.size
    val hasMultipleEditions: Boolean get() = variants.size > 1
}

object CatalogStacker {

    private val GENERIC_SLUGS = setOf(
        "root", "app", "site", "nsite", "napplet", "index", "home", "main", "default", "draft", "test"
    )

    private val SUFFIX_STRIP_REGEX = Regex(
        """(?:[-_\s]+(?:fork|remix|clone|copy|classic|legacy|beta|alpha|edition|v\d+(?:\.\d+)*))+$""",
        RegexOption.IGNORE_CASE
    )

    private val NON_ALNUM_REGEX = Regex("""[^a-z0-9]+""")

    fun coordinateFor(applet: InstalledAppletEntity): String =
        "${applet.kind}:${applet.authorPubkey}:${applet.dTag}"

    fun canonicalSlug(rawDTag: String): String? {
        val cleaned = rawDTag.trim().lowercase()
        if (cleaned.isBlank() || cleaned in GENERIC_SLUGS) return null
        val stripped = cleaned.replace(SUFFIX_STRIP_REGEX, "")
            .replace(NON_ALNUM_REGEX, "-")
            .trim('-')
        if (stripped.length < 3 || stripped in GENERIC_SLUGS) return null
        return stripped
    }

    fun canonicalTitleStem(rawTitle: String): String? {
        val single = NsiteSpamFilter.sanitizeSingleLine(rawTitle).lowercase()
        if (single.isBlank()) return null
        if (single.startsWith("nsite ") || single.startsWith("napplet ")) return null
        val noParens = single.replace(Regex("""\([^)]*\)"""), " ").trim()
        val stripped = noParens.replace(SUFFIX_STRIP_REGEX, "")
            .replace(NON_ALNUM_REGEX, " ")
            .trim()
        if (stripped.length < 4 || stripped in GENERIC_SLUGS) return null
        return stripped
    }

    /**
     * Clusters matching clones, forks, and versions using Union-Find across:
     * 1. Identical Blossom SHA-256 bundle hash (`indexSha256`)
     * 2. Canonical `d-tag` slug (excluding generic `"root"`)
     * 3. Canonical normalized `title` stem (stripping Remix/Fork/v1 suffixes)
     */
    fun buildStackedGroups(
        items: List<InstalledAppletEntity>,
        bookmarks: List<CuratedBookmarkEntity>,
        localPubkeyHex: String,
        featuredAuthorPubkeys: Set<String> = setOfNotNull(
            NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX.takeIf { it.isNotBlank() }
        )
    ): List<StackedAppletGroup> {
        if (items.isEmpty()) return emptyList()
        val lowerFeaturedAuthors = featuredAuthorPubkeys.mapTo(HashSet()) { it.lowercase() }

        val n = items.size
        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var curr = x
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        val keyToFirstIndex = HashMap<String, Int>()
        items.forEachIndexed { idx, item ->
            val protoPrefix = if (item.isNsite) "nsite" else "nap"

            if (item.indexSha256.length >= 32) {
                val shaKey = "$protoPrefix:sha:${item.indexSha256.lowercase()}"
                val prev = keyToFirstIndex.putIfAbsent(shaKey, idx)
                if (prev != null) union(prev, idx)
            }

            canonicalSlug(item.dTag)?.let { slug ->
                val slugKey = "$protoPrefix:slug:$slug"
                val prev = keyToFirstIndex.putIfAbsent(slugKey, idx)
                if (prev != null) union(prev, idx)
            }

            canonicalTitleStem(item.title)?.let { stem ->
                val stemKey = "$protoPrefix:stem:$stem"
                val prev = keyToFirstIndex.putIfAbsent(stemKey, idx)
                if (prev != null) union(prev, idx)
            }
        }

        // Index NIP-51 bookmarks by coordinate AND by canonical dTag fallback
        val coordToBookmarks = bookmarks.groupBy { it.coordinate.lowercase() }

        val buckets = LinkedHashMap<Int, MutableList<InstalledAppletEntity>>()
        items.forEachIndexed { idx, item ->
            buckets.getOrPut(find(idx)) { ArrayList() }.add(item)
        }

        return buckets.values.map { members ->
            val scoredMembers = members.map { app ->
                val coord = coordinateFor(app).lowercase()
                val matchingBookmarks = coordToBookmarks[coord].orEmpty()
                val localStar = matchingBookmarks.any {
                    it.isLocalCurator || (localPubkeyHex.isNotBlank() && it.curatorPubkey.equals(localPubkeyHex, ignoreCase = true))
                }
                val curatorNpubs = matchingBookmarks.map { it.curatorNpub }.distinct()
                Triple(app, localStar, curatorNpubs)
            }.sortedWith(
                compareByDescending<Triple<InstalledAppletEntity, Boolean, List<String>>> { it.first.isInstalled }
                    .thenByDescending { it.second }
                    .thenByDescending { it.third.size }
                    .thenByDescending { it.first.authorPubkey.lowercase() in lowerFeaturedAuthors }
                    .thenByDescending {
                        localPubkeyHex.isNotBlank() && it.first.authorPubkey.equals(localPubkeyHex, ignoreCase = true)
                    }
                    .thenByDescending { completenessScore(it.first) }
                    .thenByDescending { it.first.updatedAt }
            )

            val primaryTriple = scoredMembers.first()
            val primary = primaryTriple.first

            val variants = scoredMembers.mapIndexed { idx, (app, localStar, curators) ->
                val relation = when {
                    idx == 0 -> VariantRelation.PRIMARY
                    (app.indexSha256.isNotBlank() && app.indexSha256.equals(primary.indexSha256, ignoreCase = true)) ||
                        (app.inlineHtml.isNotBlank() && app.inlineHtml == primary.inlineHtml) ->
                        VariantRelation.IDENTICAL_CLONE
                    app.authorPubkey.equals(primary.authorPubkey, ignoreCase = true) ->
                        VariantRelation.SAME_AUTHOR_VERSION
                    else -> VariantRelation.COMMUNITY_FORK
                }
                StackedVariant(
                    applet = app,
                    relation = relation,
                    isLocalStarred = localStar,
                    curatorNpubs = curators
                )
            }

            val allCurators = variants.flatMap { it.curatorNpubs }.distinct()
            val isLocalStarred = variants.any { it.isLocalStarred }
            val isCuratedNip51 = isLocalStarred || allCurators.isNotEmpty()
            val anyInstalled = variants.any { it.applet.isInstalled }
            val isFeaturedAuthor = variants.any { it.applet.authorPubkey.lowercase() in lowerFeaturedAuthors }
            val isFeatured = isCuratedNip51 ||
                isFeaturedAuthor ||
                completenessScore(primary) >= 4

            val categories = classifyCategories(
                primary = primary,
                isFeatured = isFeatured,
                isCuratedNip51 = isCuratedNip51
            )

            StackedAppletGroup(
                groupId = primary.appletId,
                primary = primary,
                variants = variants,
                isFeatured = isFeatured,
                isCuratedNip51 = isCuratedNip51,
                isLocalStarred = isLocalStarred,
                curatorCount = allCurators.size,
                categories = categories,
                anyInstalled = anyInstalled
            )
        }.sortedWith(
            compareByDescending<StackedAppletGroup> { it.anyInstalled }
                .thenByDescending { grp -> grp.variants.any { it.applet.authorPubkey.lowercase() in lowerFeaturedAuthors } }
                .thenByDescending { it.isLocalStarred }
                .thenByDescending { it.isCuratedNip51 }
                .thenByDescending { it.isFeatured }
                .thenByDescending { it.stackCount > 1 }
                .thenByDescending { it.primary.updatedAt }
        )
    }

    private fun completenessScore(app: InstalledAppletEntity): Int {
        var score = 0
        if (app.inlineHtml.isNotBlank()) score += 3
        if (app.indexSha256.isNotBlank()) score += 2
        if (app.manifestPathsJson.length > 10) score += 1
        if (app.description.length >= 30 &&
            !app.description.startsWith("Sandboxed NIP-5D") &&
            !app.description.startsWith("Decentralized NIP-5A")
        ) {
            score += 2
        }
        return score
    }

    private fun classifyCategories(
        primary: InstalledAppletEntity,
        isFeatured: Boolean,
        isCuratedNip51: Boolean
    ): Set<GalleryCategory> {
        val out = LinkedHashSet<GalleryCategory>()
        out.add(GalleryCategory.ALL)
        out.add(GalleryCategory.NSITES)
        if (isFeatured) out.add(GalleryCategory.FEATURED)
        if (isCuratedNip51) out.add(GalleryCategory.NIP51_CURATED)

        val hay = "${primary.title} ${primary.description} ${primary.dTag}".lowercase()
        if (listOf("note", "chat", "dm", "poll", "social", "feed", "reply", "profile", "agent").any { it in hay }) {
            out.add(GalleryCategory.SOCIAL)
        }
        if (listOf("cipher", "encrypt", "relay", "sonar", "calc", "zap", "sats", "tool", "vault", "key", "storage").any { it in hay }) {
            out.add(GalleryCategory.TOOLS)
        }
        if (listOf("2048", "puzzle", "link", "board", "arcade", "score", "crt", "puter", "game", "chess", "pixel", "synth", "art", "canvas", "play", "audio", "music").any { it in hay }) {
            out.add(GalleryCategory.GAMES_ART)
        }
        if (listOf("zine", "blog", "guide", "wiki", "doc", "read", "article", "manifesto").any { it in hay }) {
            out.add(GalleryCategory.ZINES)
        }
        return out
    }
}
