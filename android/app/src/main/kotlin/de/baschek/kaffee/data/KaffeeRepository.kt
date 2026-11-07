package de.baschek.kaffee.data

import de.baschek.kaffee.core.domain.DomainError
import de.baschek.kaffee.core.domain.InventoryState
import de.baschek.kaffee.core.domain.Obj
import de.baschek.kaffee.core.domain.createEmptyDocument
import de.baschek.kaffee.core.domain.defaultSettings
import de.baschek.kaffee.core.domain.effectiveSettings
import de.baschek.kaffee.core.domain.isoNow
import de.baschek.kaffee.core.domain.obj
import de.baschek.kaffee.core.domain.parseObj
import de.baschek.kaffee.core.domain.reduceInventory
import de.baschek.kaffee.core.domain.s
import de.baschek.kaffee.core.domain.toJsonString
import de.baschek.kaffee.core.domain.toNative
import de.baschek.kaffee.core.sync.Rejection
import de.baschek.kaffee.core.sync.SyncStore
import de.baschek.kaffee.data.db.EventEntity
import de.baschek.kaffee.data.db.KaffeeDao
import de.baschek.kaffee.data.db.MetaEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The journal plus everything derived from it (never stored). */
data class Inventory(
    val document: Obj,
    val state: InventoryState,
    val settings: Obj,
    val pendingIds: Set<String>,
    val beanLinks: Map<String, String>,
)

/**
 * Local journal in Room. New bookings are checked against the stock exactly like on the Pi (no new derivation
 * warning) and go into the outbox; [onBooked] triggers a sync.
 */
class KaffeeRepository(private val dao: KaffeeDao, scope: CoroutineScope, private val onBooked: () -> Unit) {
    private val writeLock = Mutex()

    val inventory: StateFlow<Inventory?> = combine(dao.observeEvents(), dao.observeMeta()) { events, metas ->
        val meta = metas.associate { it.key to it.value }
        val document = buildDocument(events, meta)
        Inventory(document, reduceInventory(document), effectiveSettings(document), events.filter { it.pending }.map { it.id }.toSet(),
            meta[BEAN_LINKS]?.let { decodeLinks(it) } ?: emptyMap())
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    val pendingCount: Flow<Int> = dao.observePendingCount()
    val notices: Flow<List<String>> = dao.observeMeta().map { metas ->
        metas.firstOrNull { it.key == NOTICES }?.value?.let { text -> text.split('\n').filter { it.isNotBlank() } } ?: emptyList()
    }

    /** Append new events after checking them against the current stock. Throws [DomainError] with the reason. */
    suspend fun append(events: List<Obj>) = writeLock.withLock {
        val current = buildDocument(dao.events(), metaMap())
        val before = reduceInventory(current).warnings
        @Suppress("UNCHECKED_CAST")
        val combined = LinkedHashMap(current).apply { this["events"] = ((current["events"] as List<Any?>) + events).toMutableList() }
        val after = reduceInventory(combined).warnings
        val fresh = after.toMutableList().apply { before.forEach { remove(it) } }
        if (fresh.isNotEmpty()) throw DomainError(fresh.joinToString("; ") { it.substringAfter(": ") })
        val start = dao.maxPosition()
        dao.insert(events.mapIndexed { index, event ->
            EventEntity(event.s("id"), start + index + 1, event.toJsonString(), pending = true, serverSeq = null)
        })
        if (dao.meta(HEADER) == null) dao.putMeta(MetaEntity(HEADER, header(createEmptyDocument(isoNow()))))
        onBooked()
    }

    suspend fun clearNotices() = dao.deleteMeta(NOTICES)

    suspend fun exportDocument(): Obj = buildDocument(dao.events(), metaMap())

    private suspend fun metaMap(): Map<String, String> =
        listOf(HEADER, BEAN_LINKS).mapNotNull { key -> dao.meta(key)?.let { key to it.value } }.toMap()

    /** Sync bridge for the core engine. */
    val syncStore: SyncStore = object : SyncStore {
        override suspend fun outbox(): List<JsonObject> = dao.pending().map { parse(it.body) }
        override suspend fun acknowledge(eventIds: List<String>) { if (eventIds.isNotEmpty()) dao.acknowledge(eventIds) }
        override suspend fun reject(rejections: List<Rejection>) = writeLock.withLock {
            dao.delete(rejections.map { it.eventId })
            val old = dao.meta(NOTICES)?.value.orEmpty()
            val added = rejections.joinToString("\n") { "Buchung abgelehnt: ${it.reason}" }
            dao.putMeta(MetaEntity(NOTICES, (old + "\n" + added).trim()))
        }
        override suspend fun applyRemote(events: List<JsonObject>, cursor: Long) = writeLock.withLock {
            val start = dao.maxPosition()
            dao.appendRemote(events.mapIndexed { index, event ->
                EventEntity((event["id"] as JsonPrimitive).content, start + index + 1, event.toString(), pending = false, serverSeq = null)
            }, MetaEntity(CURSOR, cursor.toString()))
        }
        override suspend fun replaceWithSnapshot(document: JsonObject, beanLinks: Map<String, String>, cursor: Long) = writeLock.withLock {
            @Suppress("UNCHECKED_CAST")
            val doc = document.toNative() as Obj
            @Suppress("UNCHECKED_CAST")
            val events = (doc["events"] as? List<Obj>).orEmpty()
            dao.replaceAcknowledged(
                events.mapIndexed { index, event -> EventEntity(event.s("id"), index + 1L, event.toJsonString(), pending = false, serverSeq = null) },
                listOf(MetaEntity(HEADER, header(doc)), MetaEntity(CURSOR, cursor.toString()), MetaEntity(BEAN_LINKS, encodeLinks(beanLinks))),
            )
        }
        override suspend fun cursor(): Long? = dao.meta(CURSOR)?.value?.toLongOrNull()
    }

    /** Forget the server cursor (after a new pairing), so the next sync loads the snapshot. */
    suspend fun resetCursor() = dao.deleteMeta(CURSOR)

    companion object {
        const val HEADER = "document_header"
        const val CURSOR = "cursor"
        const val BEAN_LINKS = "bean_links"
        const val NOTICES = "notices"

        private fun parse(text: String): JsonObject = de.baschek.kaffee.core.api.WireJson.parseToJsonElement(text) as JsonObject

        fun header(document: Obj): String =
            obj("schemaVersion" to 1.0, "createdAt" to document["createdAt"], "settings" to document["settings"]).toJsonString()

        fun buildDocument(events: List<EventEntity>, meta: Map<String, String>): Obj {
            val header = meta[HEADER]?.let { parseObj(it) }
            return obj(
                "schemaVersion" to 1.0,
                "createdAt" to (header?.get("createdAt") ?: events.firstOrNull()?.let { parseObj(it.body)["recordedAt"] } ?: isoNow()),
                "settings" to (header?.get("settings") ?: defaultSettings()),
                "events" to events.map { parseObj(it.body) }.toMutableList<Any?>(),
            )
        }

        private fun encodeLinks(links: Map<String, String>): String = links.toJsonString()
        private fun decodeLinks(text: String): Map<String, String> = parseObj(text).mapValues { it.value.toString() }
    }
}
