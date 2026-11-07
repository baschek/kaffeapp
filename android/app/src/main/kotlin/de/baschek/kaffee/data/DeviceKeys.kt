package de.baschek.kaffee.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import de.baschek.kaffee.core.api.DeviceKey
import de.baschek.kaffee.core.api.JcaDeviceKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Device key pairs (EC P-256) in the Android Keystore: non-exportable, StrongBox when
 * available (otherwise the TEE), no user-authentication requirement because syncing
 * runs in the background. Each pairing uses its own alias.
 */
class DeviceKeys {
    private val cache = ConcurrentHashMap<String, DeviceKey>()

    /** Generates a new key pair under [alias] (replacing any key with that alias). */
    fun generate(alias: String): DeviceKey {
        delete(alias)
        val pair = try {
            generator(alias, strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generator(alias, strongBox = false)
        }
        return JcaDeviceKey(pair.private, pair.public).also { cache[alias] = it }
    }

    fun load(alias: String): DeviceKey? {
        cache[alias]?.let { return it }
        val ks = keyStore()
        val private = ks.getKey(alias, null) as? PrivateKey ?: return null
        val public = ks.getCertificate(alias)?.publicKey ?: return null
        return JcaDeviceKey(private, public).also { cache[alias] = it }
    }

    fun delete(alias: String) {
        cache.remove(alias)
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun generator(alias: String, strongBox: Boolean) =
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .setIsStrongBoxBacked(strongBox)
                    .build(),
            )
            generateKeyPair()
        }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
