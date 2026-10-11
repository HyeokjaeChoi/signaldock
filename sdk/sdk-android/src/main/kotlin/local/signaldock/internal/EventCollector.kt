package local.signaldock.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import local.signaldock.EventBuilder
import local.signaldock.InvalidReason
import local.signaldock.TrackResult
import local.signaldock.internal.init.Clock
import local.signaldock.internal.init.IdGenerator
import local.signaldock.internal.init.Logger
import local.signaldock.internal.storage.AddOutcome
import local.signaldock.internal.storage.EventEntity
import local.signaldock.internal.storage.EventSink
import local.signaldock.internal.storage.QueueLimits
import local.signaldock.internal.transport.CONTRACT_VERSION
import local.signaldock.internal.transport.SDK_VERSION
import local.signaldock.internal.transport.singleEventRequestBytes
import local.signaldock.internal.transport.transportJson
import local.signaldock.internal.validation.checkText
import org.openapitools.client.models.EventIngest
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.database.sqlite.SQLiteException

internal class EventCollector(
    private val installationId: String,
    private val sink: EventSink,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val limits: QueueLimits,
    private val logger: Logger,
) {
    suspend fun track(name: String, block: EventBuilder.() -> Unit = {}): TrackResult {
        val now = clock.nowMillis()
        val builder = EventBuilder().apply(block)
        checkText(name, isName = true)?.let { return TrackResult.Invalid(it, "name") }
        builder.error?.let { return it }

        val eventId = ids.newId()
        val event = EventIngest(
            eventId = eventId,
            name = name,
            occurredAt = formatUtcMillis(now),
            properties = builder.properties,
            sdkVersion = SDK_VERSION,
            contractVersion = EventIngest.ContractVersion._1,
        )
        val payload = transportJson.encodeToString(EventIngest.serializer(), event)
        val size = singleEventRequestBytes(installationId, event)
        if (size > limits.maxEventBytes) return TrackResult.Invalid(InvalidReason.EVENT_TOO_LARGE, null)

        // Cancelled before storage starts: nothing stored, no warning (spec section 3).
        currentCoroutineContext().ensureActive()
        val entity = EventEntity(
            eventId = eventId, installationId = installationId, contractVersion = CONTRACT_VERSION,
            payloadJson = payload, sizeBytes = size, createdAtMillis = now,
        )
        return try {
            when (sink.add(entity, limits)) {
                AddOutcome.ADDED -> TrackResult.Success(eventId)
                AddOutcome.QUEUE_FULL -> TrackResult.QueueFull
            }
        } catch (e: CancellationException) {
            // The commit may already have happened. Do not call track again for the same action.
            logger.warn("track cancelled during storage; the event may already be stored")
            throw e
        } catch (e: IOException) {
            storageError(e)
        } catch (e: SQLiteException) {
            storageError(e)
        } catch (e: IllegalStateException) {
            storageError(e)
        }
    }

    private fun storageError(e: Exception): TrackResult {
        logger.warn("track storage failed: ${e.javaClass.simpleName}")
        return TrackResult.StorageError(e.javaClass.simpleName)
    }

    private companion object {
        /** UTC RFC 3339 with exactly 3 fraction digits (Q81). java.time needs API 26; minSdk is 24. */
        fun formatUtcMillis(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(ms))
    }
}
