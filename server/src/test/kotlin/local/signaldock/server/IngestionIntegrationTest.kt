package local.signaldock.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.openapitools.client.models.ItemAck
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IngestionIntegrationTest {
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
    fun `stores valid items rejects invalid items and compares duplicate content`() = runBlocking {
        val id = "30d66f95-2435-4e56-b52a-70b1c8bba4ef"
        val valid = event(id)
        val invalid = event("30d66f95-2435-4e56-b52a-70b1c8bba4f0", name = " invalid")
        val ingestion = Ingestion(database)

        val first = ingestion.ingest(parse(batch("$valid,$invalid")))
        assertEquals(listOf(ItemAck.Status.ACCEPTED, ItemAck.Status.REJECTED), first.results.map { it.status })
        assertEquals(1, countRows())

        val resend = ingestion.ingest(parse(batch(valid)))
        assertEquals(ItemAck.Status.DUPLICATE, resend.results.single().status)
        assertEquals(1, countRows())

        val conflict = ingestion.ingest(parse(batch(event(id, properties = """{"price":2}"""))))
        assertEquals(ItemAck.Status.REJECTED, conflict.results.single().status)
        assertEquals("CONFLICT", conflict.results.single().code)
        assertEquals(1, countRows())
    }

    @Test
    fun `stores a valid sibling and rejects a number outside the storage range`() = runBlocking {
        val valid = event("30d66f95-2435-4e56-b52a-70b1c8bba4f1")
        val invalid = event(
            "30d66f95-2435-4e56-b52a-70b1c8bba4f2",
            properties = """{"price":1e-20000}""",
        )

        val response = Ingestion(database).ingest(parse(batch("$valid,$invalid")))

        assertEquals(listOf(ItemAck.Status.ACCEPTED, ItemAck.Status.REJECTED), response.results.map { it.status })
        assertEquals("INVALID_PROPERTIES", response.results[1].code)
        assertEquals(1, countRows())
    }

    @Test
    fun `same content repeats once while different content group stores nothing`() = runBlocking {
        val ingestion = Ingestion(database)
        val sameId = "50cc1adb-d543-4121-b0e1-ee505e41dd4b"
        val same = event(sameId)
        val accepted = ingestion.ingest(parse(batch("$same,$same")))
        assertEquals(listOf(ItemAck.Status.ACCEPTED, ItemAck.Status.DUPLICATE), accepted.results.map { it.status })

        val conflictId = "50cc1adb-d543-4121-b0e1-ee505e41dd4c"
        val conflict = ingestion.ingest(parse(batch(
            "${event(conflictId, properties = """{"n":1}""")},${event(conflictId, properties = """{"n":2}""")}",
        )))
        assertTrue(conflict.results.all { it.status == ItemAck.Status.REJECTED && it.code == "CONFLICT" })
        assertEquals(1, countRows())
    }

    @Test
    fun `concurrent inserts rely on unique constraint and store one row`() = runBlocking {
        val parsed = parse(batch(event("cdf278e4-13c1-4cc8-9256-d46a68858f2a")))
        val results = coroutineScope {
            listOf(async { Ingestion(database).ingest(parsed) }, async { Ingestion(database).ingest(parsed) }).awaitAll()
        }
        assertEquals(setOf(ItemAck.Status.ACCEPTED, ItemAck.Status.DUPLICATE), results.map { it.results.single().status }.toSet())
        assertEquals(1, countRows())
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
                        EVENTS.SDK_VERSION, EVENTS.PROPERTIES)
                        .values(id, id, "timeout", java.time.OffsetDateTime.now(), "0.1.0", org.jooq.JSONB.valueOf("{}"))
                        .execute()
                    fetch("select pg_sleep(2)")
                }
            }
        }
        assertEquals(0, countRows())
        val setting = database.transaction {
            connection.createStatement().use { statement ->
                statement.executeQuery("show statement_timeout").use { result -> result.next(); result.getString(1) }
            }
        }
        assertEquals("0", setting)
        assertEquals(1, database.transaction { query { fetchValue("select 1", Int::class.java) } })
    }

    @Test
    fun `lock timeout rolls back and a resend succeeds after lock release`() = runBlocking {
        val id = "ac9b4caa-a055-432b-9d2a-25e260d836a5"
        val parsed = parse(batch(event(id)))
        Ingestion(database).ingest(parsed)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { lock ->
            lock.autoCommit = false
            lock.prepareStatement("update events set name = name where event_id = ?::uuid").use {
                it.setString(1, id)
                it.executeUpdate()
            }
            assertFailsWith<DatabaseUnavailable> { Ingestion(database).ingest(parsed) }
            lock.rollback()
        }
        assertEquals(ItemAck.Status.DUPLICATE, Ingestion(database).ingest(parsed).results.single().status)
        assertEquals(1, countRows())
    }

    @Test
    fun `lost response after commit becomes duplicate on resend`() = runBlocking {
        val id = "ee5a65bd-066b-470f-a0a7-0cc473cf54e5"
        val accepted = Ingestion(database).ingest(parse(batch(event(id))))
        val clientReceivedResponse = runCatching { simulateResponseWrite(accepted) }.isSuccess
        assertEquals(false, clientReceivedResponse)
        assertEquals(ItemAck.Status.ACCEPTED, accepted.results.single().status)
        assertEquals(1, countRows())

        val duplicate = Ingestion(database).ingest(parse(batch(event(id))))
        assertEquals(ItemAck.Status.DUPLICATE, duplicate.results.single().status)
        assertEquals(1, countRows())
    }

    @Test
    fun `route rejects missing contract before storage`() = testApplication {
        application { ingestionModule(database, "test-key") }
        val response = client.post("/v1/events:batch") {
            header(HttpHeaders.Authorization, "Bearer test-key")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(batch(event("eb755b76-0902-4c6a-aa15-389dfb75036c")))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("CONTRACT_HEADER_MISSING"))
        assertEquals(0, runBlocking { countRows() })
    }

    @Test
    fun `route rejects mixed item contract versions before storage`() = testApplication {
        application { ingestionModule(database, "test-key") }
        val contractOne = event("eb755b76-0902-4c6a-aa15-389dfb75036d")
        val contractTwo = event("eb755b76-0902-4c6a-aa15-389dfb75036e", contractVersion = "2")

        val response = client.post("/v1/events:batch") {
            header("SignalDock-Contract-Version", "1")
            header(HttpHeaders.Authorization, "Bearer test-key")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(batch("$contractOne,$contractTwo"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("CONTRACT_VERSION_UNSUPPORTED"))
        assertEquals(0, runBlocking { countRows() })
    }

    @Test
    fun `route returns 401 ErrorResponse for an invalid collection key`() = testApplication {
        application { ingestionModule(database, "test-key") }

        val response = client.post("/v1/events:batch") {
            header("SignalDock-Contract-Version", "1")
            header(HttpHeaders.Authorization, "Bearer wrong-key")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(batch(event("eb755b76-0902-4c6a-aa15-389dfb75036f")))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]?.startsWith("application/json") == true)
        assertTrue(response.bodyAsText().contains("COLLECTION_KEY_INVALID"))
        assertEquals(0, runBlocking { countRows() })
    }

    @Test
    fun `route returns 415 ErrorResponse for unsupported media boundaries`() = testApplication {
        application { ingestionModule(database, "test-key") }
        val requestBody = batch(event("eb755b76-0902-4c6a-aa15-389dfb750370"))

        val unsupportedType = client.post("/v1/events:batch") {
            header("SignalDock-Contract-Version", "1")
            header(HttpHeaders.Authorization, "Bearer test-key")
            header(HttpHeaders.ContentType, ContentType.Text.Plain)
            setBody(requestBody)
        }
        val unsupportedEncoding = client.post("/v1/events:batch") {
            header("SignalDock-Contract-Version", "1")
            header(HttpHeaders.Authorization, "Bearer test-key")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.ContentEncoding, "gzip")
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, unsupportedType.status)
        assertTrue(unsupportedType.bodyAsText().contains("CONTENT_TYPE_UNSUPPORTED"))
        assertEquals(HttpStatusCode.UnsupportedMediaType, unsupportedEncoding.status)
        assertTrue(unsupportedEncoding.bodyAsText().contains("CONTENT_ENCODING_UNSUPPORTED"))
        assertEquals(0, runBlocking { countRows() })
    }

    @Test
    fun `route returns retryable 503 ErrorResponse with Retry-After`() = testApplication {
        database.close()
        database = newDatabase(DatabaseLimits(concurrency = 1, poolSize = 2, requestMs = 2_000,
            admissionMs = 100, connectionMs = 250, lockMs = 100, statementMs = 300, cleanupMs = 250, responseMs = 100))
        database.migrate()
        application { ingestionModule(database, "test-key") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        coroutineScope {
            val holder = async(Dispatchers.Default) {
                database.transaction {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            try {
                val response = client.post("/v1/events:batch") {
                    header("SignalDock-Contract-Version", "1")
                    header(HttpHeaders.Authorization, "Bearer test-key")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(batch(event("eb755b76-0902-4c6a-aa15-389dfb750371")))
                }

                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertEquals("1", response.headers[HttpHeaders.RetryAfter])
                assertTrue(response.bodyAsText().contains("TEMPORARILY_UNAVAILABLE"))
            } finally {
                release.countDown()
                holder.await()
            }
        }
        assertEquals(0, runBlocking { countRows() })
    }

    private suspend fun countRows(): Int = database.transaction { query { fetchCount(EVENTS) } }

    private fun newDatabase(limits: DatabaseLimits = DatabaseLimits()) =
        Database(postgres.jdbcUrl, postgres.username, postgres.password, limits)

    private fun batch(events: String) = """{"installationId":"47c4d89f-352a-41da-9b31-529ea756515a","events":[$events]}"""

    private fun parse(source: String) = parseBatch(source.encodeToByteArray(), "1")

    private fun event(
        id: String,
        name: String = "product_viewed",
        properties: String = """{"price":1}""",
        contractVersion: String = "1",
    ) = """{"eventId":"$id","name":"$name","occurredAt":"2026-10-09T21:30:00.123Z","properties":$properties,"sdkVersion":"0.1.0","contractVersion":"$contractVersion"}"""

    private fun simulateResponseWrite(@Suppress("UNUSED_PARAMETER") response: Any): Nothing =
        throw ResponseLost()

    private class ResponseLost : RuntimeException()

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
