package local.signaldock.internal

import kotlinx.serialization.json.Json
import org.openapitools.client.models.BatchIngestRequest
import org.openapitools.client.models.EventIngest

internal val transportJson = Json

/**
 * Canonical size of one event as sent (spec section 4): the uncompressed UTF-8 bytes of a
 * [BatchIngestRequest] that holds this single event, including the wrapper, installationId,
 * common fields and JSON escaping. Used for the 16 KiB event limit and the 16 MiB queue total.
 * The sender (issue 04) must reuse this function, not re-measure.
 */
internal fun singleEventRequestBytes(installationId: String, event: EventIngest): Int =
    transportJson.encodeToString(BatchIngestRequest.serializer(), BatchIngestRequest(installationId, listOf(event)))
        .toByteArray(Charsets.UTF_8).size
