package de.baschek.kaffee.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One row per journal event (the app's InventoryEvent as JSON, never changed after writing). `position` is the
 * local order (server events in seq order, own events in creation order); `pending` = in the outbox, not yet
 * acknowledged by the Pi.
 */
@Entity(tableName = "events", indices = [Index("pending")])
data class EventEntity(
    @PrimaryKey val id: String,
    val position: Long,
    val body: String,
    val pending: Boolean,
    val serverSeq: Long?,
)

/** Small key/value rows: document header, sync cursor, bean links, notices. */
@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)

@Dao
abstract class KaffeeDao {
    @Query("SELECT * FROM events ORDER BY position")
    abstract fun observeEvents(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events ORDER BY position")
    abstract suspend fun events(): List<EventEntity>

    @Query("SELECT * FROM events WHERE pending = 1 ORDER BY position")
    abstract suspend fun pending(): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events WHERE pending = 1")
    abstract fun observePendingCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(position), 0) FROM events")
    abstract suspend fun maxPosition(): Long

    @Query("SELECT id FROM events")
    abstract suspend fun ids(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(events: List<EventEntity>)

    @Query("UPDATE events SET pending = 0 WHERE id IN (:ids)")
    abstract suspend fun acknowledge(ids: List<String>)

    @Query("DELETE FROM events WHERE id IN (:ids)")
    abstract suspend fun delete(ids: List<String>)

    @Query("DELETE FROM events WHERE pending = 0")
    abstract suspend fun deleteAcknowledged()

    @Query("SELECT * FROM meta WHERE `key` = :key")
    abstract suspend fun meta(key: String): MetaEntity?

    @Query("SELECT * FROM meta")
    abstract fun observeMeta(): Flow<List<MetaEntity>>

    @Upsert
    abstract suspend fun putMeta(meta: MetaEntity)

    @Query("DELETE FROM meta WHERE `key` = :key")
    abstract suspend fun deleteMeta(key: String)

    @Transaction
    open suspend fun appendRemote(events: List<EventEntity>, cursor: MetaEntity) {
        insert(events)
        putMeta(cursor)
    }

    @Transaction
    open suspend fun replaceAcknowledged(events: List<EventEntity>, metas: List<MetaEntity>) {
        val pending = pending()
        deleteAcknowledged()
        delete(pending.map { it.id })
        insert(events)
        val start = maxPosition()
        insert(pending.mapIndexed { index, event -> event.copy(position = start + index + 1) })
        metas.forEach { putMeta(it) }
    }
}

@Database(entities = [EventEntity::class, MetaEntity::class], version = 1, exportSchema = true)
abstract class KaffeeDatabase : RoomDatabase() {
    abstract fun dao(): KaffeeDao
}
