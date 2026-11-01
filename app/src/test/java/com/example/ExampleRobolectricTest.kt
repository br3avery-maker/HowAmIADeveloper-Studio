package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.InstalledAppletEntity
import com.example.data.ShellRepository
import com.example.nostr.KeyVaultManager
import com.example.nostr.NostrCrypto
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.NsiteSpamFilter
import org.junit.Assert.assertEquals
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
}
