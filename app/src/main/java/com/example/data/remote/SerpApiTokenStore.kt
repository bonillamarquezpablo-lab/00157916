package com.example.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts a personal SerpApi key at rest with AES-GCM and an Android Keystore key.
 *
 * This is for a private, single-device prototype. Do not distribute an APK containing a user's
 * key or use this client-side design for a public multi-user app. A public release should proxy
 * SerpApi calls through a backend with the provider key stored server-side.
 */
class SerpApiTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isConfigured(): Boolean = preferences.contains(TOKEN_KEY)

    fun save(token: String) {
        val trimmed = token.trim()
        require(trimmed.isNotEmpty()) { "La clave de SerpApi no puede estar vacía." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(cipher.iv.size + ciphertext.size)
            .put(cipher.iv)
            .put(ciphertext)
            .array()

        check(preferences.edit().putString(TOKEN_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "No se pudo guardar la clave de forma segura."
        }
    }

    fun read(): String? {
        val packedText = preferences.getString(TOKEN_KEY, null) ?: return null
        return try {
            val packed = Base64.decode(packedText, Base64.NO_WRAP)
            require(packed.size > GCM_IV_BYTES + GCM_TAG_BYTES) { "Secret blob is invalid." }
            val iv = packed.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = packed.copyOfRange(GCM_IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // Android Keystore keys do not transfer to a different device in an app-data backup.
            preferences.edit().remove(TOKEN_KEY).commit()
            null
        }
    }

    fun clear() {
        preferences.edit().remove(TOKEN_KEY).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "minimo_serpapi_secret"
        const val TOKEN_KEY = "encrypted_token"
        const val KEY_ALIAS = "minimo_serpapi_aes_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = 128
    }
}
