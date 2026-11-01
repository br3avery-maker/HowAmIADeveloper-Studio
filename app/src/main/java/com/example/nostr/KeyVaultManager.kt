package com.example.nostr

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class KeyVaultManager(context: Context) {
    private val appContext = context.applicationContext
    private val fallbackPrefs by lazy {
        appContext.getSharedPreferences("sovereign_vault_fallback", Context.MODE_PRIVATE)
    }

    data class EncryptedSecret(
        val ciphertextBase64: String,
        val ivBase64: String
    )

    private fun getOrCreateMasterKey(): SecretKey {
        return runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) return@runCatching existing.secretKey

            val keyGen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        }.getOrElse {
            // Fallback for JVM/Robolectric unit test environments without AndroidKeyStore hardware
            val encoded = fallbackPrefs.getString("local_kek", null)
            val raw = if (encoded != null) {
                Base64.decode(encoded, Base64.NO_WRAP)
            } else {
                ByteArray(32).also {
                    SecureRandom().nextBytes(it)
                    fallbackPrefs.edit()
                        .putString("local_kek", Base64.encodeToString(it, Base64.NO_WRAP))
                        .apply()
                }
            }
            SecretKeySpec(raw, "AES")
        }
    }

    fun encryptPrivateKeyHex(privKeyHex: String): EncryptedSecret {
        val secretKey = getOrCreateMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val ciphertext = cipher.doFinal(privKeyHex.toByteArray(Charsets.UTF_8))
        return EncryptedSecret(
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        )
    }

    fun decryptPrivateKeyHex(ciphertextBase64: String, ivBase64: String): String {
        val secretKey = getOrCreateMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        private const val KEY_ALIAS = "sovereign_shell_nsec_master_key_v1"
    }
}
