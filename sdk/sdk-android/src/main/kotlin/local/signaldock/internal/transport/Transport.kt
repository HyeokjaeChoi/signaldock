package local.signaldock.internal.transport

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.openapitools.client.models.BatchIngestRequest
import org.openapitools.client.models.EventIngest

internal val transportJson = Json

/**
 * Canonical size of one event as sent (spec section 4): the uncompressed UTF-8 bytes of a
 * [BatchIngestRequest] that holds this single event, including the wrapper, installationId,
 * common fields and JSON escaping. Used for the 16 KiB event limit and the 16 MiB queue total.
 * The sender must reuse this function, not re-measure.
 */
internal fun singleEventRequestBytes(installationId: String, event: EventIngest): Int =
    batchRequestBytes(installationId, listOf(event))

/** Canonical size of a whole batch request: the UTF-8 bytes of the generated [BatchIngestRequest]. */
internal fun batchRequestBytes(installationId: String, events: List<EventIngest>): Int =
    transportJson.encodeToString(BatchIngestRequest.serializer(), BatchIngestRequest(installationId, events))
        .toByteArray(Charsets.UTF_8).size

internal const val BATCH_SUFFIX = "]}"

internal fun batchPrefix(installationId: String): String =
    """{"installationId":${transportJson.encodeToString(String.serializer(), installationId)},"events":["""

/**
 * Request body for a batch, assembled from the stored per-event transport JSON so that a resend
 * carries the exact bytes that were stored. It equals what [transportJson] produces for a
 * [BatchIngestRequest] with the same events (a test checks this against [batchRequestBytes]).
 */
internal fun batchRequestJson(installationId: String, payloads: List<String>): String =
    batchPrefix(installationId) + payloads.joinToString(",") + BATCH_SUFFIX
