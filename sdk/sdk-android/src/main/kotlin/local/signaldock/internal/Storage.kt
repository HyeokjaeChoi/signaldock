package local.signaldock.internal

import androidx.room.ColumnInfo
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

/** Queue item. [payloadJson] is the transport JSON of one event (contract [contractVersion]). */
@Entity(tableName = "events", indices = [Index(value = ["eventId"], unique = true)])
internal data class EventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val eventId: String,
    val installationId: String,
    val contractVersion: String,
    val payloadJson: String,
    val sizeBytes: Int,
    val createdAtMillis: Long,
)

/** Permanent rejection, stored without event payload or name. Last [MAX_REJECTIONS] are kept. */
@Entity(tableName = "rejections")
internal data class RejectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val code: String,
    val rejectedAtMillis: Long,
)

/** Single row (id = 1). Written by the sender (issue 04); read by diagnostics. */
@Entity(tableName = "retry_state")
internal data class RetryStateEntity(
    @PrimaryKey val id: Int = 1,
    val failureCount: Int = 0,
    val nextAttemptAtMillis: Long? = null,
    val lastAckAtMillis: Long? = null,
    val lastAckAccepted: Int = 0,
    val lastAckDuplicate: Int = 0,
    val lastAckRejected: Int = 0,
)

internal data class QueueUsage(
    @ColumnInfo(name = "count") val count: Int,
    @ColumnInfo(name = "bytes") val bytes: Long,
    @ColumnInfo(name = "oldest") val oldestCreatedAtMillis: Long?,
)

internal const val MAX_REJECTIONS = 100

internal data class QueueLimits(
    val maxEvents: Int = 10_000,
    val maxQueueBytes: Long = 16L * 1024 * 1024,
    val maxEventBytes: Int = 16 * 1024,
)

internal enum class AddOutcome { ADDED, QUEUE_FULL }

@Dao
internal abstract class QueueDao {
    @Insert
    abstract suspend fun insert(event: EventEntity): Long

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS bytes, MIN(createdAtMillis) AS oldest FROM events")
    abstract suspend fun usage(): QueueUsage

    /** Limit check and insert run in one transaction (spec section 4: atomic). */
    @Transaction
    open suspend fun addWithinLimits(event: EventEntity, limits: QueueLimits): AddOutcome {
        val u = usage()
        if (u.count + 1 > limits.maxEvents || u.bytes + event.sizeBytes > limits.maxQueueBytes) {
            return AddOutcome.QUEUE_FULL
        }
        insert(event)
        return AddOutcome.ADDED
    }

    @Query("SELECT * FROM events ORDER BY seq LIMIT :limit")
    abstract suspend fun oldest(limit: Int): List<EventEntity>

    @Query("DELETE FROM events WHERE eventId IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>): Int

    @Insert
    abstract suspend fun insertRejections(r: List<RejectionEntity>)

    @Query("DELETE FROM rejections WHERE id NOT IN (SELECT id FROM rejections ORDER BY id DESC LIMIT $MAX_REJECTIONS)")
    abstract suspend fun trimRejections()

    /** Stores diagnostics and removes the queue entries in one transaction (ACK application). */
    @Transaction
    open suspend fun rejectPermanently(rejections: List<RejectionEntity>) {
        insertRejections(rejections)
        trimRejections()
        deleteByIds(rejections.map { it.eventId })
    }

    @Query("SELECT * FROM rejections ORDER BY id DESC")
    abstract suspend fun recentRejections(): List<RejectionEntity>

    @Query("SELECT * FROM retry_state WHERE id = 1")
    abstract suspend fun retryState(): RetryStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveRetryState(state: RetryStateEntity)
}

@Database(
    entities = [EventEntity::class, RejectionEntity::class, RetryStateEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class SignalDockDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao
}
