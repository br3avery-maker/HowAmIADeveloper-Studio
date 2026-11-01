package com.example

import com.example.nostr.NostrCrypto
import com.example.nostr.NostrEvent
import com.example.nostr.NostrKinds
import com.example.nostr.RelayPoolClient
import org.junit.Assert.assertEquals
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
}
