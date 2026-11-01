package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.nostr.KeyVaultManager
import com.example.nostr.NostrCrypto
import org.junit.Assert.assertEquals
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
        org.junit.Assert.assertTrue(naddr.startsWith("naddr1"))
    }
}
