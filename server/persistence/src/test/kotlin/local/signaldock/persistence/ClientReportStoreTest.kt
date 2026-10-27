package local.signaldock.persistence

import kotlinx.coroutines.runBlocking
import local.signaldock.ingestion.ClientReport
import local.signaldock.ingestion.DiscardReason
import local.signaldock.ingestion.DiscardedCount
import local.signaldock.ingestion.SdkPlatform
import local.signaldock.query.ClientReportCountQuery
import local.signaldock.query.ClientReportCount
import local.signaldock.query.MAX_CLIENT_REPORT_COUNTS
import local.signaldock.query.Period
import local.signaldock.server.jooq.tables.ClientReports.CLIENT_REPORTS
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientReportStoreTest {
    private lateinit var database: Database
    private val period = Period(Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-13T00:00:00Z"))

    @BeforeTest
    fun startDatabase() {
        if (!postgres.isRunning) postgres.start()
        database = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        database.migrate()
        runBlocking { database.transaction { query { deleteFrom(CLIENT_REPORTS).execute() } } }
    }

    @AfterTest
    fun stopDatabase() {
        database.close()
    }

    private fun insert(receivedAt: String, sdkVersion: String, reason: String, count: Int, platform: String = "android") = runBlocking {
        database.transaction {
            query {
                execute(
                    "INSERT INTO client_reports (received_at, sdk_platform, sdk_version, reason, count) VALUES (?::timestamptz, ?, ?, ?, ?)",
                    receivedAt, platform, sdkVersion, reason, count,
                )
            }
        }
        Unit
    }

    private fun rows() = runBlocking {
        database.transaction { query { selectFrom(CLIENT_REPORTS).orderBy(CLIENT_REPORTS.ID).fetch().map { Triple(it.sdkVersion, it.reason, it.count) } } }
    }

    @Test
    fun `stores one row per entry with a receipt time and no other identity`() = runBlocking {
        ClientReportStore(database).store(ClientReport("0.1.0", SdkPlatform.ANDROID, listOf(
            DiscardedCount(DiscardReason.QUEUE_FULL, 3), DiscardedCount(DiscardReason.SERVER_REJECTED, 1_000_000),
        )))
        assertEquals(listOf(Triple("0.1.0", "queue_full", 3), Triple("0.1.0", "server_rejected", 1_000_000)), rows())
        assertEquals(listOf("id", "received_at", "sdk_version", "reason", "count", "sdk_platform"), CLIENT_REPORTS.fields().map { it.name })
        val receivedAt = database.transaction { query { select(CLIENT_REPORTS.RECEIVED_AT).from(CLIENT_REPORTS).fetch { it.value1()!!.toInstant() } } }
        assertTrue(receivedAt.all { it.isAfter(Instant.now().minusSeconds(60)) })
    }

    @Test
    fun `a failing entry rolls back the whole report`() = runBlocking {
        // 0 violates the count CHECK, so the second entry fails and the first must not stay.
        assertFailsWith<Exception> {
            ClientReportStore(database).store(ClientReport("0.1.0", SdkPlatform.ANDROID, listOf(
                DiscardedCount(DiscardReason.QUEUE_FULL, 3), DiscardedCount(DiscardReason.STORAGE_ERROR, 0),
            )))
        }
        assertEquals(emptyList(), rows())
    }

    @Test
    fun `constraints reject empty versions, unknown reasons, and counts out of range`() {
        for ((version, reason, count) in listOf(
            Triple("", "queue_full", 1), Triple("v", "unknown", 1), Triple("v", "queue_full", 0), Triple("v", "queue_full", 1_000_001),
        )) {
            assertFailsWith<Exception>("$version $reason $count") { insert("2026-10-12T01:00:00Z", version, reason, count) }
        }
        assertEquals(emptyList(), rows())
    }

    @Test
    fun `counts sum by version and reason in order and respect the period bounds`() = runBlocking {
        insert("2026-10-12T00:00:00.000Z", "0.1.0", "queue_full", 2) // from: in
        insert("2026-10-12T10:00:00.000Z", "0.1.0", "queue_full", 3)
        insert("2026-10-12T10:00:00.000Z", "0.1.0", "storage_error", 5)
        insert("2026-10-12T10:00:00.000Z", "0.2.0", "queue_full", 9)
        insert("2026-10-11T23:59:59.999Z", "0.1.0", "queue_full", 100)
        insert("2026-10-13T00:00:00.000Z", "0.1.0", "queue_full", 100) // to: out
        val counts = QueryStore(database).countClientReports(ClientReportCountQuery(period, null))
        assertFalse(counts.truncated)
        assertEquals(
            listOf(
                ClientReportCount(SdkPlatform.ANDROID, "0.2.0", DiscardReason.QUEUE_FULL, 9),
                ClientReportCount(SdkPlatform.ANDROID, "0.1.0", DiscardReason.QUEUE_FULL, 5),
                ClientReportCount(SdkPlatform.ANDROID, "0.1.0", DiscardReason.STORAGE_ERROR, 5),
            ),
            counts.items,
        )
    }

    @Test
    fun `the platform is stored, the constraint rejects other values, and counts group and filter by platform`() = runBlocking {
        ClientReportStore(database).store(ClientReport("0.1.0", SdkPlatform.IOS, listOf(DiscardedCount(DiscardReason.QUEUE_FULL, 4))))
        assertEquals(listOf("ios"), database.transaction { query { select(CLIENT_REPORTS.SDK_PLATFORM).from(CLIENT_REPORTS).fetch { it.value1()!! } } })
        for (bad in listOf("iOS", "unknown", "")) {
            assertFailsWith<Exception>(bad) { insert("2026-10-12T01:00:00Z", "v", "queue_full", 1, platform = bad) }
        }
        // The same version on both platforms is two rows (ADR-0046); ties in count order by platform, then version.
        insert("2026-10-12T10:00:00Z", "0.1.0", "queue_full", 4)
        insert("2026-10-12T10:00:00Z", "0.1.0", "queue_full", 4, platform = "ios")
        insert("2026-10-12T10:00:00Z", "0.2.0", "storage_error", 4)
        insert("2026-10-12T10:00:00Z", "0.1.0", "storage_error", 1, platform = "ios")
        val store = QueryStore(database)
        assertEquals(
            listOf(
                ClientReportCount(SdkPlatform.ANDROID, "0.1.0", DiscardReason.QUEUE_FULL, 4),
                ClientReportCount(SdkPlatform.ANDROID, "0.2.0", DiscardReason.STORAGE_ERROR, 4),
                ClientReportCount(SdkPlatform.IOS, "0.1.0", DiscardReason.QUEUE_FULL, 4),
                ClientReportCount(SdkPlatform.IOS, "0.1.0", DiscardReason.STORAGE_ERROR, 1),
            ),
            store.countClientReports(ClientReportCountQuery(period, null)).items,
        )
        assertEquals(
            listOf(
                ClientReportCount(SdkPlatform.IOS, "0.1.0", DiscardReason.QUEUE_FULL, 4),
                ClientReportCount(SdkPlatform.IOS, "0.1.0", DiscardReason.STORAGE_ERROR, 1),
            ),
            store.countClientReports(ClientReportCountQuery(period, SdkPlatform.IOS)).items,
        )
        assertEquals(
            listOf(SdkPlatform.ANDROID, SdkPlatform.ANDROID),
            store.countClientReports(ClientReportCountQuery(period, SdkPlatform.ANDROID)).items.map { it.sdkPlatform },
        )
    }

    @Test
    fun `counts are empty without rows and the sum does not overflow an int`() = runBlocking {
        assertEquals(emptyList(), QueryStore(database).countClientReports(ClientReportCountQuery(period, null)).items)
        repeat(3) { insert("2026-10-12T10:00:00Z", "0.1.0", "queue_full", 1_000_000) }
        database.transaction {
            query {
                execute(
                    """INSERT INTO client_reports (received_at, sdk_platform, sdk_version, reason, count)
                       SELECT timestamptz '2026-10-12 10:00:00+00', 'android', '0.1.0', 'storage_error', 1000000 FROM generate_series(1, 3000)""",
                )
            }
        }
        assertEquals(listOf(3_000_000_000L, 3_000_000L), QueryStore(database).countClientReports(ClientReportCountQuery(period, null)).items.map { it.count })
    }

    @Test
    fun `counts stop at 100 rows and mark truncation`() = runBlocking {
        database.transaction {
            query {
                execute(
                    """INSERT INTO client_reports (received_at, sdk_platform, sdk_version, reason, count)
                       SELECT timestamptz '2026-10-12 10:00:00+00', 'android', 'v' || lpad(g::text, 3, '0'), 'queue_full', 1 FROM generate_series(1, 101) g""",
                )
            }
        }
        val counts = QueryStore(database).countClientReports(ClientReportCountQuery(period, null))
        assertEquals(MAX_CLIENT_REPORT_COUNTS, counts.items.size)
        assertTrue(counts.truncated)
    }

    @Test
    fun `period query plan uses the receivedAt index`() = runBlocking {
        database.transaction {
            query {
                execute(
                    """INSERT INTO client_reports (received_at, sdk_platform, sdk_version, reason, count)
                       SELECT timestamptz '2026-09-01 00:00:00+00' + g * interval '2 minutes', 'android', '0.1.' || (g % 3), 'queue_full', 1
                       FROM generate_series(1, 20000) g""",
                )
            }
        }
        // ANALYZE cannot run inside a transaction block, so use a plain connection.
        java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { it.execute("ANALYZE client_reports") }
            val plan = c.createStatement().use { s ->
                s.executeQuery(
                    """EXPLAIN SELECT sdk_version, reason, sum(count) FROM client_reports
                       WHERE received_at >= '2026-09-10 00:00:00+00' AND received_at < '2026-09-11 00:00:00+00'
                       GROUP BY sdk_version, reason""",
                ).use { r -> generateSequence { if (r.next()) r.getString(1) else null }.joinToString("\n") }
            }
            assertTrue(plan.contains("client_reports_received_idx"), plan)
            assertFalse(plan.contains("Seq Scan"), plan)
        }
    }

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
