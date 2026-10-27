package local.signaldock.persistence

import kotlinx.coroutines.runBlocking
import local.signaldock.ingestion.PropertyValue
import local.signaldock.ingestion.SdkPlatform
import local.signaldock.query.PlanStatusCounts
import local.signaldock.query.Bucket
import local.signaldock.query.CountQuery
import local.signaldock.query.EventFilter
import local.signaldock.query.ListCursor
import local.signaldock.query.ListQuery
import local.signaldock.query.MAX_NAME_COUNTS
import local.signaldock.query.Period
import local.signaldock.query.SortOrder
import local.signaldock.query.TimeCountQuery
import local.signaldock.query.bucketStarts
import local.signaldock.query.fillBuckets
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.jooq.JSONB
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryStoreTest {
    private lateinit var database: Database
    private lateinit var store: QueryStore
    private val seoul = ZoneId.of("Asia/Seoul")
    private val noFilter = EventFilter(null, null, null)
    private val installation = UUID.fromString("47c4d89f-352a-41da-9b31-529ea756515a")

    @BeforeTest
    fun startDatabase() {
        if (!postgres.isRunning) postgres.start()
        database = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        database.migrate()
        runBlocking { database.transaction { query { deleteFrom(EVENTS).execute() } } }
        store = QueryStore(database)
    }

    @AfterTest
    fun stopDatabase() {
        database.close()
    }

    private fun id(n: Int) = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

    private fun insert(
        n: Int,
        receivedAt: String,
        name: String = "product_viewed",
        installationId: UUID = installation,
        properties: String = "{}",
        sdkPlatform: String = "android",
    ) = runBlocking {
        database.transaction {
            query {
                insertInto(EVENTS)
                    .set(EVENTS.EVENT_ID, id(n))
                    .set(EVENTS.INSTALLATION_ID, installationId)
                    .set(EVENTS.NAME, name)
                    .set(EVENTS.OCCURRED_AT, Instant.parse("2026-10-01T00:00:00Z").atOffset(ZoneOffset.UTC))
                    .set(EVENTS.SDK_VERSION, "0.1.0")
                    .set(EVENTS.SDK_PLATFORM, sdkPlatform)
                    .set(EVENTS.PROPERTIES, JSONB.valueOf(properties))
                    .set(EVENTS.RECEIVED_AT, Instant.parse(receivedAt).atOffset(ZoneOffset.UTC))
                    .execute()
            }
        }
        Unit
    }

    private fun period(from: String, to: String) = Period(Instant.parse(from), Instant.parse(to))

    private val october = period("2026-10-01T00:00:00Z", "2026-10-31T00:00:00Z")

    private fun listQuery(order: SortOrder, limit: Int, after: ListCursor? = null, filter: EventFilter = noFilter, period: Period = october) =
        ListQuery(period, filter, order, limit, after)

    private fun walk(order: SortOrder, limit: Int): List<UUID> = runBlocking {
        val seen = mutableListOf<UUID>()
        var after: ListCursor? = null
        while (true) {
            val page = store.list(listQuery(order, limit, after))
            seen += page.items.map { it.eventId }
            if (!page.hasMore) break
            after = page.items.last().let { ListCursor(it.receivedAt, it.eventId) }
        }
        seen
    }

    @Test
    fun `keyset paging is stable when many rows share one receivedAt`() {
        // Five rows share a timestamp, one is older, one newer: ties must break by eventId in both directions.
        (1..5).forEach { insert(it, "2026-10-12T00:00:00.500Z") }
        insert(6, "2026-10-12T00:00:00.499Z")
        insert(7, "2026-10-12T00:00:00.501Z")

        assertEquals(listOf(7, 5, 4, 3, 2, 1, 6).map(::id), walk(SortOrder.NEWEST_FIRST, 2))
        assertEquals(listOf(6, 1, 2, 3, 4, 5, 7).map(::id), walk(SortOrder.OLDEST_FIRST, 2))
        assertEquals(walk(SortOrder.NEWEST_FIRST, 2), walk(SortOrder.NEWEST_FIRST, 1))
    }

    @Test
    fun `a full last page reports no more rows and an empty period returns an empty page`() = runBlocking {
        insert(1, "2026-10-12T00:00:00Z")
        insert(2, "2026-10-12T00:00:01Z")
        val exact = store.list(listQuery(SortOrder.NEWEST_FIRST, 2))
        assertEquals(2, exact.items.size)
        assertFalse(exact.hasMore)
        assertTrue(store.list(listQuery(SortOrder.NEWEST_FIRST, 1)).hasMore)
        val empty = store.list(listQuery(SortOrder.NEWEST_FIRST, 5, period = period("2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z")))
        assertEquals(0, empty.items.size)
        assertFalse(empty.hasMore)
    }

    @Test
    fun `period is start inclusive and end exclusive on receivedAt`() = runBlocking {
        insert(1, "2026-10-11T23:59:59.999Z")
        insert(2, "2026-10-12T00:00:00Z")
        insert(3, "2026-10-12T23:59:59.999Z")
        insert(4, "2026-10-13T00:00:00Z")
        val day = period("2026-10-12T00:00:00Z", "2026-10-13T00:00:00Z")

        assertEquals(listOf(3, 2).map(::id), store.list(listQuery(SortOrder.NEWEST_FIRST, 10, period = day)).items.map { it.eventId })
        assertEquals(mapOf("product_viewed" to 2L), store.countByName(CountQuery(day, noFilter)).items.associate { it.name to it.count })
    }

    @Test
    fun `exact filters apply to list and counts`() = runBlocking {
        val other = UUID.fromString("11111111-1111-4111-8111-111111111111")
        insert(1, "2026-10-12T00:00:00Z", name = "cart_add")
        insert(2, "2026-10-12T00:00:01Z", name = "cart_add", installationId = other)
        insert(3, "2026-10-12T00:00:02Z", name = "cart_added")
        insert(4, "2026-10-12T00:00:03Z", name = "Cart_add")

        fun ids(filter: EventFilter) = runBlocking { store.list(listQuery(SortOrder.OLDEST_FIRST, 10, filter = filter)).items.map { it.eventId } }
        assertEquals(listOf(1, 2).map(::id), ids(EventFilter("cart_add", null, null)))
        assertEquals(listOf(2).map(::id), ids(EventFilter("cart_add", other, null)))
        assertEquals(listOf(3).map(::id), ids(EventFilter(null, null, id(3))))
        assertEquals(emptyList(), ids(EventFilter("cart_add", null, id(3))))
        assertEquals(2L, store.countByName(CountQuery(october, EventFilter("cart_add", null, null))).items.single().count)
    }

    @Test
    fun `the platform is stored per event and the filter applies to list and all event counts`() = runBlocking {
        insert(1, "2026-10-12T00:00:00Z", name = "cart_add") // android by default in the helper
        insert(2, "2026-10-12T00:00:01Z", name = "cart_add", sdkPlatform = "ios")
        insert(3, "2026-10-12T00:00:02Z", name = "cart_view", sdkPlatform = "ios")
        fun filter(p: SdkPlatform?) = EventFilter(null, null, null, sdkPlatform = p)
        fun ids(p: SdkPlatform?) = runBlocking { store.list(listQuery(SortOrder.OLDEST_FIRST, 10, filter = filter(p))).items.map { it.eventId } }
        assertEquals(listOf(1, 2, 3).map(::id), ids(null))
        assertEquals(listOf(1).map(::id), ids(SdkPlatform.ANDROID))
        assertEquals(listOf(2, 3).map(::id), ids(SdkPlatform.IOS))
        assertEquals(
            listOf(SdkPlatform.ANDROID, SdkPlatform.IOS, SdkPlatform.IOS),
            store.list(listQuery(SortOrder.OLDEST_FIRST, 10)).items.map { it.sdkPlatform },
        )
        assertEquals(SdkPlatform.IOS, assertNotNull(store.find(id(2))).summary.sdkPlatform)
        // The filter combines with the other exact filters.
        assertEquals(listOf(2).map(::id), runBlocking { store.list(listQuery(SortOrder.OLDEST_FIRST, 10, filter = EventFilter("cart_add", null, null, sdkPlatform = SdkPlatform.IOS))).items.map { it.eventId } })

        assertEquals(mapOf("cart_add" to 1L, "cart_view" to 1L), store.countByName(CountQuery(october, filter(SdkPlatform.IOS))).items.associate { it.name to it.count })
        assertEquals(mapOf("cart_add" to 1L), store.countByName(CountQuery(october, filter(SdkPlatform.ANDROID))).items.associate { it.name to it.count })
        assertEquals(PlanStatusCounts(0, 0, 0, 2), store.countByPlanStatus(CountQuery(october, filter(SdkPlatform.IOS))))
        assertEquals(PlanStatusCounts(0, 0, 0, 3), store.countByPlanStatus(CountQuery(october, filter(null))))
        val day = TimeCountQuery(period("2026-10-12T00:00:00Z", "2026-10-13T00:00:00Z"), filter(SdkPlatform.IOS), Bucket.DAY, ZoneId.of("UTC"))
        assertEquals(mapOf(Instant.parse("2026-10-12T00:00:00Z") to 2L), store.countByTime(day))
        assertEquals(mapOf(Instant.parse("2026-10-12T00:00:00Z") to 1L), store.countByTime(day.copy(filter = filter(SdkPlatform.ANDROID))))
    }

    @Test
    fun `the platform check constraint rejects values other than android and ios`() {
        for (bad in listOf("iOS", "Android", "unknown", "")) {
            kotlin.test.assertFailsWith<Exception>(bad) { insert(1, "2026-10-12T00:00:00Z", sdkPlatform = bad) }
        }
        assertEquals(0, runBlocking { store.list(listQuery(SortOrder.NEWEST_FIRST, 10)).items.size })
    }

    @Test
    fun `counts by name are ordered by count then name and report truncation`() = runBlocking {
        insert(1, "2026-10-12T00:00:00Z", name = "b")
        insert(2, "2026-10-12T00:00:01Z", name = "b")
        insert(3, "2026-10-12T00:00:02Z", name = "a")
        insert(4, "2026-10-12T00:00:03Z", name = "c")
        insert(5, "2026-10-12T00:00:04Z", name = "c")
        insert(6, "2026-10-12T00:00:05Z", name = "c")
        val counts = store.countByName(CountQuery(october, noFilter))
        assertEquals(listOf("c" to 3L, "b" to 2L, "a" to 1L), counts.items.map { it.name to it.count })
        assertFalse(counts.truncated)

        database.transaction { query { deleteFrom(EVENTS).execute() } }
        (1..MAX_NAME_COUNTS + 1).forEach { insert(it, "2026-10-12T00:00:00Z", name = "n%03d".format(it)) }
        val capped = store.countByName(CountQuery(october, noFilter))
        assertEquals(MAX_NAME_COUNTS, capped.items.size)
        assertTrue(capped.truncated)
    }

    @Test
    fun `counts by time use project timezone buckets and empty buckets read zero`() = runBlocking {
        // Seoul is UTC+9: 14:59:59Z is still Oct 12 in Seoul, 15:00:00Z starts Oct 13.
        insert(1, "2026-10-12T14:59:59.999Z")
        insert(2, "2026-10-12T15:00:00Z")
        insert(3, "2026-10-12T15:30:00Z")
        insert(4, "2026-10-14T00:00:00Z")
        val range = period("2026-10-11T15:00:00Z", "2026-10-15T15:00:00Z")

        val dayQuery = TimeCountQuery(range, noFilter, Bucket.DAY, seoul)
        val days = fillBuckets(bucketStarts(range, Bucket.DAY, seoul), store.countByTime(dayQuery))
        assertEquals(
            listOf("2026-10-11T15:00:00Z" to 1L, "2026-10-12T15:00:00Z" to 2L, "2026-10-13T15:00:00Z" to 1L, "2026-10-14T15:00:00Z" to 0L),
            days.map { it.start.toString() to it.count },
        )
        // Rows 2 and 3 share the Seoul hour 00:00-00:59 of Oct 13; the next hour is empty.
        val hourQuery = TimeCountQuery(period("2026-10-12T15:00:00Z", "2026-10-12T17:00:00Z"), noFilter, Bucket.HOUR, seoul)
        val hours = fillBuckets(bucketStarts(hourQuery.period, Bucket.HOUR, seoul), store.countByTime(hourQuery))
        assertEquals(listOf(2L, 0L), hours.map { it.count })
        // Filters apply to buckets too.
        val filtered = store.countByTime(dayQuery.copy(filter = EventFilter(null, null, id(1))))
        assertEquals(mapOf(Instant.parse("2026-10-11T15:00:00Z") to 1L), filtered)
    }

    @Test
    fun `hour buckets keep both repeated local hours at a DST fall-back`() = runBlocking {
        // New York falls back on 2026-11-01: 01:00-02:00 local occurs twice (05:00Z EDT, then 06:00Z EST).
        val ny = ZoneId.of("America/New_York")
        insert(1, "2026-11-01T04:59:59Z")
        insert(2, "2026-11-01T05:10:00Z")
        insert(3, "2026-11-01T05:50:00.123Z")
        insert(4, "2026-11-01T06:05:00Z")
        insert(5, "2026-11-01T06:06:00Z")
        insert(6, "2026-11-01T06:07:00Z")
        insert(7, "2026-11-01T07:00:00Z")
        val query = TimeCountQuery(period("2026-11-01T04:00:00Z", "2026-11-01T08:00:00Z"), noFilter, Bucket.HOUR, ny)
        val hours = fillBuckets(bucketStarts(query.period, Bucket.HOUR, ny), store.countByTime(query))
        assertEquals(
            listOf(
                "2026-11-01T04:00:00Z" to 1L,
                "2026-11-01T05:00:00Z" to 2L,
                "2026-11-01T06:00:00Z" to 3L,
                "2026-11-01T07:00:00Z" to 1L,
            ),
            hours.map { it.start.toString() to it.count },
        )
    }

    @Test
    fun `details return stored properties and a missing event is null`() = runBlocking {
        insert(1, "2026-10-12T00:00:00.123Z", properties = """{"price":1.50,"sku":"a\"b","ok":true}""")
        val event = assertNotNull(store.find(id(1)))
        assertEquals(Instant.parse("2026-10-12T00:00:00.123Z"), event.summary.receivedAt)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), event.summary.occurredAt)
        assertEquals(PropertyValue.Number("1.50"), event.properties["price"])
        assertEquals(PropertyValue.Text("a\"b"), event.properties["sku"])
        assertEquals(PropertyValue.Flag(true), event.properties["ok"])
        assertNull(store.find(id(2)))
    }

    @Test
    fun `list query plan uses the receivedAt cursor index without a new migration`() = runBlocking {
        database.transaction {
            query {
                execute(
                    """INSERT INTO events (event_id, installation_id, name, occurred_at, sdk_version, sdk_platform, properties, received_at)
                       SELECT gen_random_uuid(), gen_random_uuid(), 'e' || (g % 3), now(), '0.1.0', CASE WHEN g % 2 = 0 THEN 'ios' ELSE 'android' END, '{}'::jsonb,
                              timestamptz '2026-09-01 00:00:00+00' + g * interval '2 minutes'
                       FROM generate_series(1, 20000) g""",
                )
            }
        }
        // ANALYZE cannot run inside a transaction block, so use a plain connection.
        java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { it.execute("ANALYZE events") }
            fun plan(sql: String): String = c.createStatement().use { s ->
                s.executeQuery("EXPLAIN $sql").use { r -> generateSequence { if (r.next()) r.getString(1) else null }.joinToString("\n") }
            }
            val base = "SELECT event_id, name FROM events WHERE received_at >= '2026-09-10 00:00:00+00' AND received_at < '2026-09-11 00:00:00+00'"
            val newest = plan("$base ORDER BY received_at DESC, event_id DESC LIMIT 51")
            val keyset = plan("$base AND (received_at, event_id) < ('2026-09-10 12:00:00+00', '00000000-0000-4000-8000-000000000000') ORDER BY received_at DESC, event_id DESC LIMIT 51")
            val counts = plan("SELECT name, count(*) FROM events WHERE received_at >= '2026-09-10 00:00:00+00' AND received_at < '2026-09-11 00:00:00+00' GROUP BY name")
            // The platform filter adds a row filter on the same index scan: no Seq Scan, no new index (ADR-0046).
            val platform = "AND sdk_platform = 'ios'"
            val newestIos = plan("$base $platform ORDER BY received_at DESC, event_id DESC LIMIT 51")
            val keysetIos = plan("$base $platform AND (received_at, event_id) < ('2026-09-10 12:00:00+00', '00000000-0000-4000-8000-000000000000') ORDER BY received_at DESC, event_id DESC LIMIT 51")
            val countsIos = plan("SELECT name, count(*) FROM events WHERE received_at >= '2026-09-10 00:00:00+00' AND received_at < '2026-09-11 00:00:00+00' $platform GROUP BY name")
            for (p in listOf(newest, keyset, counts, newestIos, keysetIos, countsIos)) {
                assertTrue(p.contains("events_received_cursor_idx"), p)
                assertFalse(p.contains("Seq Scan"), p)
            }
        }
    }

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
