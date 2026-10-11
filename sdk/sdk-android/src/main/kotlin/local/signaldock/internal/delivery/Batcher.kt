package local.signaldock.internal.delivery

import local.signaldock.internal.storage.EventEntity
import local.signaldock.internal.transport.BATCH_SUFFIX
import local.signaldock.internal.transport.batchPrefix
import local.signaldock.internal.transport.batchRequestJson

internal const val MAX_BATCH_EVENTS = 50
internal const val MAX_BATCH_BYTES = 256 * 1024

internal class Batch(val events: List<EventEntity>, val contractVersion: String, val body: ByteArray)

/**
 * Takes events from the front of [rows] while the request stays within [maxEvents] and [maxBytes]
 * (uncompressed UTF-8 JSON including the wrapper, spec section 4). [rows] must be one batch group:
 * same contract version and installation id, oldest first. At least one event is always taken;
 * a stored event is at most 16 KiB, so it always fits.
 */
internal fun buildBatch(rows: List<EventEntity>, maxEvents: Int = MAX_BATCH_EVENTS, maxBytes: Int = MAX_BATCH_BYTES): Batch {
    require(rows.isNotEmpty())
    val first = rows.first()
    var size = batchPrefix(first.installationId).toByteArray(Charsets.UTF_8).size + BATCH_SUFFIX.length
    val taken = ArrayList<EventEntity>()
    for (row in rows) {
        if (taken.size >= maxEvents) break
        val add = row.payloadJson.toByteArray(Charsets.UTF_8).size + if (taken.isEmpty()) 0 else 1 // comma
        if (taken.isNotEmpty() && size + add > maxBytes) break
        size += add
        taken += row
    }
    val body = batchRequestJson(first.installationId, taken.map { it.payloadJson }).toByteArray(Charsets.UTF_8)
    check(body.size == size) { "batch size arithmetic mismatch" }
    return Batch(taken, first.contractVersion, body)
}
