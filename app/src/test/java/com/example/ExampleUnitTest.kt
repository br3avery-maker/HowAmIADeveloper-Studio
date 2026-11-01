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

    @Test
    fun nip5aPubkeyBase36AndNamedNsiteGatewayUrl_produceValidSingleLabelSubdomain() {
        val targetNpub = "npub1s06cx40d060vq887lj8hj6hku5a6hzch5ylkzj03qv86dn02tqhs0wzl0d"
        val pubHex = NostrCrypto.decodeNpubToHex(targetNpub)!!
        assertEquals(64, pubHex.length)

        val pubB36 = NostrCrypto.encodePubkeyBase36(pubHex)
        assertEquals(50, pubB36.length)
        assertTrue(pubB36.all { it in '0'..'9' || it in 'a'..'z' })
        assertEquals(pubHex, NostrCrypto.decodePubkeyBase36(pubB36))

        // Leading-zero 32-byte pubkey must still be fixed-width 50 chars so <pubkeyB36><dTag> splits at index 50
        val leadingZeroHex = "0000001234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        val paddedB36 = NostrCrypto.encodePubkeyBase36(leadingZeroHex)
        assertEquals(50, paddedB36.length)
        assertTrue(paddedB36.startsWith("0"))
        assertEquals(leadingZeroHex, NostrCrypto.decodePubkeyBase36(paddedB36))

        // Max 256-bit value must also fit in 50 base36 chars
        val maxHex = "f".repeat(64)
        val maxB36 = NostrCrypto.encodePubkeyBase36(maxHex)
        assertEquals(50, maxB36.length)
        assertEquals(maxHex, NostrCrypto.decodePubkeyBase36(maxB36))

        // Verify 2048-link resolves to a single-label *.nsite.lol URL (59-char label, zero extra dots)
        val namedUrl = com.example.nostr.ExternalSiteLauncher.buildNip5aGatewayUrl(
            kind = NostrKinds.NSITE_NAMED,
            authorPubkeyHex = pubHex,
            authorNpub = targetNpub,
            dTag = "2048-link"
        )!!
        assertEquals("https://${pubB36}2048-link.nsite.lol/", namedUrl)

        val host = java.net.URI(namedUrl).host
        val labels = host.split('.')
        assertEquals("Wildcard *.nsite.lol requires exactly 3 host labels (<sublabel>.nsite.lol)", 3, labels.size)
        assertEquals(59, labels[0].length)
        assertEquals(pubHex, NostrCrypto.decodePubkeyBase36(labels[0].substring(0, 50)))
        assertEquals("2048-link", labels[0].substring(50))

        // Legacy multi-level <dTag>.<npub>.nsite.lol URLs are automatically repaired
        val legacyBroken = "https://2048-link.$targetNpub.nsite.lol"
        assertEquals(
            "https://${pubB36}2048-link.nsite.lol/",
            com.example.nostr.ExternalSiteLauncher.normalizeGatewayUrl(legacyBroken)
        )
    }

    @Test
    fun featuredCreatorPubkeyAndPublisherResolution_handlesNpubHexAndSearchTerms() {
        val creatorNpub = NostrCrypto.DEFAULT_FEATURED_CREATOR_NPUB
        val creatorHex = NostrCrypto.DEFAULT_FEATURED_CREATOR_PUBKEY_HEX

        assertEquals("npub1s06cx40d060vq887lj8hj6hku5a6hzch5ylkzj03qv86dn02tqhs0wzl0d", creatorNpub)
        assertEquals(64, creatorHex.length)
        assertEquals(creatorNpub, NostrCrypto.encodeNpub(creatorHex))

        assertEquals(creatorHex, NostrCrypto.resolvePubkeyHex(creatorNpub))
        assertEquals(creatorHex, NostrCrypto.resolvePubkeyHex("nostr:$creatorNpub"))
        assertEquals(creatorHex, NostrCrypto.resolvePubkeyHex(creatorHex.uppercase()))
        org.junit.Assert.assertNull(NostrCrypto.resolvePubkeyHex("2048 link puzzle"))
        org.junit.Assert.assertNull(NostrCrypto.resolvePubkeyHex(""))
    }
}


