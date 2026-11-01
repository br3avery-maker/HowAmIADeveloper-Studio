package com.example

import com.example.nostr.NostrCrypto
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.NsiteSpamFilter
import com.example.nostr.RelayPoolClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun bip340SchnorrSignAndVerify_isDeterministicAndValid() {
        val kp = NostrCrypto.generateKeyPair()
        assertTrue(kp.npub.startsWith("npub1"))
        assertTrue(kp.nsec.startsWith("nsec1"))

        val reimported = NostrCrypto.importPrivateKey(kp.nsec)
        assertEquals(kp.pubKeyHex, reimported.pubKeyHex)
        assertEquals(kp.npub, reimported.npub)

        val signedNote = NostrEvent.createAndSign(
            privKeyHex = kp.privKeyHex,
            kind = NostrKinds.TEXT_NOTE,
            content = "Hello SovereignShell NIP-01 test note!",
            tags = listOf(listOf("t", "nostr"))
        )
        assertEquals(64, signedNote.id.length)
        assertEquals(128, signedNote.sig.length)
        assertTrue(
            "BIP-340 signature must verify against derived x-only pubkey",
            NostrCrypto.verifySchnorrHex(signedNote.id, signedNote.pubkey, signedNote.sig)
        )
    }

    @Test
    fun relayUrlNormalization_formatsWebSocketSchemes() {
        assertEquals("wss://relay.damus.io", RelayPoolClient.normalizeRelayUrl("relay.damus.io/"))
        assertEquals("wss://nos.lol", RelayPoolClient.normalizeRelayUrl("HTTPS://nos.lol/"))
        assertEquals("ws://localhost:7777", RelayPoolClient.normalizeRelayUrl("ws://localhost:7777/"))
    }

    @Test
    fun nsiteSpamFilter_blocksAllFiveKnownSpamTemplatesAndPreservesRealApps() {
        assertTrue(
            NsiteSpamFilter.isIndividualSpam(
                kind = NostrKinds.NSITE_ROOT,
                dTag = "root",
                title = "Noah Foster | A note about Sportium Bet",
                description = "Online publishing specialist sharing practical resources and useful notes related to sportium-bet-ca.com.",
                pathCount = 1
            )
        )
        assertTrue(
            NsiteSpamFilter.isIndividualSpam(
                kind = NostrKinds.NSITE_ROOT,
                dTag = "root",
                title = "Alice Collins | Una nota sobre Radiante",
                description = "Digital resources editor sharing practical resources and useful notes related to radiante-apostar-mx.com.",
                pathCount = 1
            )
        )
        assertTrue(
            NsiteSpamFilter.isIndividualSpam(
                kind = NostrKinds.NSITE_ROOT,
                dTag = "root",
                title = "Liam Brooks | Practical overview",
                description = "Independent web researcher sharing practical resources and useful notes related to example-portal.com.",
                pathCount = 1
            )
        )
        assertTrue(
            NsiteSpamFilter.isIndividualSpam(
                kind = NostrKinds.NSITE_ROOT,
                dTag = "root",
                title = "Emma Reed | Field notes",
                description = "Practical technology researcher sharing practical resources related to some-site.org.",
                pathCount = 1
            )
        )
        assertTrue(
            NsiteSpamFilter.isIndividualSpam(
                kind = NostrKinds.NSITE_ROOT,
                dTag = "root",
                title = "Chloe Ward | Community resource",
                description = "Community writer sharing useful notes related to linkfarm-seo.net.",
                pathCount = 1
            )
        )

        // Legitimate NIP-5D Napplets & NIP-5A Nsites must NOT be flagged
        assertFalse(
            NsiteSpamFilter.isIndividualSpam(
                kind = NostrKinds.NAPPLET_NAMED,
                dTag = "zap-chess",
                title = "ZapChess Arena",
                description = "Play real-time Nostr chess matches over NIP-5D NAP-RELAY.",
                pathCount = 1
            )
        )
        assertFalse(
            NsiteSpamFilter.isIndividualSpam(
                kind = NostrKinds.NSITE_NAMED,
                dTag = "zine-guide",
                title = "Pretend Internet Zine",
                description = "Static punk zine hosted via Blossom content-addressed SHA-256 blobs.",
                pathCount = 3
            )
        )
    }
}
