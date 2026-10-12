package local.signaldock.query

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class QueryTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val id = "30d66f95-2435-4e56-b52a-70b1c8bba4ef"

    private fun params(vararg pairs: Pair<String, String>) = pairs.associate { it.first to listOf(it.second) }

    private fun day(vararg extra: Pair<String, String>) =
        params("from" to "2026-10-12T00:00:00+09:00", "to" to "2026-10-13T00:00:00+09:00", *extra)

    private fun code(block: () -> Unit) = assertFailsWith<QueryFailure> { block() }.code

    @Test
    fun `list defaults are newest first with 50 rows and no cursor`() {
        val query = parseListQuery(day(), seoul)
        assertEquals(SortOrder.NEWEST_FIRST, query.order)
        assertEquals(50, query.limit)
        assertNull(query.after)
        assertEquals(Instant.parse("2026-10-11T15:00:00Z"), query.period.from)
        assertEquals(Instant.parse("2026-10-12T15:00:00Z"), query.period.to)
    }

    @Test
    fun `page size is bounded from 1 to 100`() {
        assertEquals(100, parseListQuery(day("limit" to "100"), seoul).limit)
        assertEquals(1, parseListQuery(day("limit" to "1"), seoul).limit)
        for (bad in listOf("0", "101", "-1", "abc", "", "1.5", "0050x")) {
            assertEquals("INVALID_PARAMETER", code { parseListQuery(day("limit" to bad), seoul) }, bad)
        }
    }

    @Test
    fun `period is required and ordered`() {
        assertEquals("INVALID_PARAMETER", code { parseListQuery(params("to" to "2026-10-13T00:00:00Z"), seoul) })
        assertEquals("INVALID_PARAMETER", code { parseListQuery(params("from" to "2026-10-13T00:00:00Z"), seoul) })
        val same = params("from" to "2026-10-13T00:00:00Z", "to" to "2026-10-13T00:00:00Z")
        assertEquals("INVALID_PARAMETER", code { parseListQuery(same, seoul) })
    }

    @Test
    fun `period may touch 30 calendar dates in the project timezone and no more`() {
        val ok = params("from" to "2026-09-13T00:00:00+09:00", "to" to "2026-10-13T00:00:00+09:00")
        parseCountQuery(ok, seoul)
        val tooLong = params("from" to "2026-09-12T00:00:00+09:00", "to" to "2026-10-13T00:00:00+09:00")
        assertEquals("PERIOD_TOO_LONG", code { parseCountQuery(tooLong, seoul) })
        // Same 30 days of length, but 31 Seoul dates because the start is not at midnight.
        val shifted = params("from" to "2026-09-13T12:00:00+09:00", "to" to "2026-10-13T12:00:00+09:00")
        assertEquals("PERIOD_TOO_LONG", code { parseCountQuery(shifted, seoul) })
        // Exclusive end: ending exactly at midnight does not touch the next date.
        val exactEnd = params("from" to "2026-10-12T00:00:00+09:00", "to" to "2026-10-13T00:00:00+09:00")
        assertEquals(Bucket.HOUR, parseTimeCountQuery(exactEnd, seoul).bucket)
    }

    @Test
    fun `timestamps need an offset and exact milliseconds`() {
        fun from(value: String) = params("from" to value, "to" to "2026-10-14T00:00:00Z")
        parseCountQuery(from("2026-10-12T00:00:00.123000Z"), seoul)
        for (bad in listOf("2026-10-12T00:00:00", "2026-10-12", "2026-10-12T00:00:00.1234Z", "2026-13-12T00:00:00Z", "x", "")) {
            assertEquals("INVALID_PARAMETER", code { parseCountQuery(from(bad), seoul) }, bad)
        }
    }

    @Test
    fun `unknown and repeated parameters are rejected`() {
        assertEquals("INVALID_PARAMETER", code { parseListQuery(day("foo" to "1"), seoul) })
        assertEquals("INVALID_PARAMETER", code { parseCountQuery(day("limit" to "5"), seoul) })
        assertEquals("INVALID_PARAMETER", code { parseCountQuery(day("cursor" to "x"), seoul) })
        val twice = day() + ("limit" to listOf("1", "2"))
        assertEquals("INVALID_PARAMETER", code { parseListQuery(twice, seoul) })
    }

    @Test
    fun `filters are exact values`() {
        val query = parseCountQuery(day("name" to " padded name", "installationId" to id, "eventId" to id.uppercase()), seoul)
        assertEquals(" padded name", query.filter.name)
        assertEquals(UUID.fromString(id), query.filter.installationId)
        assertEquals(UUID.fromString(id), query.filter.eventId)
        for (bad in listOf("name" to "", "name" to "a\u0000b", "name" to "\ud800", "installationId" to "1-1-1-1-1", "eventId" to "nope")) {
            assertEquals("INVALID_PARAMETER", code { parseCountQuery(day(bad), seoul) }, bad.toString())
        }
        assertEquals("INVALID_PARAMETER", code { parseCountQuery(day("name" to "x".repeat(16 * 1024 + 1)), seoul) })
    }

    @Test
    fun `cursor round trips and is bound to the sort order`() {
        val cursor = ListCursor(Instant.parse("2026-10-12T01:02:03.456Z"), UUID.fromString(id))
        val text = encodeCursor(SortOrder.NEWEST_FIRST, cursor)
        assertEquals(cursor, parseListQuery(day("cursor" to text), seoul).after)
        assertEquals("INVALID_CURSOR", code { parseListQuery(day("cursor" to text, "sort" to "receivedAt_asc"), seoul) })
        assertEquals("INVALID_CURSOR", code { parseListQuery(day("cursor" to "***"), seoul) })
        assertEquals("INVALID_CURSOR", code { parseListQuery(day("cursor" to "AAAA"), seoul) })
        val farFuture = java.util.Base64.getUrlEncoder().encodeToString("N.999999999999999.$id".toByteArray())
        assertEquals("INVALID_CURSOR", code { parseListQuery(day("cursor" to farFuture), seoul) })
    }

    @Test
    fun `bucket defaults to hour for one date and day for longer periods`() {
        assertEquals(Bucket.HOUR, parseTimeCountQuery(day(), seoul).bucket)
        val week = params("from" to "2026-10-06T00:00:00+09:00", "to" to "2026-10-13T00:00:00+09:00")
        assertEquals(Bucket.DAY, parseTimeCountQuery(week, seoul).bucket)
        assertEquals(Bucket.DAY, parseTimeCountQuery(day("bucket" to "day"), seoul).bucket)
        assertEquals("INVALID_PARAMETER", code { parseTimeCountQuery(day("bucket" to "week"), seoul) })
    }

    @Test
    fun `hourly buckets for 30 dates reach the 720 limit and no further`() {
        val thirty = params("from" to "2026-09-13T00:00:00+09:00", "to" to "2026-10-13T00:00:00+09:00", "bucket" to "hour")
        assertEquals(720, bucketStarts(parseTimeCountQuery(thirty, seoul).period, Bucket.HOUR, seoul).size)
        // Starts one hour earlier than a bucket boundary: 721 hourly buckets.
        val over = Period(Instant.parse("2026-09-12T14:30:00Z"), Instant.parse("2026-10-12T15:00:00Z"))
        assertEquals("PERIOD_TOO_LONG", code { bucketStarts(over, Bucket.HOUR, seoul) })
    }

    @Test
    fun `buckets follow project timezone boundaries and empty ones are filled`() {
        val period = Period(Instant.parse("2026-10-11T20:30:00Z"), Instant.parse("2026-10-12T16:00:00Z"))
        val days = bucketStarts(period, Bucket.DAY, seoul)
        assertEquals(listOf(Instant.parse("2026-10-11T15:00:00Z"), Instant.parse("2026-10-12T15:00:00Z")), days)
        val filled = fillBuckets(days, mapOf(days[1] to 3L))
        assertEquals(listOf(0L, 3L), filled.map { it.count })

        val hours = bucketStarts(Period(Instant.parse("2026-10-12T00:10:00Z"), Instant.parse("2026-10-12T02:00:00Z")), Bucket.HOUR, seoul)
        assertEquals(listOf(Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-12T01:00:00Z")), hours)
    }

    @Test
    fun `project timezone must be a region ID`() {
        assertEquals(seoul, parseProjectZone("Asia/Seoul"))
        for (bad in listOf("+09:00", "Z", "GMT+9", "Mars/Base", "Asia/Seoul'; --")) {
            assertFailsWith<Exception>(bad) { parseProjectZone(bad) }
        }
    }
}
