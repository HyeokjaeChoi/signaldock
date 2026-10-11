package local.signaldock.persistence

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import local.signaldock.ingestion.IngestPlan
import local.signaldock.ingestion.ItemReceipt
import local.signaldock.ingestion.PropertyValue
import local.signaldock.ingestion.StoredEvent
import local.signaldock.ingestion.sameContent
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.jooq.JSONB
import java.time.ZoneOffset

public class EventStore(private val database: Database) {
    /** Stores each group of the plan in one transaction and returns receipts in request order. */
    public suspend fun store(plan: IngestPlan, budget: RequestBudget = database.budget()): List<ItemReceipt> =
        database.transaction(budget) {
            val stored = mutableListOf<ItemReceipt>()
            plan.groups.forEach { group ->
                val event = group.event
                val inserted = query {
                    insertInto(EVENTS)
                        .set(EVENTS.EVENT_ID, event.eventId)
                        .set(EVENTS.INSTALLATION_ID, event.installationId)
                        .set(EVENTS.NAME, event.name)
                        .set(EVENTS.OCCURRED_AT, event.occurredAt.atOffset(ZoneOffset.UTC))
                        .set(EVENTS.SDK_VERSION, event.sdkVersion)
                        .set(EVENTS.PROPERTIES, JSONB.valueOf(propertiesJson(event.properties)))
                        .onConflict(EVENTS.EVENT_ID).doNothing().execute() == 1
                }
                // READ COMMITTED gives this statement a new snapshot after a competing insert commits.
                val equal = inserted || query {
                    val row = selectFrom(EVENTS).where(EVENTS.EVENT_ID.eq(event.eventId)).fetchOne()
                        ?: throw DatabaseUnavailable("CONCURRENT_ROW_REMOVAL")
                    sameContent(event, StoredEvent(
                        row.eventId!!, row.installationId!!, row.name!!, row.occurredAt!!.toInstant(),
                        row.sdkVersion!!, propertiesFrom(Json.parseToJsonElement(row.properties!!.data()).jsonObject),
                    ))
                }
                stored += group.receipts(inserted, equal)
            }
            // This value cannot reach the caller until Database.transaction has committed and cleaned up.
            plan.complete(stored)
        }
}

/** Numbers are written with their source literal so storage never rounds them. */
@OptIn(ExperimentalSerializationApi::class)
internal fun propertiesJson(properties: Map<String, PropertyValue>): String =
    JsonObject(properties.mapValues { (_, value) -> value.toJson() }).toString()

private fun PropertyValue.toJson(): JsonElement = when (this) {
    is PropertyValue.Text -> JsonPrimitive(value)
    is PropertyValue.Flag -> JsonPrimitive(value)
    is PropertyValue.Number -> JsonUnquotedLiteral(literal)
    PropertyValue.Unsupported -> error("Unsupported property values are rejected before storage")
}

internal fun propertiesFrom(json: JsonObject): Map<String, PropertyValue> = json.mapValues { (_, value) ->
    val primitive = value as? JsonPrimitive
    when {
        primitive == null || primitive is kotlinx.serialization.json.JsonNull -> PropertyValue.Unsupported
        primitive.isString -> PropertyValue.Text(primitive.content)
        primitive.booleanOrNull != null -> PropertyValue.Flag(primitive.booleanOrNull!!)
        else -> PropertyValue.Number(primitive.content)
    }
}
