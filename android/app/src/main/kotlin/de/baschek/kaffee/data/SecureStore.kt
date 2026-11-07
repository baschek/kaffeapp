package de.baschek.kaffee.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the pairing result. The token is encrypted with an AES-GCM key that
 * lives in the Android Keystore and never leaves the device. Neither the token
 * nor the server address is ever logged.
 */
class SecureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val _paired = MutableStateFlow(readToken() != null && baseUrl() != null && keyAlias() != null)

    /** True while a server address and a usable token are stored. */
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    fun baseUrl(): String? = prefs.getString(KEY_URL, null)

    fun token(): String? = readToken()

    /** Alias of the device key (in the Android Keystore) belonging to this pairing. */
    fun keyAlias(): String? = prefs.getString(KEY_ALIAS, null)

    fun save(baseUrl: String, token: String, keyAlias: String) {
        val encrypted = encrypt(token)
        prefs.edit().putString(KEY_URL, baseUrl).putString(KEY_TOKEN, encrypted).putString(KEY_ALIAS, keyAlias).apply()
        _paired.value = true
    }

    fun clear() {
        prefs.edit().clear().apply()
        _paired.value = false
    }

    private fun readToken(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
        return String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_URL = "base_url"
        const val KEY_TOKEN = "token_enc"
        const val KEY_ALIAS = "device_key_alias"
        const val ALIAS = "kaffee_pairing_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
