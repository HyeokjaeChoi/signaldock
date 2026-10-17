package local.signaldock.internal.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import org.openapitools.client.models.DiscardReason

/**
 * Queue item. [payloadJson] is the transport JSON of one event (contract [contractVersion]).
 * [sizeBytes] is the canonical size of that event as sent: see [local.signaldock.internal.transport.singleEventRequestBytes].
 */
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

/**
 * Events the SDK dropped, counted per reason ([org.openapitools.client.models.DiscardReason] wire name).
 * Reported to the server and then subtracted (ADR-0036). [count] stops at [MAX_DROPPED_COUNT].
 */
@Entity(tableName = "dropped_counts")
internal data class DroppedCountEntity(
    @PrimaryKey val reason: String,
    val count: Int,
)

/** Single row (id = 1). Written by the sender (issue 04); read by diagnostics. */
@Entity(tableName = "retry_state")
internal data class RetryStateEntity(
    @PrimaryKey val id: Int = 1,
    val failureCount: Int = 0,
    val nextAttemptAtMillis: Long? = null,
    /** The wait that was meant when [nextAttemptAtMillis] was stored. A clock moved back cannot extend it (ADR-0025). */
    val waitMillis: Long? = null,
    val lastAckAtMillis: Long? = null,
    val lastAckAccepted: Int = 0,
    val lastAckDuplicate: Int = 0,
    val lastAckRejected: Int = 0,
    /** [local.signaldock.DeliveryErrorKind] name; content-free. */
    val lastErrorKind: String? = null,
    val lastErrorHttpStatus: Int? = null,
    val lastErrorAtMillis: Long? = null,
    /** Normal sending is stopped (auth, configuration or contract error). One probe batch per recheck. */
    val hold: Boolean = false,
    /** Batch event cap after a 413 split; null means the default. See ADR-0006. */
    val maxBatchEvents: Int? = null,
)

internal data class QueueUsage(
    @ColumnInfo(name = "count") val count: Int,
    @ColumnInfo(name = "bytes") val bytes: Long,
    @ColumnInfo(name = "oldest") val oldestCreatedAtMillis: Long?,
)

internal data class DiagnosticsSnapshot(
    val usage: QueueUsage,
    val retry: RetryStateEntity?,
    val rejections: List<RejectionEntity>,
)

internal const val MAX_REJECTIONS = 100

/** A counter stops here, like the contract limit of one report entry (ADR-0036). */
internal const val MAX_DROPPED_COUNT = 1_000_000

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

    /** Oldest events of one batch group. A request has one contract header and one installationId. */
    @Query("SELECT * FROM events WHERE contractVersion = :contractVersion AND installationId = :installationId ORDER BY seq LIMIT :limit")
    abstract suspend fun oldestOfGroup(contractVersion: String, installationId: String, limit: Int): List<EventEntity>

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

    /**
     * ACK application (architecture "Collection, delivery, and ACK"): queue removal, permanent
     * rejection diagnostics and the retry state reset commit together or not at all.
     * [removeIds] holds every event of the batch that the valid ACK settles, rejected ones included.
     */
    @Transaction
    open suspend fun applyAck(removeIds: List<String>, rejections: List<RejectionEntity>, state: RetryStateEntity) {
        if (rejections.isNotEmpty()) {
            insertRejections(rejections)
            trimRejections()
            addDropped(DiscardReason.SERVER_REJECTED.value, rejections.size) // same commit as the removal (ADR-0036)
        }
        deleteByIds(removeIds)
        saveRetryState(state)
    }

    @Query("SELECT * FROM dropped_counts WHERE count > 0 ORDER BY reason")
    abstract suspend fun droppedCounts(): List<DroppedCountEntity>

    @Query("SELECT count FROM dropped_counts WHERE reason = :reason")
    abstract suspend fun droppedCount(reason: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putDropped(row: DroppedCountEntity)

    @Query("DELETE FROM dropped_counts WHERE reason = :reason")
    abstract suspend fun deleteDropped(reason: String)

    /** Adds [n] to a counter. It stops at [MAX_DROPPED_COUNT] and never overflows. */
    @Transaction
    open suspend fun addDropped(reason: String, n: Int) {
        val now = droppedCount(reason) ?: 0
        putDropped(DroppedCountEntity(reason, if (n >= MAX_DROPPED_COUNT - now) MAX_DROPPED_COUNT else now + n))
    }

    /**
     * Removes counts that a report carried. Drops counted during the HTTP call stay. A counter that
     * was saturated while the report was in flight loses only what was sent.
     */
    @Transaction
    open suspend fun subtractDropped(sent: List<DroppedCountEntity>) {
        for (row in sent) {
            val left = (droppedCount(row.reason) ?: 0) - row.count
            if (left > 0) putDropped(DroppedCountEntity(row.reason, left)) else deleteDropped(row.reason)
        }
    }

    @Query("SELECT * FROM rejections ORDER BY id DESC")
    abstract suspend fun recentRejections(): List<RejectionEntity>

    @Query("SELECT * FROM retry_state WHERE id = 1")
    abstract suspend fun retryState(): RetryStateEntity?

    /** Usage, retry state and rejections in one transaction: one consistent snapshot. */
    @Transaction
    open suspend fun diagnosticsSnapshot(): DiagnosticsSnapshot =
        DiagnosticsSnapshot(usage(), retryState(), recentRejections())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveRetryState(state: RetryStateEntity)
}
