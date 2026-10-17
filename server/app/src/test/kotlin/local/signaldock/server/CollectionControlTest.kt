package local.signaldock.server

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import local.signaldock.persistence.Database
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.openapitools.client.models.BatchAckResponse
import org.openapitools.client.models.ItemAck
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** ADR-0037: operator block by event name and SDK version, and the global shed switch. */
class CollectionControlTest {
    private lateinit var database: Database

    @BeforeTest
    fun startDatabase() {
        if (!postgres.isRunning) postgres.start()
        database = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        database.migrate()
        runBlocking { database.transaction { query { deleteFrom(EVENTS).execute() } } }
    }

    @AfterTest
    fun stopDatabase() = database.close()

    private fun id(n: Int) = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

    private fun event(n: Int, name: String, sdkVersion: String = "0.1.0") =
        """{"eventId":"${id(n)}","name":"$name","occurredAt":"2026-10-09T21:30:00.123Z","properties":{},"sdkVersion":"$sdkVersion","contractVersion":"1"}"""

    private fun batch(vararg events: String) =
        """{"installationId":"47c4d89f-352a-41da-9b31-529ea756515a","events":[${events.joinToString(",")}]}"""

    private suspend fun HttpClient.ingest(body: String): HttpResponse = post("/v1/events:batch") {
        header("SignalDock-Contract-Version", "1")
        header(HttpHeaders.Authorization, "Bearer collection-key")
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        setBody(body)
    }

    private fun stored() = runBlocking { database.transaction { query { fetchCount(EVENTS) } } }

    private fun io.ktor.server.testing.ApplicationTestBuilder.useControl(control: CollectionControl) =
        application { ingestionModule(database, "collection-key", control = control) }

    private fun acks(text: String) = Json.decodeFromString<BatchAckResponse>(text).results

    @Test
    fun `nothing is blocked by default`() = testApplication {
        useControl(CollectionControl())
        val results = acks(client.ingest(batch(event(1, "a"), event(2, "b", "9.9.9"))).bodyAsText())
        assertEquals(listOf(ItemAck.Status.ACCEPTED, ItemAck.Status.ACCEPTED), results.map { it.status })
        assertEquals(2, stored())
    }

    @Test
    fun `a blocked event name is rejected per item and the other events are stored`() = testApplication {
        useControl(CollectionControl.fromEnv(mapOf("SIGNALDOCK_BLOCKED_EVENTS" to " noisy_event , other ")::get))
        val response = client.ingest(batch(event(1, "noisy_event"), event(2, "good_event"), event(3, "noisy_event_2")))
        assertEquals(HttpStatusCode.OK, response.status)
        val results = acks(response.bodyAsText())
        assertEquals(listOf(ItemAck.Status.REJECTED, ItemAck.Status.ACCEPTED, ItemAck.Status.ACCEPTED), results.map { it.status })
        assertEquals("COLLECTION_BLOCKED", results[0].code)
        assertEquals(id(1).toString(), results[0].eventId)
        assertEquals(2, stored())
    }

    @Test
    fun `a same-ID sibling of a blocked event is not stored`() = testApplication {
        useControl(CollectionControl.fromEnv(mapOf("SIGNALDOCK_BLOCKED_EVENTS" to "noisy_event")::get))
        val results = acks(client.ingest(batch(event(1, "noisy_event"), event(1, "good_event"))).bodyAsText())
        assertEquals(listOf("COLLECTION_BLOCKED", "EVENT_ID_CONFLICT"), results.map { it.code })
        assertEquals(0, stored())
    }

    @Test
    fun `a blocked SDK version is rejected per item`() = testApplication {
        useControl(CollectionControl.fromEnv(mapOf("SIGNALDOCK_BLOCKED_SDK_VERSIONS" to "0.1.0")::get))
        val results = acks(client.ingest(batch(event(1, "a", "0.1.0"), event(2, "a", "0.1.1"))).bodyAsText())
        assertEquals(listOf(ItemAck.Status.REJECTED, ItemAck.Status.ACCEPTED), results.map { it.status })
        assertEquals("COLLECTION_BLOCKED", results[0].code)
        assertEquals(1, stored())
    }

    @Test
    fun `shed answers 503 with the configured Retry-After and stores nothing`() = testApplication {
        useControl(CollectionControl.fromEnv(mapOf("SIGNALDOCK_INGEST_SHED" to "true", "SIGNALDOCK_INGEST_SHED_RETRY_AFTER_SECONDS" to "120")::get))
        val response = client.ingest(batch(event(1, "a")))
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("120", response.headers[HttpHeaders.RetryAfter])
        assertEquals(0, stored())
        // The key is checked first: a caller without it learns nothing about the shed state.
        val noKey = client.post("/v1/events:batch") {
            header("SignalDock-Contract-Version", "1")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(batch(event(1, "a")))
        }
        assertEquals(HttpStatusCode.Unauthorized, noKey.status)
    }

    @Test
    fun `shed default Retry-After is 1 second`() = testApplication {
        useControl(CollectionControl.fromEnv(mapOf("SIGNALDOCK_INGEST_SHED" to "true")::get))
        assertEquals("1", client.ingest(batch(event(1, "a"))).headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `env parsing defaults to no control and fails on a malformed value`() {
        val none = CollectionControl.fromEnv { null }
        assertEquals(false, none.shed)
        assertEquals(1, none.shedRetryAfterSeconds)
        assertEquals(false, CollectionControl.fromEnv(mapOf("SIGNALDOCK_INGEST_SHED" to "")::get).shed) // blank = off
        assertFailsWith<IllegalArgumentException> { CollectionControl.fromEnv(mapOf("SIGNALDOCK_INGEST_SHED" to "yes")::get) }
        for (bad in listOf("0", "-5", "abc", "86401")) {
            assertFailsWith<IllegalArgumentException> {
                CollectionControl.fromEnv(mapOf("SIGNALDOCK_INGEST_SHED_RETRY_AFTER_SECONDS" to bad)::get)
            }
        }
    }

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
