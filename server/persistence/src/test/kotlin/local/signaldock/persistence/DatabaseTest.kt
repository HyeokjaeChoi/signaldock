package local.signaldock.persistence

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import local.signaldock.ingestion.PropertyValue
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseTest {
    private lateinit var database: Database

    @BeforeTest
    fun startDatabase() {
        if (!postgres.isRunning) postgres.start()
        database = newDatabase()
        database.migrate()
        runBlocking { database.transaction { query { deleteFrom(EVENTS).execute() } } }
    }

    @AfterTest
    fun stopDatabase() {
        database.close()
    }

    @Test
    fun `admission overload fails fast and releases the slot`() = runBlocking {
        database.close()
        database = newDatabase(DatabaseLimits(concurrency = 1, poolSize = 2, requestMs = 2_000,
            admissionMs = 100, connectionMs = 250, lockMs = 100, statementMs = 300, cleanupMs = 250, responseMs = 100))
        database.migrate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = async(Dispatchers.Default) {
            database.transaction {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals("ADMISSION_TIMEOUT", assertFailsWith<DatabaseUnavailable> { database.transaction { 1 } }.reason)
        release.countDown()
        holder.await()
        assertEquals(1, database.transaction { 1 })
    }

    @Test
    fun `statement timeout rolls back and the connection is reusable without leaked settings`() = runBlocking {
        val id = UUID.fromString("9d0aba71-d7a8-4aa1-8b77-ac7ed0c65c73")
        assertFailsWith<DatabaseUnavailable> {
            database.transaction {
                query {
                    insertInto(EVENTS, EVENTS.EVENT_ID, EVENTS.INSTALLATION_ID, EVENTS.NAME, EVENTS.OCCURRED_AT,
                        EVENTS.SDK_VERSION, EVENTS.SDK_PLATFORM, EVENTS.PROPERTIES)
                        .values(id, id, "timeout", java.time.OffsetDateTime.now(), "0.1.0", "android", org.jooq.JSONB.valueOf("{}"))
                        .execute()
                    fetch("select pg_sleep(2)")
                }
            }
        }
        assertEquals(0, database.transaction { query { fetchCount(EVENTS) } })
        val setting = database.transaction {
            connection.createStatement().use { statement ->
                statement.executeQuery("show statement_timeout").use { result -> result.next(); result.getString(1) }
            }
        }
        assertEquals("0", setting)
        assertEquals(1, database.transaction { query { fetchValue("select 1", Int::class.java) } })
    }

    // Q125: one request time budget. The parts cannot add up to more than the budget, and lock < statement.
    @Test
    fun `limits keep one request budget and a lock timeout shorter than the statement timeout`() {
        assertFailsWith<IllegalArgumentException> { DatabaseLimits(lockMs = 1_000, statementMs = 1_000) }
        assertFailsWith<IllegalArgumentException> { DatabaseLimits(lockMs = 2_000, statementMs = 1_000) }
        // admission 250 + connection 500 + statement 1000 + cleanup 500 + response 250 = 2500
        assertFailsWith<IllegalArgumentException> { DatabaseLimits(requestMs = 2_500) }
        assertFailsWith<IllegalArgumentException> { DatabaseLimits(requestMs = 120_001, statementMs = 1_000) }
        DatabaseLimits(requestMs = 2_501)
    }

    // Q125: statement_timeout and lock_timeout are applied per statement on the transaction's connection.
    @Test
    fun `each statement runs with the configured statement_timeout and a shorter lock_timeout`() = runBlocking {
        fun setting(name: String): Long = runBlocking {
            database.transaction {
                query { fetchValue("select setting from pg_settings where name = '$name'").toString().toLong() }
            }
        }
        val statement = setting("statement_timeout")
        val lock = setting("lock_timeout")
        assertEquals(database.limits.statementMs, statement)
        assertEquals(database.limits.lockMs, lock)
        assertTrue(lock < statement)
    }

    // Q125: a statement limit is not a whole-transaction limit. The request budget caps later statements.
    @Test
    fun `a multi-statement transaction stays inside the request budget and rolls back every statement`() = runBlocking {
        database.close()
        // Budget for database work: 2000 - cleanup 250 - response 100 = 1650 ms. Each statement may use up to 1000 ms.
        database = newDatabase(DatabaseLimits(concurrency = 1, poolSize = 2, requestMs = 2_000,
            admissionMs = 100, connectionMs = 250, lockMs = 100, statementMs = 1_000, cleanupMs = 250, responseMs = 100))
        database.migrate()
        val id = UUID.fromString("5b0c3d1e-7a39-4c63-8d2b-0f0d5a1e9c11")
        val started = System.nanoTime()
        assertFailsWith<DatabaseUnavailable> {
            database.transaction {
                query {
                    insertInto(EVENTS, EVENTS.EVENT_ID, EVENTS.INSTALLATION_ID, EVENTS.NAME, EVENTS.OCCURRED_AT,
                        EVENTS.SDK_VERSION, EVENTS.SDK_PLATFORM, EVENTS.PROPERTIES)
                        .values(id, id, "budget", java.time.OffsetDateTime.now(), "0.1.0", "android", org.jooq.JSONB.valueOf("{}"))
                        .execute()
                }
                query { fetch("select pg_sleep(0.9)") }   // under the 1000 ms statement limit
                query { fetch("select pg_sleep(0.95)") }  // under it too, but over what the budget has left (about 700 ms)
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < database.limits.requestMs, "took ${elapsedMs}ms")
        assertEquals(0, database.transaction { query { fetchCount(EVENTS) } })
    }

    // Q125: commit/rollback boundary when the caller goes away while a statement runs.
    @Test
    fun `cancelling the caller cancels the running statement, rolls back and frees the slot`() = runBlocking {
        database.close()
        database = newDatabase(DatabaseLimits(concurrency = 1, poolSize = 2, requestMs = 10_000, admissionMs = 2_000, statementMs = 5_000, lockMs = 250))
        database.migrate()
        val id = UUID.fromString("0a4f8c52-3a6d-4d7e-9a54-6f1c0b7d2e33")
        val job = launch(Dispatchers.Default) {
            database.transaction {
                query {
                    insertInto(EVENTS, EVENTS.EVENT_ID, EVENTS.INSTALLATION_ID, EVENTS.NAME, EVENTS.OCCURRED_AT,
                        EVENTS.SDK_VERSION, EVENTS.SDK_PLATFORM, EVENTS.PROPERTIES)
                        .values(id, id, "cancel", java.time.OffsetDateTime.now(), "0.1.0", "android", org.jooq.JSONB.valueOf("{}"))
                        .execute()
                }
                query { fetch("select pg_sleep(4)") }
            }
        }
        // PostgreSQL ignores a cancel that arrives while idle: cancel only once a backend runs pg_sleep(4).
        assertTrue(sleepIsActive(), "no active backend ran pg_sleep(4) within 5 s")
        val started = System.nanoTime() // measure cancel latency only, not setup or polling
        withTimeout(3_000) { job.cancelAndJoin() } // a missed cancel would wait for pg_sleep(4)
        // concurrency is 1: this call only succeeds if the cancelled worker rolled back and released its permit.
        assertEquals(0, database.transaction { query { fetchCount(EVENTS) } })
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 3_000, "pg_sleep(4) was not cancelled: ${elapsedMs}ms")
    }

    // Q125: a cancel that is seen before COMMIT must not commit.
    @Test
    fun `a cancel seen before commit rolls the transaction back`() = runBlocking {
        val id = UUID.fromString("7e2d9b64-1c8a-4f35-b0a7-3d5e6f8a9b44")
        lateinit var job: Job
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            database.transaction {
                query {
                    insertInto(EVENTS, EVENTS.EVENT_ID, EVENTS.INSTALLATION_ID, EVENTS.NAME, EVENTS.OCCURRED_AT,
                        EVENTS.SDK_VERSION, EVENTS.SDK_PLATFORM, EVENTS.PROPERTIES)
                        .values(id, id, "precommit", java.time.OffsetDateTime.now(), "0.1.0", "android", org.jooq.JSONB.valueOf("{}"))
                        .execute()
                }
                job.cancel() // runs the transaction's abort hook on this worker thread, before commit
            }
        }
        job.start()
        job.join()
        assertEquals(0, database.transaction { query { fetchCount(EVENTS) } })
    }

    @Test
    fun `properties keep number literals and round trip through JSON`() {
        val properties = mapOf(
            "n" to PropertyValue.Number("1e0"),
            "s" to PropertyValue.Text("a\"b"),
            "b" to PropertyValue.Flag(true),
        )

        val json = propertiesJson(properties)

        assertEquals("""{"n":1e0,"s":"a\"b","b":true}""", json)
        assertEquals(
            mapOf("n" to PropertyValue.Number("1e0"), "s" to properties["s"], "b" to properties["b"]),
            propertiesFrom(kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject),
        )
    }

    private fun sleepIsActive(deadlineMs: Long = 5_000): Boolean =
        java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            val end = System.nanoTime() + deadlineMs * 1_000_000
            while (System.nanoTime() < end) {
                c.createStatement().use { st ->
                    st.executeQuery("select 1 from pg_stat_activity where state = 'active' and pid <> pg_backend_pid() " +
                        "and query like '%pg_sleep(4)%'").use { if (it.next()) return true }
                }
                Thread.sleep(50)
            }
            false
        }

    private fun newDatabase(limits: DatabaseLimits = DatabaseLimits()) =
        Database(postgres.jdbcUrl, postgres.username, postgres.password, limits)

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
