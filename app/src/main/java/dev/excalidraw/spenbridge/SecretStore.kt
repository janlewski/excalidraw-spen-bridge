package dev.excalidraw.spenbridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Stores the pairing secret wrapped by a non-exportable Android Keystore AES key. */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("bridge-secrets", Context.MODE_PRIVATE)
    private val keyAlias = "spen-bridge-pairing-v1"

    fun save(secret: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        prefs.edit()
            .putString("iv", encode(cipher.iv))
            .putString("secret", encode(cipher.doFinal(secret)))
            .apply()
    }

    fun load(): ByteArray? {
        if (!prefs.contains("secret") || !prefs.contains("iv")) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(prefs.getString("iv", "")!!)))
            cipher.doFinal(decode(prefs.getString("secret", "")!!))
        }.getOrNull()
    }

    /** Creates a secret only when none is stored. A failed decrypt must not silently rotate pairing. */
    fun rotate(): ByteArray {
        val created = ByteArray(32).also(SecureRandom()::nextBytes)
        save(created)
        return created
    }

    fun loadOrCreate(): ByteArray {
        load()?.let { return it }
        if (prefs.contains("secret")) {
            error("Stored pairing secret could not be decrypted. Clear app storage to pair again.")
        }
        val created = ByteArray(32).also(SecureRandom()::nextBytes)
        save(created)
        return created
    }

    private fun key(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(keyAlias, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
}
