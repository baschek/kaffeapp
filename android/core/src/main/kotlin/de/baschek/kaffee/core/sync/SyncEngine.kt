package de.baschek.kaffee.core.sync

import de.baschek.kaffee.core.api.KaffeeApi
import de.baschek.kaffee.core.api.SyncApiException
import kotlinx.serialization.json.JsonObject

/** What the engine needs from local storage (Room on Android, in-memory in tests). */
interface SyncStore {
    /** Events created on this device and not yet acknowledged by the server, in creation order. */
    suspend fun outbox(): List<JsonObject>

    /** Server acknowledged (applied or duplicate): keep the event, it is no longer pending. */
    suspend fun acknowledge(eventIds: List<String>)

    /** Server refused: remove the event from journal and outbox and remember the notice for the user. */
    suspend fun reject(rejections: List<Rejection>)

    /** Store pulled events (ignore ids already known) and the new cursor, in one transaction. */
    suspend fun applyRemote(events: List<JsonObject>, cursor: Long)

    /** Replace the whole local journal by the server snapshot; pending outbox events stay and are pushed again. */
    suspend fun replaceWithSnapshot(document: JsonObject, beanLinks: Map<String, String>, cursor: Long)

    suspend fun cursor(): Long?
}

data class Rejection(val eventId: String, val reason: String)

data class SyncResult(val pushed: Int, val pulled: Int, val rejected: List<Rejection>, val snapshot: Boolean)

/**
 * Sync algorithm of docs/sync-protocol.md: (1) push the outbox in creation order (batches of 200), (2) pull events
 * since the cursor until has_more=false, (3) no cursor yet or 410 resync_required -> snapshot.
 */
class SyncEngine(private val api: KaffeeApi, private val store: SyncStore) {
    suspend fun sync(): SyncResult {
        var pushed = 0
        val rejected = mutableListOf<Rejection>()
        for (batch in store.outbox().chunked(MAX_PUSH)) {
            val response = api.push(batch)
            val acknowledged = response.results.filter { it.status == "applied" || it.status == "duplicate" }.map { it.eventId }
            val refused = response.results.filter { it.status == "rejected" }
                .map { Rejection(it.eventId, it.reason ?: "Buchung passt nicht zum Bestand") }
            store.acknowledge(acknowledged)
            if (refused.isNotEmpty()) store.reject(refused)
            pushed += acknowledged.size
            rejected += refused
        }
        var pulled = 0
        var cursor = store.cursor()
        if (cursor == null) return snapshot(pushed, rejected)
        try {
            while (true) {
                val page = api.events(cursor!!)
                store.applyRemote(page.events.map { it.event }, page.nextSince)
                pulled += page.events.size
                cursor = page.nextSince
                if (!page.hasMore) break
            }
        } catch (e: SyncApiException.ResyncRequired) {
            return snapshot(pushed, rejected)
        }
        return SyncResult(pushed, pulled, rejected, snapshot = false)
    }

    private suspend fun snapshot(pushed: Int, rejected: List<Rejection>): SyncResult {
        val snapshot = api.snapshot()
        store.replaceWithSnapshot(snapshot.document, snapshot.beanLinks, snapshot.seq)
        return SyncResult(pushed, (snapshot.document["events"] as? List<*>)?.size ?: 0, rejected, snapshot = true)
    }

    companion object {
        const val MAX_PUSH = 200
    }
}
