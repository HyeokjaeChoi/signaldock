package local.signaldock.internal.delivery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import local.signaldock.InvalidReason
import local.signaldock.TrackResult
import local.signaldock.internal.init.Logger
import local.signaldock.internal.storage.DroppedCountEntity
import local.signaldock.internal.storage.MAX_DROPPED_COUNT
import local.signaldock.internal.storage.QueueDao
import local.signaldock.internal.transport.SDK_VERSION
import local.signaldock.internal.transport.transportJson
import org.openapitools.client.models.ClientReportRequest
import org.openapitools.client.models.DiscardReason
import org.openapitools.client.models.DiscardedEventCount
import org.openapitools.client.models.SdkPlatform

/**
 * SDK health report (Q164, ADR-0036): the SDK counts the events it dropped, by reason, and sends the
 * counts to `POST /v1/client-reports`. The counters are in Room, so a restart does not lose them.
 */

/**
 * The reason that `track()` dropped the event, or null when it did not drop it (a stored event, or
 * a result that is not a drop). Opt-out and queue clearing by the host are not drops (Q156), so any
 * result that is not one of the three below is not counted.
 */
internal fun TrackResult.dropReason(): DiscardReason? = when (this) {
    is TrackResult.Invalid -> if (reason == InvalidReason.EVENT_TOO_LARGE) DiscardReason.EVENT_TOO_LARGE else DiscardReason.INVALID_EVENT
    TrackResult.QueueFull -> DiscardReason.QUEUE_FULL
    is TrackResult.StorageError -> DiscardReason.STORAGE_ERROR
    else -> null
}

/** Counts one dropped event. Best effort: a failure here never reaches the host and never changes the track result. */
internal class DropCounter(private val dao: QueueDao, private val logger: Logger) {
    suspend fun record(reason: DiscardReason) {
        try {
            dao.addDropped(reason.value, 1)
        } catch (e: CancellationException) {
            // A closed Room database cancels its own scope: the caller is still active (ADR-0017).
            if (!currentCoroutineContext().isActive) throw e
            logger.warn("drop counter failed: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            // A storage failure can break the counter too (a full disk). Such a drop is not counted.
            logger.warn("drop counter failed: ${e.javaClass.simpleName}")
        }
    }
}

/** A report body and the counters it carries. Counters of an unknown reason are not sent and stay. */
internal class ClientReport(val body: ByteArray, val rows: List<DroppedCountEntity>)

internal fun buildClientReport(counters: List<DroppedCountEntity>): ClientReport? {
    val sent = ArrayList<DroppedCountEntity>()
    val entries = ArrayList<DiscardedEventCount>()
    for (row in counters) {
        val reason = DiscardReason.entries.firstOrNull { it.value == row.reason } ?: continue
        if (row.count <= 0) continue
        val count = row.count.coerceAtMost(MAX_DROPPED_COUNT)
        entries += DiscardedEventCount(reason, count)
        sent += DroppedCountEntity(row.reason, count)
    }
    if (entries.isEmpty()) return null
    val json = transportJson.encodeToString(ClientReportRequest.serializer(), ClientReportRequest(sdkVersion = SDK_VERSION, discardedEvents = entries, sdkPlatform = SdkPlatform.ANDROID))
    return ClientReport(json.toByteArray(Charsets.UTF_8), sent)
}

internal enum class ReportOutcome {
    /** The server stored the report: subtract the counts. */
    SENT,

    /** The server cannot take this report (404 from an old server, another permanent 4xx): subtract the counts, no retry. */
    DISCARDED,

    /** A transient failure: keep the counters for the next send cycle. */
    KEPT,
}

internal fun classifyReport(result: TransportResult): ReportOutcome = when (result) {
    is TransportResult.Failed -> ReportOutcome.KEPT
    is TransportResult.Http -> when (result.status) {
        in 200..299 -> ReportOutcome.SENT
        408, 429 -> ReportOutcome.KEPT
        501, 505 -> ReportOutcome.DISCARDED
        in 500..599 -> ReportOutcome.KEPT
        else -> ReportOutcome.DISCARDED // 404, 400, 401, 403, 413, 415, redirects, 1xx
    }
}
