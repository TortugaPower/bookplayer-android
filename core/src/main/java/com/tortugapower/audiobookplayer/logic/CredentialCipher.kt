package com.tortugapower.audiobookplayer.logic

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts external-server credentials (tokens, custom header values) at rest with an AES/GCM key
 * held in the Android Keystore, so they never sit in the Room database as plaintext — the parity of
 * iOS storing its media-server connections in the Keychain.
 *
 * Ciphertext is versioned with a prefix. Anything unprefixed or undecryptable resolves to an empty
 * secret: requests then fail auth and the user re-adds the server. There is deliberately no
 * plaintext fallback — this feature has never shipped, so no production rows predate encryption.
 */
object CredentialCipher {
    private const val KEY_ALIAS = "external_server_credentials"
    private const val PREFIX = "bpenc1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    // Cached after the first load: Keystore access is binder IPC, and decrypt runs once per
    // credential value per emission — without the cache that's many round-trips per list read.
    @Volatile
    private var cachedKey: SecretKey? = null

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val key = existing ?: run {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generator.generateKey()
            }
            cachedKey = key
            return key
        }
    }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) {
            android.util.Log.e("CredentialCipher", "Stored credential is not in the expected format")
            return ""
        }
        return try {
            val payload = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.copyOfRange(0, GCM_IV_LENGTH))
            )
            String(cipher.doFinal(payload, GCM_IV_LENGTH, payload.size - GCM_IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            // Keystore key lost/invalidated: the credential is unrecoverable. Return an empty
            // secret so requests fail auth and the user is pushed to re-add the server, instead
            // of leaking ciphertext into a request header.
            android.util.Log.e("CredentialCipher", "Failed to decrypt stored credential", e)
            ""
        }
    }
}
