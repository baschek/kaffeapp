package de.baschek.kaffee.core.api

import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The device key pair used to prove possession on every authenticated request (docs/sync-protocol.md, credential A).
 * On Android the private key lives in the Keystore and cannot be exported; tests use a software key.
 */
interface DeviceKey {
    /** SubjectPublicKeyInfo (X.509 DER) of the public key, base64url without padding. */
    fun publicKeySpkiBase64Url(): String

    /** ECDSA P-256 / SHA-256 signature, DER encoded. */
    fun sign(message: ByteArray): ByteArray
}

class JcaDeviceKey(private val privateKey: PrivateKey, private val publicKey: PublicKey) : DeviceKey {
    override fun publicKeySpkiBase64Url(): String = Base64Url.encode(publicKey.encoded)

    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(message)
        sign()
    }
}

object Base64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

object SignedHeaders {
    const val TIMESTAMP = "X-Kaffee-Timestamp"
    const val NONCE = "X-Kaffee-Nonce"
    const val SIGNATURE = "X-Kaffee-Signature"
}

/** Canonical string `METHOD\nPATH\nQUERY\nTIMESTAMP\nNONCE\nSHA256_HEX(body)` and the signature headers. */
class RequestSigner(
    private val key: DeviceKey,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nonce: () -> String = ::randomNonce,
) {
    fun headers(method: String, path: String, query: String, body: ByteArray): Map<String, String> {
        val timestamp = nowSeconds().toString()
        val n = nonce()
        val signature = key.sign(canonicalString(method, path, query, timestamp, n, body).toByteArray(Charsets.UTF_8))
        return mapOf(SignedHeaders.TIMESTAMP to timestamp, SignedHeaders.NONCE to n, SignedHeaders.SIGNATURE to Base64Url.encode(signature))
    }

    companion object {
        fun canonicalString(method: String, path: String, query: String, timestamp: String, nonce: String, body: ByteArray): String =
            listOf(method.uppercase(), path, query, timestamp, nonce, sha256Hex(body)).joinToString("\n")

        fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        fun randomNonce(): String = Base64Url.encode(ByteArray(16).also { SecureRandom().nextBytes(it) })

        fun verify(publicKeySpkiBase64Url: String, message: ByteArray, signatureBase64Url: String): Boolean = try {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64Url.decode(publicKeySpkiBase64Url)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(message)
                verify(Base64Url.decode(signatureBase64Url))
            }
        } catch (e: Exception) {
            false
        }
    }
}

class InvalidBaseUrlException(val reason: String) : IllegalArgumentException(reason)

/** Validation of the server address typed in at pairing: HTTPS only (debug builds: http for local hosts). */
object BaseUrl {
    private val LOCAL_HOSTS = setOf("localhost", "10.0.2.2", "127.0.0.1")

    fun normalize(input: String, allowInsecureLocal: Boolean = false): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw InvalidBaseUrlException("empty")
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = try { URI(withScheme) } catch (e: Exception) { throw InvalidBaseUrlException("malformed") }
        val host = uri.host?.lowercase() ?: throw InvalidBaseUrlException("no_host")
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) throw InvalidBaseUrlException("unsupported_parts")
        when (uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" -> if (!(allowInsecureLocal && host in LOCAL_HOSTS)) throw InvalidBaseUrlException("https_required")
            else -> throw InvalidBaseUrlException("https_required")
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme.lowercase()}://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
    }
}
