package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CatalogStacker
import com.example.data.CuratedBookmarkEntity
import com.example.data.GalleryCategory
import com.example.data.InstalledAppletEntity
import com.example.data.ShellRepository
import com.example.data.VariantRelation
import com.example.nostr.ExternalSiteLauncher
import com.example.nostr.KeyVaultManager
import com.example.nostr.NostrCrypto
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.NsiteSpamFilter
import com.example.ui.injectRuntimeBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun appNameAndVaultAndNip04RoundTrip_succeeds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SovereignShell", appName)

        val kp = NostrCrypto.generateKeyPair()
        val vault = KeyVaultManager(context)
        val wrapped = vault.encryptPrivateKeyHex(kp.privKeyHex)
        val unwrapped = vault.decryptPrivateKeyHex(wrapped.ciphertextBase64, wrapped.ivBase64)
        assertEquals(kp.privKeyHex, unwrapped)

        val secretMemo = "Self-published Nsite & Napplet shell memo"
        val ciphertext = NostrCrypto.nip04Encrypt(kp.privKeyHex, kp.pubKeyHex, secretMemo)
        val decrypted = NostrCrypto.nip04Decrypt(kp.privKeyHex, kp.pubKeyHex, ciphertext)
        assertEquals(secretMemo, decrypted)

        // Verify NIP-44 v2 authenticated encryption between two distinct keypairs
        val peer = NostrCrypto.generateKeyPair()
        val nip44Plain = "SovereignShell NIP-44 v2 authenticated ChaCha20 + HMAC-SHA256 payload! ".repeat(3)
        val nip44Envelope = NostrCrypto.nip44Encrypt(kp.privKeyHex, peer.pubKeyHex, nip44Plain)
        val nip44DecryptedByPeer = NostrCrypto.nip44Decrypt(peer.privKeyHex, kp.pubKeyHex, nip44Envelope)
        assertEquals(nip44Plain, nip44DecryptedByPeer)

        // Verify NIP-19 naddr coordinate encoding
        val naddr = NostrCrypto.encodeNaddr(35129, kp.pubKeyHex, "micro-notes")
        assertTrue(naddr.startsWith("naddr1"))
    }

    @Test
    fun nip5dXTagManifestParsingAndCrossAccountSpamClustering_workEndToEnd() {
        val author1 = NostrCrypto.generateKeyPair()
        val author2 = NostrCrypto.generateKeyPair()
        val bundleSha = NostrCrypto.sha256Hex("<!DOCTYPE html><html><body>Hello NIP-5D</body></html>")

        // Real NIP-5D Kind 35129 Napplet using standard ["x", "<64-hex-sha256>"] and ["name", ...] tags (no path tag!)
        val nip5dEvent = NostrEvent.createAndSign(
            privKeyHex = author1.privKeyHex,
            kind = NostrKinds.NAPPLET_NAMED,
            content = "Interactive pixel canvas over Nostr NAP-STORAGE.",
            tags = listOf(
                listOf("d", "pixel-board"),
                listOf("name", "PixelBoard"),
                listOf("x", bundleSha),
                listOf("blossom", "https://cdn.satellite.earth")
            )
        )
        val parsedNapplet = ShellRepository.parseManifestEventToEntity(nip5dEvent)
        assertNotNull("NIP-5D Napplet with ['x', sha256] must be ingested", parsedNapplet)
        assertEquals("PixelBoard", parsedNapplet!!.title)
        assertEquals(bundleSha, parsedNapplet.indexSha256)
        assertEquals("https://cdn.satellite.earth", parsedNapplet.blossomServer)
        assertEquals(false, parsedNapplet.isNsite)

        // Spam Kind 15128 event must be rejected at parse time
        val spamEvent = NostrEvent.createAndSign(
            privKeyHex = author2.privKeyHex,
            kind = NostrKinds.NSITE_ROOT,
            content = "Online publishing specialist sharing practical resources and useful notes related to sportium-bet-ca.com.",
            tags = listOf(
                listOf("title", "Noah Foster | A note about Sportium"),
                listOf("path", "/index.html", bundleSha)
            )
        )
        assertNull(
            "Known spam fingerprint must be dropped before DB insert",
            ShellRepository.parseManifestEventToEntity(spamEvent)
        )

        // Cross-account template clustering catches novel coordinated bot templates across >= 2 pubkeys
        val botA = InstalledAppletEntity(
            appletId = "35128:${author1.pubKeyHex}:digest",
            kind = NostrKinds.NSITE_NAMED,
            authorPubkey = author1.pubKeyHex,
            authorNpub = author1.npub,
            dTag = "digest",
            title = "Maya Lin Overview",
            description = "Curated regional reference digest covering daily updates at alpha-news-hub.org",
            iconGlyph = "🌐",
            accentHex = "#5CC8FF",
            isNsite = true,
            enableNip07 = true,
            isInstalled = false
        )
        val botB = InstalledAppletEntity(
            appletId = "35128:${author2.pubKeyHex}:digest",
            kind = NostrKinds.NSITE_NAMED,
            authorPubkey = author2.pubKeyHex,
            authorNpub = author2.npub,
            dTag = "digest",
            title = "Evan Cole Summary",
            description = "Curated regional reference digest covering daily updates at beta-links-portal.org",
            iconGlyph = "🌐",
            accentHex = "#5CC8FF",
            isNsite = true,
            enableNip07 = true,
            isInstalled = false
        )

        val clustered = NsiteSpamFilter.filterCatalogWithClustering(
            items = listOf(parsedNapplet, botA, botB),
            localPubkeyHex = ""
        )
        assertEquals(2, clustered.blockedSpamCount)
        assertEquals(listOf(parsedNapplet.appletId), clustered.cleanItems.map { it.appletId })
    }

    @Test
    fun catalogStacker_stacksClonesVersionsAndForks_whileIsolatingGenericRootNsites() {
        val creator = NostrCrypto.generateKeyPair()
        val forker = NostrCrypto.generateKeyPair()
        val cloner = NostrCrypto.generateKeyPair()
        val zineA = NostrCrypto.generateKeyPair()
        val zineB = NostrCrypto.generateKeyPair()

        val primarySha = NostrCrypto.sha256Hex("<html><body>PulseNotes v2</body></html>")
        val v1Sha = NostrCrypto.sha256Hex("<html><body>PulseNotes v1</body></html>")
        val forkSha = NostrCrypto.sha256Hex("<html><body>PulseNotes Fork</body></html>")

        val primaryPulse = InstalledAppletEntity(
            appletId = "35129:${creator.pubKeyHex}:pulse-notes",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = creator.pubKeyHex,
            authorNpub = creator.npub,
            dTag = "pulse-notes",
            title = "PulseNotes",
            description = "Fast Nostr Kind 1 micro-composer & live feed viewer powered by NIP-5D NAPs.",
            iconGlyph = "⚡",
            accentHex = "#FF1493",
            indexSha256 = primarySha,
            inlineHtml = "<div>PulseNotes v2</div>",
            isNsite = false,
            enableNip07 = false,
            isInstalled = true,
            updatedAt = 2000L
        )
        val authorVersion = InstalledAppletEntity(
            appletId = "35129:${creator.pubKeyHex}:pulse-notes-classic",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = creator.pubKeyHex,
            authorNpub = creator.npub,
            dTag = "pulse-notes-classic",
            title = "PulseNotes v1",
            description = "Minimalist classic v1 edition of PulseNotes.",
            iconGlyph = "📝",
            accentHex = "#CD0D72",
            indexSha256 = v1Sha,
            inlineHtml = "<div>PulseNotes v1</div>",
            isNsite = false,
            enableNip07 = false,
            isInstalled = false,
            updatedAt = 1500L
        )
        val communityFork = InstalledAppletEntity(
            appletId = "35129:${forker.pubKeyHex}:pulse-notes-fork",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = forker.pubKeyHex,
            authorNpub = forker.npub,
            dTag = "pulse-notes-fork",
            title = "PulseNotes Remix",
            description = "Community dark-neon remix of PulseNotes.",
            iconGlyph = "⚡",
            accentHex = "#00F5D4",
            indexSha256 = forkSha,
            inlineHtml = "<div>PulseNotes Fork</div>",
            isNsite = false,
            enableNip07 = false,
            isInstalled = false,
            updatedAt = 1800L
        )
        val identicalClone = InstalledAppletEntity(
            appletId = "35129:${cloner.pubKeyHex}:totally-different-slug",
            kind = NostrKinds.NAPPLET_NAMED,
            authorPubkey = cloner.pubKeyHex,
            authorNpub = cloner.npub,
            dTag = "totally-different-slug",
            title = "Mirrored Client",
            description = "Exact byte-identical Blossom bundle mirror.",
            iconGlyph = "⚡",
            accentHex = "#FF1493",
            indexSha256 = primarySha,
            isNsite = false,
            enableNip07 = false,
            isInstalled = false,
            updatedAt = 1900L
        )

        // Two distinct root Nsites (both dTag = "root") with distinct content must NOT stack together
        val rootSite1 = InstalledAppletEntity(
            appletId = "15128:${zineA.pubKeyHex}:root",
            kind = NostrKinds.NSITE_ROOT,
            authorPubkey = zineA.pubKeyHex,
            authorNpub = zineA.npub,
            dTag = "root",
            title = "Solaris Cyber Zine",
            description = "Independent visual synth essays published to Blossom.",
            iconGlyph = "🌐",
            accentHex = "#FF1493",
            indexSha256 = NostrCrypto.sha256Hex("zine-a"),
            isNsite = true,
            enableNip07 = true,
            isInstalled = false
        )
        val rootSite2 = InstalledAppletEntity(
            appletId = "15128:${zineB.pubKeyHex}:root",
            kind = NostrKinds.NSITE_ROOT,
            authorPubkey = zineB.pubKeyHex,
            authorNpub = zineB.npub,
            dTag = "root",
            title = "Lunar Field Notes",
            description = "Decentralized radio hardware schematics and logs.",
            iconGlyph = "🌐",
            accentHex = "#00F5D4",
            indexSha256 = NostrCrypto.sha256Hex("zine-b"),
            isNsite = true,
            enableNip07 = true,
            isInstalled = false
        )

        val nip51Bookmark = CuratedBookmarkEntity(
            coordinate = CatalogStacker.coordinateFor(primaryPulse),
            curatorPubkey = creator.pubKeyHex,
            curatorNpub = creator.npub,
            listSlug = "sovereign-curated-apps",
            listTitle = "SovereignShell Curated Picks",
            isLocalCurator = true
        )

        val groups = CatalogStacker.buildStackedGroups(
            items = listOf(authorVersion, communityFork, identicalClone, primaryPulse, rootSite1, rootSite2),
            bookmarks = listOf(nip51Bookmark),
            localPubkeyHex = creator.pubKeyHex
        )

        // Expect 3 groups total: 1 stacked PulseNotes group (4 variants) + 2 distinct root Nsite groups
        assertEquals(3, groups.size)

        val pulseGroup = groups.first { it.primary.appletId == primaryPulse.appletId }
        assertEquals(4, pulseGroup.stackCount)
        assertTrue(pulseGroup.hasMultipleEditions)
        assertTrue(pulseGroup.isCuratedNip51)
        assertTrue(pulseGroup.isLocalStarred)
        assertTrue(pulseGroup.categories.contains(GalleryCategory.NIP51_CURATED))

        val relations = pulseGroup.variants.associate { it.applet.appletId to it.relation }
        assertEquals(VariantRelation.PRIMARY, relations[primaryPulse.appletId])
        assertEquals(VariantRelation.SAME_AUTHOR_VERSION, relations[authorVersion.appletId])
        assertEquals(VariantRelation.COMMUNITY_FORK, relations[communityFork.appletId])
        assertEquals(VariantRelation.IDENTICAL_CLONE, relations[identicalClone.appletId])

        val rootGroups = groups.filter { it.primary.isNsite }
        assertEquals(2, rootGroups.size)
        assertFalse(rootGroups[0].hasMultipleEditions)
        assertFalse(rootGroups[1].hasMultipleEditions)
    }

    @Test
    fun externalSiteLauncherAndRealWebViewBootstrapAndMimeTypes_preserveStandardsAndGateways() {
        val publisher = NostrCrypto.generateKeyPair()
        val gameSha = NostrCrypto.sha256Hex("<!DOCTYPE html><html><body>2048 Link</body></html>")

        // Named NIP-5A Nsite (Kind 35128) -> single-label https://<pubkeyB36><dTag>.nsite.lol/
        val namedNsite = InstalledAppletEntity(
            appletId = "35128:${publisher.pubKeyHex}:2048-link",
            kind = NostrKinds.NSITE_NAMED,
            authorPubkey = publisher.pubKeyHex,
            authorNpub = publisher.npub,
            dTag = "2048-link",
            title = "2048 Link",
            description = "5x7 BreBre link puzzle game.",
            iconGlyph = "🎮",
            accentHex = "#FF1493",
            indexSha256 = gameSha,
            isNsite = true,
            enableNip07 = true
        )
        val expectedPubB36 = NostrCrypto.encodePubkeyBase36(publisher.pubKeyHex)
        assertEquals(
            "https://${expectedPubB36}2048-link.nsite.lol/",
            ExternalSiteLauncher.resolveExternalUrl(namedNsite)
        )

        // Root NIP-5A Nsite (Kind 15128) -> https://<npub>.nsite.lol/
        val rootNsite = namedNsite.copy(
            appletId = "15128:${publisher.pubKeyHex}:root",
            kind = NostrKinds.NSITE_ROOT,
            dTag = "root"
        )
        assertEquals(
            "https://${publisher.npub}.nsite.lol/",
            ExternalSiteLauncher.resolveExternalUrl(rootNsite)
        )

        // Single-file Blossom Napplet fallback -> https://<blossomServer>/<sha256>.html
        val blossomNapplet = namedNsite.copy(
            appletId = "35129:${publisher.pubKeyHex}:pixel",
            kind = NostrKinds.NAPPLET_NAMED,
            isNsite = false,
            blossomServer = "https://blossom.primal.net"
        )
        assertEquals(
            "https://blossom.primal.net/$gameSha.html",
            ExternalSiteLauncher.resolveExternalUrl(blossomNapplet)
        )

        // Runtime bootstrap MUST preserve <!DOCTYPE html> at index 0 (CSS Standards Mode)
        val originalHtml = "<!DOCTYPE html><html><head><title>2048 Link</title></head><body><canvas id='board'></canvas></body></html>"
        val strictNip5dHtml = injectRuntimeBootstrap(originalHtml, enableNip07 = false)
        assertTrue(strictNip5dHtml.startsWith("<!DOCTYPE html>"))
        assertTrue(strictNip5dHtml.contains("__SovereignNativeBridge.postRequest"))
        assertTrue(strictNip5dHtml.contains("delete window.nostr"))

        val nip07Html = injectRuntimeBootstrap(originalHtml, enableNip07 = true)
        assertTrue(nip07Html.startsWith("<!DOCTYPE html>"))
        assertTrue(nip07Html.contains("window.nostr = {"))

        // Bare MIME types required by Android WebResourceResponse for ES modules, CSS, and WASM
        assertEquals("text/javascript" to "utf-8", ShellRepository.guessMimeType("/assets/index-abc.js"))
        assertEquals("text/javascript" to "utf-8", ShellRepository.guessMimeType("/game.mjs"))
        assertEquals("text/css" to "utf-8", ShellRepository.guessMimeType("/styles/main.css"))
        assertEquals("application/wasm" to null, ShellRepository.guessMimeType("/engine.wasm"))
    }
}

