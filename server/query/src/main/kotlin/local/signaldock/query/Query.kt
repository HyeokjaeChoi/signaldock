package local.signaldock.query

import local.signaldock.ingestion.PlanStatus
import local.signaldock.ingestion.PropertyValue
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

public const val DEFAULT_PAGE_SIZE: Int = 50
public const val MAX_PAGE_SIZE: Int = 100
public const val MAX_PERIOD_DATES: Int = 30
public const val MAX_BUCKETS: Int = 720
public const val MAX_NAME_COUNTS: Int = 100
private const val MAX_NAME_BYTES = 16 * 1024

/** A rejected query. `code` is machine readable; the caller maps it to HTTP 400. */
public class QueryFailure(public val code: String, message: String) : RuntimeException(message)

/** Receipt time period [from, to). */
public data class Period(val from: Instant, val to: Instant)

/** Exact-value filters. A null value means no filter. */
public data class EventFilter(
    val name: String?,
    val installationId: UUID?,
    val eventId: UUID?,
    val planStatus: PlanStatus? = null,
)

public enum class SortOrder(public val parameter: String) {
    NEWEST_FIRST("receivedAt_desc"),
    OLDEST_FIRST("receivedAt_asc"),
}

/** Position after which the next page starts, in the order of the query. */
public data class ListCursor(val receivedAt: Instant, val eventId: UUID)

public data class ListQuery(
    val period: Period,
    val filter: EventFilter,
    val order: SortOrder,
    val limit: Int,
    val after: ListCursor?,
)

public data class CountQuery(val period: Period, val filter: EventFilter)

public enum class Bucket(public val parameter: String) { HOUR("hour"), DAY("day") }

public data class TimeCountQuery(val period: Period, val filter: EventFilter, val bucket: Bucket, val zone: ZoneId)

/** A list row. Properties stay out of lists; only details read them. */
public data class ReceiptSummary(
    val eventId: UUID,
    val installationId: UUID,
    val name: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val sdkVersion: String,
    /** Null when no plan was configured at ingest, and for events stored before the plan check (ADR-0022). */
    val planStatus: PlanStatus? = null,
)

public data class ReceivedEvent(val summary: ReceiptSummary, val properties: Map<String, PropertyValue>)

/** One page in query order. `hasMore` means a next page may exist (the cursor is not a snapshot). */
public data class ReceiptPage(val items: List<ReceiptSummary>, val hasMore: Boolean)

public data class NameCount(val name: String, val count: Long)

/** At most [MAX_NAME_COUNTS] names; `truncated` means more names matched. */
public data class NameCounts(val items: List<NameCount>, val truncated: Boolean)

public data class TimeBucketCount(val start: Instant, val count: Long)

/** Events per plan status in a period. `notChecked` counts rows without a status. */
public data class PlanStatusCounts(val planned: Long, val unplannedEvent: Long, val invalidProperties: Long, val notChecked: Long)

private val ID_FILTER_PARAMETERS = setOf("name", "installationId", "eventId")
private val FILTER_PARAMETERS = ID_FILTER_PARAMETERS + "planStatus"
private val PERIOD_PARAMETERS = setOf("from", "to")

public fun parseListQuery(params: Map<String, List<String>>, zone: ZoneId): ListQuery {
    rejectUnknown(params, PERIOD_PARAMETERS + FILTER_PARAMETERS + setOf("sort", "limit", "cursor"))
    val order = single(params, "sort")?.let { raw ->
        SortOrder.entries.firstOrNull { it.parameter == raw } ?: invalid("sort", "must be receivedAt_desc or receivedAt_asc")
    } ?: SortOrder.NEWEST_FIRST
    val limit = single(params, "limit")?.let { raw ->
        if (!Regex("[0-9]{1,3}").matches(raw) || raw.toInt() !in 1..MAX_PAGE_SIZE) {
            invalid("limit", "must be an integer from 1 to $MAX_PAGE_SIZE")
        }
        raw.toInt()
    } ?: DEFAULT_PAGE_SIZE
    val after = single(params, "cursor")?.let { decodeCursor(it, order) }
    return ListQuery(parsePeriod(params, zone), parseFilter(params), order, limit, after)
}

public fun parseCountQuery(params: Map<String, List<String>>, zone: ZoneId): CountQuery {
    rejectUnknown(params, PERIOD_PARAMETERS + FILTER_PARAMETERS)
    return CountQuery(parsePeriod(params, zone), parseFilter(params))
}

/** The plan-status counts take the exact filters but not `planStatus`: they already split by it. */
public fun parsePlanStatusCountQuery(params: Map<String, List<String>>, zone: ZoneId): CountQuery {
    rejectUnknown(params, PERIOD_PARAMETERS + ID_FILTER_PARAMETERS)
    return CountQuery(parsePeriod(params, zone), parseFilter(params))
}

public fun parseTimeCountQuery(params: Map<String, List<String>>, zone: ZoneId): TimeCountQuery {
    rejectUnknown(params, PERIOD_PARAMETERS + FILTER_PARAMETERS + "bucket")
    val period = parsePeriod(params, zone)
    val bucket = single(params, "bucket")?.let { raw ->
        Bucket.entries.firstOrNull { it.parameter == raw } ?: invalid("bucket", "must be hour or day")
    } ?: if (calendarDates(period, zone) == 1L) Bucket.HOUR else Bucket.DAY
    bucketStarts(period, bucket, zone) // checks the bucket limit
    return TimeCountQuery(period, parseFilter(params), bucket, zone)
}

/** Project timezone (Q78, Q79). Only region IDs such as Asia/Seoul: PostgreSQL reads offset IDs differently. */
public fun parseProjectZone(id: String): ZoneId {
    require(Regex("[A-Za-z]+(/[A-Za-z0-9_+-]+)*").matches(id)) { "Project timezone must be a region ID such as Asia/Seoul" }
    return ZoneId.of(id).also { require(it !is ZoneOffset) { "Project timezone must be a region ID such as Asia/Seoul" } }
}

public fun parseEventId(raw: String): UUID = parseUuid("eventId", raw)

/** The details route takes no query parameters: any one, even repeated, is rejected like on the other routes. */
public fun parseDetailRequest(rawEventId: String, params: Map<String, List<String>>): UUID {
    rejectUnknown(params, emptySet())
    return parseEventId(rawEventId)
}

/** Starts of all buckets that touch the period, in order. The first start can precede `from`. */
public fun bucketStarts(period: Period, bucket: Bucket, zone: ZoneId): List<Instant> {
    val starts = ArrayList<Instant>()
    var current: ZonedDateTime = period.from.atZone(zone).let {
        if (bucket == Bucket.HOUR) it.truncatedTo(ChronoUnit.HOURS) else it.toLocalDate().atStartOfDay(zone)
    }
    while (current.toInstant() < period.to) {
        if (starts.size == MAX_BUCKETS) throw QueryFailure("PERIOD_TOO_LONG", "more than $MAX_BUCKETS buckets")
        starts += current.toInstant()
        current = if (bucket == Bucket.HOUR) current.plusHours(1) else current.toLocalDate().plusDays(1).atStartOfDay(zone)
    }
    return starts
}

/** Gives every bucket of the period a count; buckets without rows get 0. */
public fun fillBuckets(starts: List<Instant>, counts: Map<Instant, Long>): List<TimeBucketCount> =
    starts.map { TimeBucketCount(it, counts[it] ?: 0L) }

public fun encodeCursor(order: SortOrder, cursor: ListCursor): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        "${order.name.first()}.${cursor.receivedAt.toEpochMilli()}.${cursor.eventId}".toByteArray(Charsets.UTF_8),
    )

private fun decodeCursor(raw: String, order: SortOrder): ListCursor {
    val bad = { QueryFailure("INVALID_CURSOR", "cursor is not valid for this request") }
    val text = try {
        String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        throw bad()
    }
    val parts = text.split('.')
    if (parts.size != 3 || parts[0] != order.name.first().toString() || !Regex("-?[0-9]{1,15}").matches(parts[1])) throw bad()
    val id = try { parseUuid("cursor", parts[2]) } catch (_: QueryFailure) { throw bad() }
    val at = Instant.ofEpochMilli(parts[1].toLong())
    if (at.atOffset(ZoneOffset.UTC).year !in 1..9999) throw bad()
    return ListCursor(at, id)
}

private fun parsePeriod(params: Map<String, List<String>>, zone: ZoneId): Period {
    val from = timestamp("from", single(params, "from") ?: invalid("from", "is required"))
    val to = timestamp("to", single(params, "to") ?: invalid("to", "is required"))
    if (!from.isBefore(to)) invalid("to", "must be after from")
    val period = Period(from, to)
    if (calendarDates(period, zone) > MAX_PERIOD_DATES) {
        throw QueryFailure("PERIOD_TOO_LONG", "a period may touch at most $MAX_PERIOD_DATES calendar dates in $zone")
    }
    return period
}

/** Calendar dates in the project timezone that [from, to) touches. */
private fun calendarDates(period: Period, zone: ZoneId): Long =
    ChronoUnit.DAYS.between(
        period.from.atZone(zone).toLocalDate(),
        period.to.minusMillis(1).atZone(zone).toLocalDate(),
    ) + 1

private fun parseFilter(params: Map<String, List<String>>): EventFilter = EventFilter(
    name = single(params, "name")?.also(::checkName),
    installationId = single(params, "installationId")?.let { parseUuid("installationId", it) },
    eventId = single(params, "eventId")?.let { parseUuid("eventId", it) },
    planStatus = single(params, "planStatus")?.let { raw ->
        PlanStatus.fromWire(raw) ?: invalid("planStatus", "must be planned, unplanned_event, or invalid_properties")
    },
)

/** Exact filter: no trimming or normalization (Q64, Q84), but a value storage cannot hold never matches. */
private fun checkName(name: String) {
    if (name.isEmpty()) invalid("name", "must not be empty")
    if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) invalid("name", "is too long")
    var index = 0
    while (index < name.length) {
        val c = name[index]
        val pairOk = c.isHighSurrogate() && index + 1 < name.length && name[index + 1].isLowSurrogate()
        if (c == '\u0000' || (c.isSurrogate() && !pairOk)) invalid("name", "contains NUL or a lone surrogate")
        index += if (pairOk) 2 else 1
    }
}

private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val TIMESTAMP_TEXT = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})")

private fun parseUuid(parameter: String, raw: String): UUID =
    if (UUID_TEXT.matches(raw)) UUID.fromString(raw) else invalid(parameter, "must be a UUID")

/** RFC 3339 with an offset; exact milliseconds only, like event times (Q82, Q83). */
private fun timestamp(parameter: String, raw: String): Instant {
    if (!TIMESTAMP_TEXT.matches(raw)) invalid(parameter, "must be an RFC 3339 timestamp with an offset")
    val instant = try {
        OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (_: java.time.DateTimeException) {
        invalid(parameter, "must be an RFC 3339 timestamp with an offset")
    }
    if (instant.nano % 1_000_000 != 0) invalid(parameter, "must be exact to the millisecond")
    if (instant.atOffset(ZoneOffset.UTC).year !in 1..9999) invalid(parameter, "is out of range")
    return instant
}

private fun single(params: Map<String, List<String>>, name: String): String? {
    val values = params[name] ?: return null
    if (values.size != 1) invalid(name, "must be given once")
    return values.single()
}

private fun rejectUnknown(params: Map<String, List<String>>, allowed: Set<String>) {
    params.keys.firstOrNull { it !in allowed }?.let { invalid(it, "is not a supported parameter") }
}

private fun invalid(parameter: String, problem: String): Nothing =
    throw QueryFailure("INVALID_PARAMETER", "$parameter $problem")
