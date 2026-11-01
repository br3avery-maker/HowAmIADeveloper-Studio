package com.example.nostr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import com.example.data.InstalledAppletEntity
import org.json.JSONObject

object ExternalSiteLauncher {

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
     * Computes the canonical external HTTPS URL for a NIP-5A Nsite or Blossom-hosted NIP-5D Napplet:
     * 1. Explicit `url` / `u` / `r` tag from manifest (`__direct_url`) if present.
     * 2. Official NIP-5A gateway address (`https://<npub>.nsite.lol/` for Kind 15128 root,
     *    `https://<dTag>.<npub>.nsite.lol/` for Kind 35128 named Nsites).
     * 3. Content-addressed Blossom blob URL (`https://<blossomServer>/<sha256>.html`) for single-file bundles.
     */
    fun resolveExternalUrl(applet: InstalledAppletEntity): String? {
        val directUrl = runCatching {
            if (applet.manifestPathsJson.isNotBlank()) {
                JSONObject(applet.manifestPathsJson).optString("__direct_url").trim()
            } else ""
        }.getOrDefault("")

        if (directUrl.startsWith("https://", ignoreCase = true) || directUrl.startsWith("http://", ignoreCase = true)) {
            return directUrl
        }

        if (applet.isNsite && applet.authorNpub.startsWith("npub1", ignoreCase = true)) {
            val cleanNpub = applet.authorNpub.trim().lowercase()
            val cleanDTag = applet.dTag.trim().lowercase()
            return if (applet.kind == NostrKinds.NSITE_ROOT || cleanDTag.isBlank() || cleanDTag == "root") {
                "https://$cleanNpub.nsite.lol/"
            } else {
                "https://$cleanDTag.$cleanNpub.nsite.lol/"
            }
        }

        if (applet.indexSha256.length == 64 && applet.blossomServer.isNotBlank()) {
            return "${applet.blossomServer.trim().trimEnd('/')}/${applet.indexSha256.lowercase()}.html"
        }

        return null
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
        if (url.isBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false

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
