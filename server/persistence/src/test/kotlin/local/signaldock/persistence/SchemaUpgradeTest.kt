package local.signaldock.persistence

import kotlinx.coroutines.runBlocking
import local.signaldock.ingestion.ClientReport
import local.signaldock.ingestion.DiscardReason
import local.signaldock.ingestion.DiscardedCount
import local.signaldock.ingestion.PlanStatus
import local.signaldock.ingestion.SdkPlatform
import local.signaldock.query.ClientReportCountQuery
import local.signaldock.query.ClientReportCount
import local.signaldock.server.jooq.tables.ClientReports.CLIENT_REPORTS
import local.signaldock.ingestion.PropertyValue
import local.signaldock.ingestion.ReceiptStatus
import local.signaldock.ingestion.StoredEvent
import local.signaldock.ingestion.ValidatedBatch
import local.signaldock.ingestion.ValidatedItem
import local.signaldock.ingestion.planBatch
import local.signaldock.query.CountQuery
import local.signaldock.query.EventFilter
import local.signaldock.query.ListQuery
import local.signaldock.query.Period
import local.signaldock.query.PlanStatusCounts
import local.signaldock.query.SortOrder
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Spec section 7 "DB changes" (ADR-0012): the server queries run on (a) an empty database
 * migrated to head and (b) a database upgraded one migration at a time, with rows written at
 * every earlier schema version. With one migration, (b) loads the V1 rows at V1 and re-checks
 * them at head; with V2 the same loop loads V1 rows at V1 and migrates them to V2.
 */
class SchemaUpgradeTest {
    private val opened = mutableListOf<Database>()
    private val a = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val b = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val c = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val d = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private val installation = UUID.fromString("47c4d89f-352a-41da-9b31-529ea756515a")
    private val october = Period(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z"))
    private val noFilter = EventFilter(null, null, null)

    @AfterTest
    fun close() = opened.forEach { it.close() }

    @Test
    fun `empty database migrates to head and serves ingestion and queries`() = runBlocking {
        val database = freshDatabase()
        database.migrate()
        assertEquals(0, QueryStore(database).countByName(CountQuery(october, noFilter)).items.size)
        assertEquals(0, QueryStore(database).countClientReports(ClientReportCountQuery(october, null)).items.size)
        assertEquals(ReceiptStatus.ACCEPTED, store(database, a, "n").status)
        assertEquals(1, QueryStore(database).list(listQuery()).items.size)
    }

    @Test
    fun `database upgraded one migration at a time keeps its rows and serves queries at head`() = runBlocking {
        val database = freshDatabase()
        fun flyway(target: String? = null) = Flyway.configure().dataSource(database.dataSource)
            .apply { if (target != null) target(target) }.load()

        val versions = flyway().info().pending().map { it.version.version }
        assertTrue(versions.isNotEmpty(), "no migrations found")
        for (version in versions) {
            flyway(version).migrate()
            val seed = javaClass.getResource("/db/previous-data/V$version.sql")
                ?: fail("Add server/persistence/src/test/resources/db/previous-data/V$version.sql: rows written at schema V$version")
            database.dataSource.connection.use { c -> c.createStatement().use { it.execute(seed.readText()) } }
        }
        flyway().migrate()
        flyway().validate()
        assertEquals(0, flyway().info().pending().size)

        val query = QueryStore(database)
        assertEquals(listOf(d, c, b, a), query.list(listQuery()).items.map { it.eventId })
        assertEquals(listOf(a, b, c, d), query.list(listQuery(SortOrder.OLDEST_FIRST)).items.map { it.eventId })
        val detail = assertNotNull(query.find(a))
        assertEquals(Instant.parse("2026-10-01T00:00:00.500Z"), detail.summary.receivedAt)
        assertEquals(PropertyValue.Number("19.50"), detail.properties["price"])
        assertEquals(PropertyValue.Text("가나다"), detail.properties["note"])
        assertEquals(PropertyValue.Flag(true), detail.properties["in_stock"])
        assertEquals(
            setOf("product_viewed" to 3L, "purchase_completed" to 1L),
            query.countByName(CountQuery(october, noFilter)).items.map { it.name to it.count }.toSet(),
        )

        // Plan status (V2): rows written at V1 are "not checked" (null); the row written at V2 keeps its status.
        assertNull(detail.summary.planStatus)
        assertEquals(PlanStatus.PLANNED, assertNotNull(query.find(c)).summary.planStatus)
        assertEquals(PlanStatusCounts(planned = 1, unplannedEvent = 0, invalidProperties = 0, notChecked = 3), query.countByPlanStatus(CountQuery(october, noFilter)))
        assertEquals(listOf(c), query.list(ListQuery(october, EventFilter(null, null, null, PlanStatus.PLANNED), SortOrder.NEWEST_FIRST, 50, null)).items.map { it.eventId })

        // Client reports (V3): rows written at V3 are counted, and a new report adds to them (ADR-0036).
        assertEquals(
            listOf(
                ClientReportCount(SdkPlatform.IOS, "0.1.0", DiscardReason.QUEUE_FULL, 7),
                ClientReportCount(SdkPlatform.ANDROID, "0.1.0", DiscardReason.QUEUE_FULL, 3),
                ClientReportCount(SdkPlatform.ANDROID, "0.1.0", DiscardReason.STORAGE_ERROR, 2),
            ),
            query.countClientReports(ClientReportCountQuery(october, null)).items,
        )

        // Platform (V4, ADR-0046): rows written before V4 are `android`; the row written at V4 keeps `ios`.
        assertEquals(SdkPlatform.ANDROID, assertNotNull(query.find(a)).summary.sdkPlatform)
        assertEquals(SdkPlatform.IOS, assertNotNull(query.find(d)).summary.sdkPlatform)
        fun platform(p: SdkPlatform) = ListQuery(october, EventFilter(null, null, null, sdkPlatform = p), SortOrder.OLDEST_FIRST, 50, null)
        assertEquals(listOf(a, b, c), query.list(platform(SdkPlatform.ANDROID)).items.map { it.eventId })
        assertEquals(listOf(d), query.list(platform(SdkPlatform.IOS)).items.map { it.eventId })
        assertEquals(
            listOf(ClientReportCount(SdkPlatform.ANDROID, "0.1.0", DiscardReason.QUEUE_FULL, 3), ClientReportCount(SdkPlatform.ANDROID, "0.1.0", DiscardReason.STORAGE_ERROR, 2)),
            query.countClientReports(ClientReportCountQuery(october, SdkPlatform.ANDROID)).items,
        )
        // The V4 migration dropped the column default: a row stored without a platform fails (ADR-0046: the ingest rules set it).
        assertFailsWith<Exception> {
            database.transaction {
                query { execute("INSERT INTO client_reports (sdk_version, reason, count) VALUES ('v', 'queue_full', 1)") }
            }
        }
        ClientReportStore(database).store(ClientReport("0.1.0", SdkPlatform.ANDROID, listOf(DiscardedCount(DiscardReason.QUEUE_FULL, 4))))
        assertEquals(4, database.transaction { query { fetchCount(CLIENT_REPORTS) } })

        // Ingestion against old rows: same content is a duplicate, other content conflicts, a new ID is stored.
        val original = StoredEvent(a, installation, "product_viewed", Instant.parse("2026-10-01T00:00:00.123Z"), "0.1.0", SdkPlatform.ANDROID,
            mapOf("price" to PropertyValue.Number("19.5"), "note" to PropertyValue.Text("가나다"), "in_stock" to PropertyValue.Flag(true)))
        assertEquals(ReceiptStatus.DUPLICATE, store(database, original).status)
        assertEquals("CONFLICT", store(database, original.copy(sdkPlatform = SdkPlatform.IOS)).code) // same ID, other platform
        assertEquals("CONFLICT", store(database, original.copy(name = "other")).code)
        assertEquals(ReceiptStatus.ACCEPTED, store(database, original.copy(eventId = UUID.randomUUID())).status)
        assertEquals(5, database.transaction { query { fetchCount(EVENTS) } })
    }

    private fun listQuery(order: SortOrder = SortOrder.NEWEST_FIRST) = ListQuery(october, noFilter, order, 50, null)

    private suspend fun store(database: Database, id: UUID, name: String) = store(database,
        StoredEvent(id, installation, name, Instant.parse("2026-10-01T00:00:00.123Z"), "0.1.0", SdkPlatform.ANDROID, emptyMap()))

    private suspend fun store(database: Database, event: StoredEvent) =
        EventStore(database).store(planBatch(ValidatedBatch(listOf(ValidatedItem(0, event.eventId.toString(), event, null))))).single()

    /** One empty database per test, so migrations always start from nothing. */
    private fun freshDatabase(): Database {
        if (!postgres.isRunning) postgres.start()
        val name = "upgrade_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return Database(postgres.jdbcUrl.replace("/${postgres.databaseName}", "/$name"), postgres.username, postgres.password)
            .also { opened += it }
    }

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
