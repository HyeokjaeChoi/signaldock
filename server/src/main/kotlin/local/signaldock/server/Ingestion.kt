package local.signaldock.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.jooq.JSONB
import org.openapitools.client.models.BatchAckResponse
import org.openapitools.client.models.ItemAck
import java.time.ZoneOffset

internal class Ingestion(private val database: Database) {
    suspend fun ingest(batch: ValidatedBatch, budget: RequestBudget = database.budget()): BatchAckResponse =
        database.transaction(budget) {
            val results = arrayOfNulls<ItemAck>(batch.items.size)
            batch.items.filter { it.event == null }.forEach { item ->
                results[item.index] = ItemAck(item.index, ItemAck.Status.REJECTED, item.eventId,
                    item.rejectionCode ?: "INVALID_EVENT")
            }
            // A stable order also prevents opposite-order batches from deadlocking on unique keys.
            batch.items.filter { it.event != null }.groupBy { it.event!!.eventId }
                .toSortedMap().forEach { (_, group) ->
                    val event = group.first().event!!
                    if (group.any { !sameContent(event, it.event!!) }) {
                        group.forEach { item ->
                            results[item.index] = ItemAck(item.index, ItemAck.Status.REJECTED, item.eventId, "CONFLICT")
                        }
                    } else {
                        val inserted = query {
                            insertInto(EVENTS)
                                .set(EVENTS.EVENT_ID, event.eventId)
                                .set(EVENTS.INSTALLATION_ID, event.installationId)
                                .set(EVENTS.NAME, event.name)
                                .set(EVENTS.OCCURRED_AT, event.occurredAt.atOffset(ZoneOffset.UTC))
                                .set(EVENTS.SDK_VERSION, event.sdkVersion)
                                .set(EVENTS.PROPERTIES, JSONB.valueOf(event.properties.toString()))
                                .onConflict(EVENTS.EVENT_ID).doNothing().execute() == 1
                        }
                        // READ COMMITTED gives this statement a new snapshot after a competing insert commits.
                        val equal = inserted || query {
                            val row = selectFrom(EVENTS).where(EVENTS.EVENT_ID.eq(event.eventId)).fetchOne()
                                ?: throw DatabaseUnavailable("CONCURRENT_ROW_REMOVAL")
                            sameContent(event, StoredEvent(
                                row.eventId!!, row.installationId!!, row.name!!, row.occurredAt!!.toInstant(),
                                row.sdkVersion!!, Json.parseToJsonElement(row.properties!!.data()).jsonObject,
                            ))
                        }
                        group.forEachIndexed { offset, item ->
                            results[item.index] = when {
                                !equal -> ItemAck(item.index, ItemAck.Status.REJECTED, item.eventId, "CONFLICT")
                                inserted && offset == 0 -> ItemAck(item.index, ItemAck.Status.ACCEPTED, item.eventId)
                                else -> ItemAck(item.index, ItemAck.Status.DUPLICATE, item.eventId)
                            }
                        }
                    }
                }
            // This value cannot reach the caller until Database.transaction has committed and cleaned up.
            BatchAckResponse(results.map { requireNotNull(it) })
        }
}
