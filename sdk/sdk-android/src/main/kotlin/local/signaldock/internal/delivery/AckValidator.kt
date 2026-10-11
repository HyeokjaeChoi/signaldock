package local.signaldock.internal.delivery

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.openapitools.client.models.BatchAckResponse
import org.openapitools.client.models.ItemAck

/** Compatible extra response fields are ignored (Q68). Everything else is strict. */
private val ackJson = Json { ignoreUnknownKeys = true }

/**
 * Validates the whole ACK against the immutable request-time id list (architecture "Validate the
 * entire ACK before changing the queue"). Returns the results ordered by request index, or null
 * if the response is unreadable, incomplete or contradictory. The caller then applies nothing.
 *
 * Rules: valid JSON of the expected shape; known statuses only (the generated enum rejects others);
 * exactly one result per request index, none outside the request; `accepted` and `duplicate` echo
 * the request's eventId; a `rejected` item may omit eventId but must not contradict it.
 */
internal fun validateAck(requestEventIds: List<String>, body: String?): List<ItemAck>? {
    if (body == null) return null
    val response = try {
        if (!hasStrictTypes(ackJson.parseToJsonElement(body))) return null
        ackJson.decodeFromString(BatchAckResponse.serializer(), body)
    } catch (e: SerializationException) {
        return null
    } catch (e: IllegalArgumentException) {
        return null
    }
    val results = response.results
    if (results.size != requestEventIds.size) return null
    val byIndex = arrayOfNulls<ItemAck>(requestEventIds.size)
    for (item in results) {
        if (item.index !in requestEventIds.indices || byIndex[item.index] != null) return null
        val expected = requestEventIds[item.index]
        when (item.status) {
            ItemAck.Status.ACCEPTED, ItemAck.Status.DUPLICATE -> if (item.eventId != expected) return null
            ItemAck.Status.REJECTED -> if (item.eventId != null && item.eventId != expected) return null
        }
        byIndex[item.index] = item
    }
    return byIndex.map { it ?: return null }
}

/**
 * kotlinx.serialization accepts a quoted number for an Int even in strict mode. A type error is an
 * invalid ACK (Q68), so check the JSON types of the known fields first: `index` is a bare number,
 * the other known fields are strings or null.
 */
private fun hasStrictTypes(root: kotlinx.serialization.json.JsonElement): Boolean {
    val results = (root as? JsonObject)?.get("results") as? JsonArray ?: return false
    return results.all { item ->
        item is JsonObject &&
            (item["index"] as? JsonPrimitive)?.let { !it.isString } == true &&
            listOf("status", "eventId", "code", "message").all { key ->
                val v = item[key]
                v == null || v is JsonNull || (v is JsonPrimitive && v.isString)
            }
    }
}
