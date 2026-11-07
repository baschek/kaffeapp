package de.baschek.kaffee.core.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI

/** Wire format of /kaffee/v1 (docs/sync-protocol.md). Events stay raw JSON objects (the app's InventoryEvent). */
val WireJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

@Serializable data class PairRequest(val code: String, @SerialName("device_name") val deviceName: String, @SerialName("public_key") val publicKey: String)
@Serializable data class PairResponse(val token: String, @SerialName("device_id") val deviceId: String)
@Serializable data class StatusResponse(val seq: Long, val events: Long = 0, @SerialName("server_time") val serverTime: String? = null)
@Serializable data class SnapshotResponse(val seq: Long, val document: JsonObject, @SerialName("bean_links") val beanLinks: Map<String, String> = emptyMap())
@Serializable data class SeqEvent(val seq: Long, val origin: String = "", val event: JsonObject)
@Serializable data class EventsResponse(val events: List<SeqEvent>, @SerialName("next_since") val nextSince: Long, @SerialName("has_more") val hasMore: Boolean)
@Serializable data class PushRequest(val events: List<JsonObject>)
@Serializable data class PushResult(@SerialName("event_id") val eventId: String, val status: String, val reason: String? = null)
@Serializable data class PushResponse(val results: List<PushResult>, val seq: Long)

interface KaffeeApi {
    suspend fun status(): StatusResponse
    suspend fun snapshot(): SnapshotResponse
    suspend fun events(since: Long, limit: Int = 500): EventsResponse
    suspend fun push(events: List<JsonObject>): PushResponse
}

/** Failures of the sync API. Messages never contain tokens or request bodies. */
sealed class SyncApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 404 on an authenticated call: revoked, expired or replaced; looks identical for every cause by design. */
    class NotFound : SyncApiException("not_found")
    class Unauthorized : SyncApiException("unauthorized")
    /** 404 while pairing: no pairing window is open (or another device is active), or the code is wrong. */
    class PairingUnavailable : SyncApiException("pairing_unavailable")
    class ResyncRequired : SyncApiException("resync_required")
    class RateLimited(val retryAfterSeconds: Int?) : SyncApiException("rate_limited")
    class Http(val code: Int, val error: String?) : SyncApiException("http_$code${error?.let { ":$it" } ?: ""}")
    class Network(cause: Throwable) : SyncApiException("network: ${cause::class.simpleName}", cause)
    class BadResponse(detail: String) : SyncApiException("bad_response: $detail")
    class NotConfigured : SyncApiException("not_configured")
}

fun createHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
    // Deliberately no Logging plugin: headers contain the bearer token.
}

class KtorKaffeeApi(
    private val client: HttpClient,
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val signer: () -> RequestSigner?,
) : KaffeeApi {
    override suspend fun status() = get("status", StatusResponse.serializer())
    override suspend fun snapshot() = get("snapshot", SnapshotResponse.serializer())
    override suspend fun events(since: Long, limit: Int) =
        get("events?since=$since&limit=${limit.coerceIn(1, 500)}", EventsResponse.serializer())

    override suspend fun push(events: List<JsonObject>): PushResponse {
        val body = WireJson.encodeToString(PushRequest.serializer(), PushRequest(events))
        return send(HttpMethod.Post, "push", body, PushResponse.serializer())
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T = send(HttpMethod.Get, path, null, serializer)

    private suspend fun <T> send(method: HttpMethod, path: String, body: String?, serializer: KSerializer<T>): T {
        val base = baseUrl() ?: throw SyncApiException.NotConfigured()
        val bearer = token() ?: throw SyncApiException.NotConfigured()
        val requestSigner = signer() ?: throw SyncApiException.NotConfigured()
        return execute(client, base, method, path, body, bearer, serializer, requestSigner = requestSigner)
    }

    companion object {
        const val PREFIX = "/kaffee/v1/"

        suspend fun pair(client: HttpClient, baseUrl: String, code: String, deviceName: String, publicKey: String): PairResponse {
            val body = WireJson.encodeToString(PairRequest.serializer(), PairRequest(code.trim(), deviceName, publicKey))
            return execute(client, baseUrl, HttpMethod.Post, "pair", body, null, PairResponse.serializer(), pairing = true)
        }

        /** Path that is signed: the base URL's own path plus /kaffee/v1/<path>, without host and query. */
        fun signedPath(base: String, path: String): String =
            (runCatching { URI(base).rawPath }.getOrNull().orEmpty().trimEnd('/')) + PREFIX + path.substringBefore('?')

        private suspend fun <T> execute(
            client: HttpClient, base: String, method: HttpMethod, path: String, body: String?, bearer: String?,
            serializer: KSerializer<T>, pairing: Boolean = false, requestSigner: RequestSigner? = null,
        ): T {
            val response: HttpResponse
            val text: String
            try {
                val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                val query = if ('?' in path) path.substringAfter('?') else ""
                response = client.request {
                    this.method = method
                    url("${base.trimEnd('/')}$PREFIX$path")
                    header(HttpHeaders.Accept, "application/json")
                    if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                    requestSigner?.headers(method.value, signedPath(base, path), query, bodyBytes)?.forEach { (k, v) -> header(k, v) }
                    if (body != null) setBody(ByteArrayContent(bodyBytes, ContentType.Application.Json))
                }
                text = response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SyncApiException.Network(e)
            }
            val code = response.status.value
            if (code in 200..299) {
                return try {
                    WireJson.decodeFromString(serializer, text)
                } catch (e: Exception) {
                    throw SyncApiException.BadResponse("undecodable ${e::class.simpleName}")
                }
            }
            throw when (code) {
                404 -> if (pairing) SyncApiException.PairingUnavailable() else SyncApiException.NotFound()
                401 -> if (pairing) SyncApiException.PairingUnavailable() else SyncApiException.Unauthorized()
                410 -> SyncApiException.ResyncRequired()
                429 -> SyncApiException.RateLimited(response.headers[HttpHeaders.RetryAfter]?.toIntOrNull())
                else -> SyncApiException.Http(code, errorCode(text))
            }
        }

        private fun errorCode(text: String): String? = try {
            ((WireJson.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull?.take(64)
        } catch (e: Exception) {
            null
        }
    }
}
