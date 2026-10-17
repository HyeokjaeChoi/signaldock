package local.signaldock.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import local.signaldock.ingestion.PlanStatus
import local.signaldock.ingestion.DiscardReason
import local.signaldock.query.Bucket
import local.signaldock.query.ClientReportCount
import local.signaldock.query.ClientReportCounts
import local.signaldock.query.MAX_CLIENT_REPORT_COUNTS
import local.signaldock.query.CountQuery
import local.signaldock.query.EventFilter
import local.signaldock.query.ListQuery
import local.signaldock.query.MAX_NAME_COUNTS
import local.signaldock.query.NameCount
import local.signaldock.query.NameCounts
import local.signaldock.query.PlanStatusCounts
import local.signaldock.query.Period
import local.signaldock.query.ReceiptPage
import local.signaldock.query.ReceiptSummary
import local.signaldock.query.ReceivedEvent
import local.signaldock.query.SortOrder
import local.signaldock.query.TimeCountQuery
import local.signaldock.server.jooq.tables.ClientReports.CLIENT_REPORTS
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.jooq.Condition
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Dashboard reads. Every call is one read-only transaction with one SQL statement, run under
 * the same statement and lock limits as ingestion. Counts use COUNT / GROUP BY at query time;
 * there are no aggregate tables or counters (spec Q70). Ordering and keyset paging use
 * `events_received_cursor_idx (received_at DESC, event_id DESC)`.
 */
public class QueryStore(private val database: Database) {
    public suspend fun list(q: ListQuery, budget: RequestBudget = database.budget()): ReceiptPage =
        database.transaction(budget) {
            readOnly()
            val ascending = q.order == SortOrder.OLDEST_FIRST
            var where = condition(q.period, q.filter)
            q.after?.let { after ->
                val position = DSL.row(EVENTS.RECEIVED_AT, EVENTS.EVENT_ID)
                val last = DSL.row(after.receivedAt.atOffset(ZoneOffset.UTC), after.eventId)
                where = where.and(if (ascending) position.gt(last) else position.lt(last))
            }
            val rows = query {
                select(EVENTS.EVENT_ID, EVENTS.INSTALLATION_ID, EVENTS.NAME, EVENTS.OCCURRED_AT, EVENTS.RECEIVED_AT, EVENTS.SDK_VERSION, EVENTS.PLAN_STATUS)
                    .from(EVENTS)
                    .where(where)
                    .orderBy(
                        if (ascending) EVENTS.RECEIVED_AT.asc() else EVENTS.RECEIVED_AT.desc(),
                        if (ascending) EVENTS.EVENT_ID.asc() else EVENTS.EVENT_ID.desc(),
                    )
                    .limit(q.limit + 1) // one extra row tells whether another page may exist
                    .fetch { it.toSummary() }
            }
            ReceiptPage(rows.take(q.limit), rows.size > q.limit)
        }

    public suspend fun find(eventId: UUID, budget: RequestBudget = database.budget()): ReceivedEvent? =
        database.transaction(budget) {
            readOnly()
            query { selectFrom(EVENTS).where(EVENTS.EVENT_ID.eq(eventId)).fetchOne() }?.let { row ->
                ReceivedEvent(
                    row.toSummary(),
                    propertiesFrom(Json.parseToJsonElement(row.properties!!.data()).jsonObject),
                )
            }
        }

    public suspend fun countByName(q: CountQuery, budget: RequestBudget = database.budget()): NameCounts =
        database.transaction(budget) {
            readOnly()
            val count = DSL.count()
            val rows = query {
                select(EVENTS.NAME, count)
                    .from(EVENTS)
                    .where(condition(q.period, q.filter))
                    .groupBy(EVENTS.NAME)
                    .orderBy(count.desc(), EVENTS.NAME.asc())
                    .limit(MAX_NAME_COUNTS + 1)
                    .fetch { NameCount(it.value1()!!, it.value2().toLong()) }
            }
            NameCounts(rows.take(MAX_NAME_COUNTS), rows.size > MAX_NAME_COUNTS)
        }

    /** One COUNT with FILTER per status, so the Dashboard reads all violation counts in one statement. */
    public suspend fun countByPlanStatus(q: CountQuery, budget: RequestBudget = database.budget()): PlanStatusCounts =
        database.transaction(budget) {
            readOnly()
            fun count(status: PlanStatus?) =
                DSL.count().filterWhere(if (status == null) EVENTS.PLAN_STATUS.isNull else EVENTS.PLAN_STATUS.eq(status.wire))
            val row = query {
                select(
                    count(PlanStatus.PLANNED), count(PlanStatus.UNPLANNED_EVENT),
                    count(PlanStatus.INVALID_PROPERTIES), count(null),
                )
                    .from(EVENTS)
                    .where(condition(q.period, q.filter))
                    .fetchSingle()
            }
            PlanStatusCounts(row.value1().toLong(), row.value2().toLong(), row.value3().toLong(), row.value4().toLong())
        }

    /** Dropped events by SDK version and reason (ADR-0036): rows are summed at query time, largest first. */
    public suspend fun countClientReports(period: Period, budget: RequestBudget = database.budget()): ClientReportCounts =
        database.transaction(budget) {
            readOnly()
            val total = DSL.sum(CLIENT_REPORTS.COUNT)
            val rows = query {
                select(CLIENT_REPORTS.SDK_VERSION, CLIENT_REPORTS.REASON, total)
                    .from(CLIENT_REPORTS)
                    .where(CLIENT_REPORTS.RECEIVED_AT.ge(period.from.atOffset(ZoneOffset.UTC)))
                    .and(CLIENT_REPORTS.RECEIVED_AT.lt(period.to.atOffset(ZoneOffset.UTC)))
                    .groupBy(CLIENT_REPORTS.SDK_VERSION, CLIENT_REPORTS.REASON)
                    .orderBy(total.desc(), CLIENT_REPORTS.SDK_VERSION.asc(), CLIENT_REPORTS.REASON.asc())
                    .limit(MAX_CLIENT_REPORT_COUNTS + 1)
                    .fetch { ClientReportCount(it.value1()!!, DiscardReason.fromWire(it.value2()!!)!!, it.value3()!!.toLong()) }
            }
            ClientReportCounts(rows.take(MAX_CLIENT_REPORT_COUNTS), rows.size > MAX_CLIENT_REPORT_COUNTS)
        }

    /** Counts per bucket start. Empty buckets are absent; the domain fills them. */
    public suspend fun countByTime(q: TimeCountQuery, budget: RequestBudget = database.budget()): Map<Instant, Long> =
        database.transaction(budget) {
            readOnly()
            // Inline literals keep the SELECT and GROUP BY expressions identical for PostgreSQL.
            val unit = DSL.inline(if (q.bucket == Bucket.HOUR) "hour" else "day")
            val zone = DSL.inline(q.zone.id)
            val start = if (q.bucket == Bucket.HOUR) {
                // date_trunc(hour, ts, zone) merges both occurrences of a repeated local hour at a DST fall-back.
                // Subtracting the local remainder from the instant keeps them apart.
                DSL.field(
                    "{0} - (({0} AT TIME ZONE {1}) - date_trunc('hour', {0} AT TIME ZONE {1}))",
                    SQLDataType.TIMESTAMPWITHTIMEZONE, EVENTS.RECEIVED_AT, zone,
                )
            } else {
                DSL.field("date_trunc({0}, {1}, {2})", SQLDataType.TIMESTAMPWITHTIMEZONE, unit, EVENTS.RECEIVED_AT, zone)
            }
            val count = DSL.count()
            query {
                select(start, count)
                    .from(EVENTS)
                    .where(condition(q.period, q.filter))
                    .groupBy(start)
                    .fetch()
                    .associate { it.value1()!!.toInstant() to it.value2().toLong() }
            }
        }
}

private fun Transaction.readOnly() {
    connection.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
}

private fun condition(period: Period, filter: EventFilter): Condition {
    var where = EVENTS.RECEIVED_AT.ge(period.from.atOffset(ZoneOffset.UTC))
        .and(EVENTS.RECEIVED_AT.lt(period.to.atOffset(ZoneOffset.UTC)))
    filter.name?.let { where = where.and(EVENTS.NAME.eq(it)) }
    filter.installationId?.let { where = where.and(EVENTS.INSTALLATION_ID.eq(it)) }
    filter.eventId?.let { where = where.and(EVENTS.EVENT_ID.eq(it)) }
    filter.planStatus?.let { where = where.and(EVENTS.PLAN_STATUS.eq(it.wire)) }
    return where
}

private fun Record.toSummary() = ReceiptSummary(
    get(EVENTS.EVENT_ID)!!, get(EVENTS.INSTALLATION_ID)!!, get(EVENTS.NAME)!!,
    get(EVENTS.OCCURRED_AT)!!.toInstant(), get(EVENTS.RECEIVED_AT)!!.toInstant(), get(EVENTS.SDK_VERSION)!!,
    get(EVENTS.PLAN_STATUS)?.let(PlanStatus::fromWire), // the CHECK constraint allows only known values
)
