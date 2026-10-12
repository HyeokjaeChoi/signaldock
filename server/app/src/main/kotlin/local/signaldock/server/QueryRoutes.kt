package local.signaldock.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.signaldock.ingestion.PropertyValue
import local.signaldock.ingestion.RequestFailure
import local.signaldock.persistence.Database
import local.signaldock.persistence.DatabaseUnavailable
import local.signaldock.persistence.QueryStore
import local.signaldock.persistence.RequestBudget
import local.signaldock.query.QueryFailure
import local.signaldock.query.ReceiptSummary
import local.signaldock.query.ListCursor
import local.signaldock.query.bucketStarts
import local.signaldock.query.encodeCursor
import local.signaldock.query.fillBuckets
import local.signaldock.query.parseCountQuery
import local.signaldock.query.parseDetailRequest
import local.signaldock.query.parseListQuery
import local.signaldock.query.parseTimeCountQuery
import org.openapitools.client.models.ErrorResponse
import org.openapitools.client.models.EventNameCount
import org.openapitools.client.models.EventNameCounts
import org.openapitools.client.models.EventReceiptDetail
import org.openapitools.client.models.EventReceiptPage
import org.openapitools.client.models.EventReceiptSummary
import org.openapitools.client.models.EventTimeBucket
import org.openapitools.client.models.EventTimeCounts
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.openapitools.client.models.PropertyValue as ApiPropertyValue

private val json = Json

/**
 * Dashboard query routes (ADR-0002 Q107, ADR-0007). Parsing and limits live in `:query`, SQL in
 * `:persistence`; this layer owns HTTP status mapping and the generated transport DTOs.
 */
internal fun Application.queryModule(database: Database, queryKey: String, zone: ZoneId) {
    require(queryKey.isNotBlank())
    val store = QueryStore(database)
    routing {
        get("/v1/events") {
            call.answer(database, queryKey) { budget ->
                val query = parseListQuery(call.queryParameterMap(), zone)
                val page = store.list(query, budget)
                val next = page.items.lastOrNull()?.takeIf { page.hasMore }
                    ?.let { encodeCursor(query.order, ListCursor(it.receivedAt, it.eventId)) }
                json.encodeToString(EventReceiptPage(page.items.map { it.toDto() }, next))
            }
        }
        get("/v1/events/{eventId}") {
            call.answer(database, queryKey) { budget ->
                val id = parseDetailRequest(call.parameters["eventId"].orEmpty(), call.queryParameterMap())
                val event = store.find(id, budget) ?: throw EventNotFound()
                val summary = event.summary
                json.encodeToString(EventReceiptDetail(
                    summary.eventId.toString(), summary.installationId.toString(), summary.name,
                    summary.occurredAt.text(), summary.receivedAt.text(), summary.sdkVersion,
                    event.properties.mapValues { (_, value) -> value.toDto() },
                ))
            }
        }
        get("/v1/event-counts/by-name") {
            call.answer(database, queryKey) { budget ->
                val counts = store.countByName(parseCountQuery(call.queryParameterMap(), zone), budget)
                json.encodeToString(EventNameCounts(counts.items.map { EventNameCount(it.name, it.count) }, counts.truncated))
            }
        }
        get("/v1/event-counts/by-time") {
            call.answer(database, queryKey) { budget ->
                val query = parseTimeCountQuery(call.queryParameterMap(), zone)
                val buckets = fillBuckets(bucketStarts(query.period, query.bucket, zone), store.countByTime(query, budget))
                json.encodeToString(EventTimeCounts(
                    zone.id,
                    EventTimeCounts.Bucket.valueOf(query.bucket.name),
                    buckets.map { EventTimeBucket(it.start.text(), it.count) },
                ))
            }
        }
    }
}

private class EventNotFound : RuntimeException("EVENT_NOT_FOUND")

/** Same order as ingestion: contract version, then key, then parameters. One request budget covers all of it. */
private suspend fun ApplicationCall.answer(
    database: Database,
    queryKey: String,
    work: suspend (RequestBudget) -> String,
) {
    val budget = database.budget()
    try {
        val body = withTimeout(budget.remainingMs()) {
            requireContractVersion()
            requireQueryKey(queryKey)
            work(budget)
        }
        respondText(body, ContentType.Application.Json)
    } catch (error: RequestFailure) {
        fail(HttpStatusCode.fromValue(httpStatusOf(error.code)), error.code, error.code)
    } catch (error: QueryFailure) {
        fail(HttpStatusCode.BadRequest, error.code, error.message ?: error.code)
    } catch (_: EventNotFound) {
        fail(HttpStatusCode.NotFound, "EVENT_NOT_FOUND", "No stored event has this ID")
    } catch (_: DatabaseUnavailable) {
        unavailable("TEMPORARILY_UNAVAILABLE", "Retry the same request later")
    } catch (_: TimeoutCancellationException) {
        unavailable("REQUEST_TIMEOUT", "Retry the same request later")
    }
}

private suspend fun ApplicationCall.fail(status: HttpStatusCode, code: String, message: String) =
    respondText(json.encodeToString(ErrorResponse(code, message)), ContentType.Application.Json, status)

private suspend fun ApplicationCall.unavailable(code: String, message: String) {
    response.headers.append(HttpHeaders.RetryAfter, "1")
    fail(HttpStatusCode.ServiceUnavailable, code, message)
}

private fun ApplicationCall.queryParameterMap(): Map<String, List<String>> =
    request.queryParameters.entries().associate { it.key to it.value }

private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** UTC RFC 3339 with exactly three fractional digits (Q81, Q83). */
private fun Instant.text(): String = TIMESTAMP.format(this)

private fun ReceiptSummary.toDto() = EventReceiptSummary(
    eventId.toString(), installationId.toString(), name, occurredAt.text(), receivedAt.text(), sdkVersion,
)

private fun PropertyValue.toDto(): ApiPropertyValue = when (this) {
    is PropertyValue.Text -> ApiPropertyValue.StringValue(value)
    is PropertyValue.Flag -> ApiPropertyValue.BooleanValue(value)
    is PropertyValue.Number -> ApiPropertyValue.BigDecimalValue(BigDecimal(literal))
    PropertyValue.Unsupported -> error("Stored properties are flat scalars; ingestion rejects other values")
}
