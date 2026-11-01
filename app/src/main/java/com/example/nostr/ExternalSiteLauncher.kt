package com.example.nostr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import com.example.data.InstalledAppletEntity
import org.json.JSONObject
import java.net.URI

object ExternalSiteLauncher {

    private const val DEFAULT_NSITE_GATEWAY_DOMAIN = "nsite.lol"

    private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
    private const val EXTRA_TOOLBAR_COLOR = "android.support.customtabs.extra.TOOLBAR_COLOR"
    private const val EXTRA_ENABLE_URLBAR_HIDING = "android.support.customtabs.extra.ENABLE_URLBAR_HIDING"
    private const val EXTRA_SHOW_TITLE = "android.support.customtabs.extra.TITLE_VISIBILITY"
    private const val EXTRA_COLOR_SCHEME = "androidx.browser.customtabs.extra.COLOR_SCHEME"
    private const val EXTRA_INITIAL_ACTIVITY_HEIGHT_PX = "androidx.browser.customtabs.extra.INITIAL_ACTIVITY_HEIGHT_PX"
    private const val EXTRA_ACTIVITY_HEIGHT_RESIZE_BEHAVIOR = "androidx.browser.customtabs.extra.ACTIVITY_HEIGHT_RESIZE_BEHAVIOR"
    private const val EXTRA_CORNER_RADIUS_DP = "androidx.browser.customtabs.extra.TOOLBAR_CORNER_RADIUS_DP"
    private const val COLOR_SCHEME_DARK = 2

    /**
     * Builds the canonical single-label NIP-5A HTTPS gateway URL:
     * - Root site (`Kind 15128` or `dTag` blank/`"root"`): `https://<npub>.nsite.lol/` (63-char single label)
     * - Named site (`Kind 35128` with 1..13 char `[a-z0-9-]` `dTag`): `https://<pubkeyB36><dTag>.nsite.lol/`
     *   (50-char lowercase base36 pubkey + 1..13 char `dTag` = 51..63 chars in a SINGLE DNS label,
     *   satisfying both RFC 1035 label limits and `*.nsite.lol` single-level wildcard TLS certificates).
     */
    fun buildNip5aGatewayUrl(
        kind: Int,
        authorPubkeyHex: String,
        authorNpub: String,
        dTag: String,
        gatewayDomain: String = DEFAULT_NSITE_GATEWAY_DOMAIN
    ): String? {
        val hexCandidate = authorPubkeyHex.trim().lowercase()
        val resolvedHex = if (hexCandidate.length == 64 && hexCandidate.all { it.isDigit() || it in 'a'..'f' }) {
            hexCandidate
        } else {
            NostrCrypto.decodeNpubToHex(authorNpub)
        }

        val npubCandidate = authorNpub.trim().lowercase()
        val resolvedNpub = if (npubCandidate.startsWith("npub1") && NostrCrypto.decodeNpubToHex(npubCandidate) != null) {
            npubCandidate
        } else {
            resolvedHex?.let { runCatching { NostrCrypto.encodeNpub(it) }.getOrNull() }
        }

        val cleanDTag = dTag.trim().lowercase()
        if (kind == NostrKinds.NSITE_ROOT || cleanDTag.isBlank() || cleanDTag == "root") {
            val npub = resolvedNpub ?: return null
            if (npub.length > 63) return null
            return "https://$npub.$gatewayDomain/"
        }

        val pubHex = resolvedHex ?: return null
        if (!isValidNamedNsiteDTag(cleanDTag)) return null
        val pubkeyB36 = runCatching { NostrCrypto.encodePubkeyBase36(pubHex) }.getOrNull() ?: return null
        val label = "$pubkeyB36$cleanDTag"
        if (label.length > 63) return null
        return "https://$label.$gatewayDomain/"
    }

    /**
     * NIP-5A Named Site `d`-tags are 1..13 lowercase alphanumeric or hyphen characters (`^[a-z0-9-]{1,13}$`),
     * and must not end with `-` because `<pubkeyB36><dTag>` forms the trailing end of the DNS label.
     */
    fun isValidNamedNsiteDTag(dTag: String): Boolean {
        if (dTag.length !in 1..13) return false
        if (dTag.endsWith('-')) return false
        return dTag.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }

    /**
     * Validates an explicit external URL and automatically repairs legacy multi-level
     * `https://<dTag>.<npub1...>.nsite.lol/...` addresses into single-label NIP-5A
     * `https://<pubkeyB36><dTag>.nsite.lol/...` addresses so wildcard TLS never fails.
     */
    fun normalizeGatewayUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true) && !trimmed.startsWith("http://", ignoreCase = true)) {
            return null
        }
        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = parsed.host?.lowercase() ?: return null

        val gatewaySuffix = ".$DEFAULT_NSITE_GATEWAY_DOMAIN"
        if (host.endsWith(gatewaySuffix)) {
            val subdomain = host.removeSuffix(gatewaySuffix)
            if (subdomain.isBlank()) return null

            if ('.' in subdomain) {
                val parts = subdomain.split('.')
                if (parts.size == 2) {
                    val (dPart, npubPart) = when {
                        parts[1].startsWith("npub1") -> parts[0] to parts[1]
                        parts[0].startsWith("npub1") -> parts[1] to parts[0]
                        else -> return null
                    }
                    val repairedBase = buildNip5aGatewayUrl(
                        kind = NostrKinds.NSITE_NAMED,
                        authorPubkeyHex = "",
                        authorNpub = npubPart,
                        dTag = dPart
                    ) ?: return null
                    val extraPath = parsed.rawPath?.takeIf { it.isNotEmpty() && it != "/" }
                        ?.removePrefix("/").orEmpty()
                    val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
                    val fragment = parsed.rawFragment?.let { "#$it" }.orEmpty()
                    return "$repairedBase$extraPath$query$fragment"
                }
                return null
            }

            if (subdomain.length > 63) return null
        }

        val labels = host.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return null
        return trimmed
    }

    /**
     * Computes the canonical external HTTPS URL for a NIP-5A Nsite or Blossom-hosted NIP-5D Napplet:
     * 1. Explicit `url` / `u` tag from manifest (`__direct_url`) if valid and not a bare Blossom host root.
     * 2. Official single-label NIP-5A gateway address:
     *    - `https://<npub>.nsite.lol/` for Kind 15128 root sites
     *    - `https://<pubkeyB36><dTag>.nsite.lol/` for Kind 35128 named sites
     * 3. Content-addressed Blossom blob URL (`https://<blossomServer>/<sha256>.html`) for single-file bundles.
     */
    fun resolveExternalUrl(applet: InstalledAppletEntity): String? {
        val manifestObj = runCatching {
            if (applet.manifestPathsJson.isNotBlank()) JSONObject(applet.manifestPathsJson) else null
        }.getOrNull()

        val rawDirectUrl = manifestObj?.optString("__direct_url")?.trim().orEmpty()
        val normalizedDirectUrl = if (rawDirectUrl.isNotBlank()) normalizeGatewayUrl(rawDirectUrl) else null
        if (!normalizedDirectUrl.isNullOrBlank() && !isBareBlossomServerRoot(normalizedDirectUrl, applet.blossomServer)) {
            return normalizedDirectUrl
        }

        if (applet.isNsite) {
            buildNip5aGatewayUrl(
                kind = applet.kind,
                authorPubkeyHex = applet.authorPubkey,
                authorNpub = applet.authorNpub,
                dTag = applet.dTag
            )?.let { return it }
        }

        if (applet.indexSha256.length == 64 && applet.blossomServer.isNotBlank()) {
            val nonInternalRouteCount = manifestObj?.keys()?.asSequence()
                ?.count { !it.startsWith("__") } ?: 0
            // Multi-file Nsites without a valid gateway URL need subresource path interception in WebView
            if (!applet.isNsite || nonInternalRouteCount <= 1) {
                return "${applet.blossomServer.trim().trimEnd('/')}/${applet.indexSha256.lowercase()}.html"
            }
        }

        return null
    }

    private fun isBareBlossomServerRoot(candidateUrl: String, blossomServer: String): Boolean {
        if (blossomServer.isBlank()) return false
        return runCatching {
            val cand = URI(candidateUrl)
            val srv = URI(blossomServer.trim())
            val sameHost = cand.host != null && cand.host.equals(srv.host, ignoreCase = true)
            val emptyPath = cand.path.isNullOrEmpty() || cand.path == "/"
            sameHost && emptyPath && cand.query.isNullOrEmpty()
        }.getOrDefault(false)
    }

    /**
     * Launches the resolved URL in an Android Custom Tab bottom-sheet (matching native Nostr shells,
     * with pitch-black #000000 chrome and drag-to-expand handle), falling back cleanly to the system
     * browser or returning `false` if no browser Activity is installed on the device.
     */
    fun launchCustomTabOrBrowser(
        context: Context,
        url: String,
        asExpandableSheet: Boolean = true
    ): Boolean {
        val safeUrl = normalizeGatewayUrl(url) ?: return false
        val uri = runCatching { Uri.parse(safeUrl) }.getOrNull() ?: return false

        val displayHeightPx = context.resources.displayMetrics.heightPixels
        val sheetHeightPx = (displayHeightPx * 0.93f).toInt().coerceAtLeast(600)

        val customTabIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            val sessionBundle = Bundle().apply {
                putBinder(EXTRA_CUSTOM_TABS_SESSION, null)
            }
            putExtras(sessionBundle)
            putExtra(EXTRA_TOOLBAR_COLOR, Color.BLACK)
            putExtra(EXTRA_COLOR_SCHEME, COLOR_SCHEME_DARK)
            putExtra(EXTRA_SHOW_TITLE, 1)
            putExtra(EXTRA_ENABLE_URLBAR_HIDING, true)
            putExtra(EXTRA_CORNER_RADIUS_DP, 20)
            if (asExpandableSheet && context is Activity) {
                putExtra(EXTRA_INITIAL_ACTIVITY_HEIGHT_PX, sheetHeightPx)
                putExtra(EXTRA_ACTIVITY_HEIGHT_RESIZE_BEHAVIOR, 1) // ACTIVITY_HEIGHT_ADJUSTABLE
            } else {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return runCatching {
            context.startActivity(customTabIntent)
            true
        }.recoverCatching {
            val plainBrowser = Intent(Intent.ACTION_VIEW, uri).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(plainBrowser)
            true
        }.getOrDefault(false)
    }
}
