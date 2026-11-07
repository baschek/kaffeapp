package de.baschek.kaffee.core

import de.baschek.kaffee.core.api.EventsResponse
import de.baschek.kaffee.core.api.KaffeeApi
import de.baschek.kaffee.core.api.PushResponse
import de.baschek.kaffee.core.api.PushResult
import de.baschek.kaffee.core.api.RequestSigner
import de.baschek.kaffee.core.api.JcaDeviceKey
import de.baschek.kaffee.core.api.KtorKaffeeApi
import de.baschek.kaffee.core.api.SeqEvent
import de.baschek.kaffee.core.api.SnapshotResponse
import de.baschek.kaffee.core.api.StatusResponse
import de.baschek.kaffee.core.api.SyncApiException
import de.baschek.kaffee.core.api.createHttpClient
import de.baschek.kaffee.core.sync.Rejection
import de.baschek.kaffee.core.sync.SyncEngine
import de.baschek.kaffee.core.sync.SyncStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineTest {
    private fun event(id: String) = buildJsonObject { put("id", id); put("type", "JAR_CLEANED") }

    private class MemoryStore(var pending: MutableList<JsonObject>, var cursor: Long?) : SyncStore {
        val journal = mutableListOf<String>()
        val rejected = mutableListOf<Rejection>()
        var snapshots = 0
        override suspend fun outbox() = pending.toList()
        override suspend fun acknowledge(eventIds: List<String>) { pending.removeAll { (it["id"] as JsonPrimitive).content in eventIds } }
        override suspend fun reject(rejections: List<Rejection>) {
            rejected += rejections
            pending.removeAll { p -> rejections.any { it.eventId == (p["id"] as JsonPrimitive).content } }
        }
        override suspend fun applyRemote(events: List<JsonObject>, cursor: Long) {
            journal += events.map { (it["id"] as JsonPrimitive).content }; this.cursor = cursor
        }
        override suspend fun replaceWithSnapshot(document: JsonObject, beanLinks: Map<String, String>, cursor: Long) {
            snapshots++; this.cursor = cursor
        }
        override suspend fun cursor() = cursor
    }

    private class FakeApi(val resyncOnce: Boolean = false) : KaffeeApi {
        val pushed = mutableListOf<List<String>>()
        var resync = resyncOnce
        override suspend fun status() = StatusResponse(3)
        override suspend fun snapshot() = SnapshotResponse(9, buildJsonObject { put("events", buildJsonArray { }) })
        override suspend fun events(since: Long, limit: Int): EventsResponse {
            if (resync) { resync = false; throw SyncApiException.ResyncRequired() }
            return if (since < 2) EventsResponse(listOf(SeqEvent(since + 1, "web", buildJsonObject { put("id", "r$since") })), since + 1, true)
            else EventsResponse(emptyList(), since, false)
        }
        override suspend fun push(events: List<JsonObject>): PushResponse {
            val ids = events.map { (it["id"] as JsonPrimitive).content }
            pushed += ids
            return PushResponse(ids.map { if (it == "bad") PushResult(it, "rejected", "S01 ist nicht frei") else PushResult(it, "applied") }, 2)
        }
    }

    @Test
    fun pushesOutboxRemovesRejectedAndPullsUntilDone() = runTest {
        val store = MemoryStore(mutableListOf(event("a"), event("bad"), event("b")), cursor = 0)
        val api = FakeApi()
        val result = SyncEngine(api, store).sync()
        assertEquals(listOf(listOf("a", "bad", "b")), api.pushed)
        assertEquals(2, result.pushed)
        assertEquals(listOf(Rejection("bad", "S01 ist nicht frei")), result.rejected)
        assertTrue(store.pending.isEmpty())
        assertEquals(listOf("r0", "r1"), store.journal)
        assertEquals(2L, store.cursor)
    }

    @Test
    fun firstRunAndResyncUseTheSnapshot() = runTest {
        val first = MemoryStore(mutableListOf(), cursor = null)
        assertTrue(SyncEngine(FakeApi(), first).sync().snapshot)
        val resync = MemoryStore(mutableListOf(), cursor = 5)
        assertTrue(SyncEngine(FakeApi(resyncOnce = true), resync).sync().snapshot)
        assertEquals(9L, resync.cursor)
    }

    @Test
    fun requestsAreSignedOverTheKaffeePath() = runTest {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key = JcaDeviceKey(pair.private, pair.public)
        var seen: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            seen = request
            respond("""{"seq":4,"events":2}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = KtorKaffeeApi(createHttpClient(engine), { "https://pi.example/base" }, { "tok" }, { RequestSigner(key) })
        assertEquals(4L, api.status().seq)
        val request = seen!!
        assertEquals("/base/kaffee/v1/status", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        val message = RequestSigner.canonicalString("GET", "/base/kaffee/v1/status", "", request.headers["X-Kaffee-Timestamp"]!!,
            request.headers["X-Kaffee-Nonce"]!!, ByteArray(0))
        assertTrue(RequestSigner.verify(key.publicKeySpkiBase64Url(), message.toByteArray(), request.headers["X-Kaffee-Signature"]!!))
    }
}
