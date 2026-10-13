package com.example.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
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

    @Volatile
    private var lastReadError: String? = null

    fun isConfigured(): Boolean =
        !preferences.getString(TOKEN_KEY, null).isNullOrBlank()

    fun lastReadErrorMessage(): String? = lastReadError

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
        lastReadError = null
    }

    fun read(): String? {
        val packedText = preferences.getString(TOKEN_KEY, null) ?: run {
            lastReadError = null
            return null
        }

        // Decode/validate the stored blob separately so only a clearly malformed blob is
        // deleted here. Unrelated Keystore/provider exceptions must not erase the user's key.
        val packed = try {
            Base64.decode(packedText, Base64.NO_WRAP).also {
                require(it.size > GCM_IV_BYTES + GCM_TAG_BYTES) { "Secret blob is invalid." }
            }
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Stored SerpApi token is malformed; removing unusable encrypted data.", error)
            clearStoredToken()
            lastReadError = "La clave cifrada guardada está dañada. Vuelve a introducir tu clave de SerpApi."
            return null
        } catch (error: Exception) {
            Log.e(TAG, "Could not decode stored SerpApi token; encrypted data has been preserved.", error)
            lastReadError = "No se pudo leer la clave guardada por un error interno. No se ha borrado; vuelve a intentarlo."
            return null
        }

        return try {
            val iv = packed.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = packed.copyOfRange(GCM_IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val token = cipher.doFinal(ciphertext).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
            if (token == null) {
                Log.e(TAG, "Stored SerpApi token decrypted to an empty value; removing unusable encrypted data.")
                clearStoredToken()
                lastReadError = "La clave guardada está vacía o dañada. Vuelve a introducir tu clave de SerpApi."
            } else {
                lastReadError = null
            }
            token
        } catch (error: AEADBadTagException) {
            // Authentication failure means the saved ciphertext cannot be decrypted by this
            // Keystore key (e.g. the key was lost/restored on another device).
            Log.e(TAG, "SerpApi token authentication failed; removing unusable encrypted data.", error)
            clearStoredToken()
            lastReadError = "Android Keystore ya no puede descifrar la clave guardada. Vuelve a introducir tu clave de SerpApi."
            null
        } catch (error: KeyPermanentlyInvalidatedException) {
            Log.e(TAG, "Android Keystore key is permanently invalidated; removing unusable token.", error)
            clearStoredToken(deleteKeystoreKey = true)
            lastReadError = "Android ha invalidado la clave de cifrado. Vuelve a introducir tu clave de SerpApi."
            null
        } catch (error: UnrecoverableKeyException) {
            Log.e(TAG, "Android Keystore key is unrecoverable; removing unusable token.", error)
            clearStoredToken(deleteKeystoreKey = true)
            lastReadError = "Android Keystore no puede recuperar la clave de cifrado. Vuelve a introducir tu clave de SerpApi."
            null
        } catch (error: Exception) {
            // Do not assume every Keystore/provider exception proves the ciphertext is corrupt.
            // Log the cause but preserve the encrypted token for retry and diagnosis.
            Log.e(TAG, "Failed to read SerpApi token; encrypted data has been preserved.", error)
            lastReadError = "No se pudo acceder a Android Keystore. La clave guardada no se ha borrado; inténtalo de nuevo."
            null
        }
    }

    fun clear() {
        preferences.edit().remove(TOKEN_KEY).commit()
        lastReadError = null
    }

    private fun clearStoredToken(deleteKeystoreKey: Boolean = false) {
        preferences.edit().remove(TOKEN_KEY).commit()
        if (deleteKeystoreKey) {
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            }.onFailure { error ->
                Log.e(TAG, "Could not remove invalid Android Keystore entry.", error)
            }
        }
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
        const val TAG = "SerpApiTokenStore"
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
