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
import local.signaldock.server.jooq.tables.ClientReports.CLIENT_REPORTS
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
        runBlocking { database.transaction { query { deleteFrom(CLIENT_REPORTS).execute(); deleteFrom(EVENTS).execute() } } }
    }

    @AfterTest
    fun stopDatabase() = database.close()

    private fun id(n: Int) = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

    /** `platform` null leaves `sdkPlatform` out, like Android SDK 0.1.0 to 0.3.0. */
    private fun event(n: Int, name: String, sdkVersion: String = "0.1.0", platform: String? = null) =
        """{"eventId":"${id(n)}","name":"$name","occurredAt":"2026-10-09T21:30:00.123Z","properties":{},"sdkVersion":"$sdkVersion",""" +
            (platform?.let { """"sdkPlatform":"$it",""" } ?: "") + """"contractVersion":"1"}"""

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

    private fun statuses(control: String, vararg events: String) = testApplication {
        useControl(CollectionControl.fromEnv(mapOf("SIGNALDOCK_BLOCKED_SDK_VERSIONS" to control)::get))
        runBlocking { database.transaction { query { deleteFrom(EVENTS).execute() } } } // the same IDs are reused for each setting
        client.ingest(batch(*events)).bodyAsText().let { lastStatuses = acks(it).map { ack -> ack.code ?: ack.status.value } }
    }

    private var lastStatuses: List<String> = emptyList()

    @Test
    fun `an item with a platform prefix blocks only that platform and version`() {
        val events = arrayOf(
            event(1, "a", "0.1.0", "ios"), event(2, "a", "0.1.0", "android"), event(3, "a", "0.1.0"), // no field: android
            event(4, "a", "0.2.0", "ios"),
        )
        statuses("ios:0.1.0", *events)
        assertEquals(listOf("COLLECTION_BLOCKED", "accepted", "accepted", "accepted"), lastStatuses)
        statuses("android:0.1.0", *events)
        assertEquals(listOf("accepted", "COLLECTION_BLOCKED", "COLLECTION_BLOCKED", "accepted"), lastStatuses)
        // Both pairs, with blanks around the items.
        statuses(" ios:0.1.0 , android:0.1.0 ,, ios:0.2.0", *events)
        assertEquals(listOf("COLLECTION_BLOCKED", "COLLECTION_BLOCKED", "COLLECTION_BLOCKED", "COLLECTION_BLOCKED"), lastStatuses)
    }

    @Test
    fun `an item with no prefix means android, so old settings keep their meaning`() {
        val events = arrayOf(event(1, "a", "0.1.0", "ios"), event(2, "a", "0.1.0", "android"), event(3, "a", "0.1.0"), event(4, "a", "0.1.1"))
        statuses("0.1.0", *events)
        assertEquals(listOf("accepted", "COLLECTION_BLOCKED", "COLLECTION_BLOCKED", "accepted"), lastStatuses)
        // An unprefixed item and its `android:` form are the same pair.
        assertEquals(
            CollectionControl.fromEnv(mapOf("SIGNALDOCK_BLOCKED_SDK_VERSIONS" to "0.1.0")::get).blockedSdkVersions,
            CollectionControl.fromEnv(mapOf("SIGNALDOCK_BLOCKED_SDK_VERSIONS" to "android:0.1.0")::get).blockedSdkVersions,
        )
    }

    @Test
    fun `matching of version and prefix is exact and case-sensitive`() {
        statuses("ios:0.1.0-RC", event(1, "a", "0.1.0-rc", "ios"), event(2, "a", "0.1.0-RC", "ios"))
        assertEquals(listOf("accepted", "COLLECTION_BLOCKED"), lastStatuses)
    }

    @Test
    fun `a malformed or unknown platform prefix fails the start`() {
        for (bad in listOf("iOS:0.1.0", "IOS:0.1.0", "Android:0.1.0", "ANDROID:0.1.0", "windows:0.1.0", ":0.1.0", "ios:", "android:", "ios:0.1.0,iOS:0.2.0")) {
            val error = assertFailsWith<IllegalArgumentException>(bad) {
                CollectionControl.fromEnv(mapOf("SIGNALDOCK_BLOCKED_SDK_VERSIONS" to bad)::get)
            }
            assertEquals(true, error.message?.contains("SIGNALDOCK_BLOCKED_SDK_VERSIONS"), bad) // the message names the variable
        }
        // Blank and empty values stay valid (nothing blocked).
        CollectionControl.fromEnv(mapOf("SIGNALDOCK_BLOCKED_SDK_VERSIONS" to " , ")::get)
        CollectionControl.fromEnv(mapOf("SIGNALDOCK_BLOCKED_SDK_VERSIONS" to "")::get)
    }

    @Test
    fun `shed answers 503 with the configured Retry-After and stores nothing`() = testApplication {
        useControl(CollectionControl.fromEnv(mapOf("SIGNALDOCK_INGEST_SHED" to "true", "SIGNALDOCK_INGEST_SHED_RETRY_AFTER_SECONDS" to "120")::get))
        val response = client.ingest(batch(event(1, "a")))
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("120", response.headers[HttpHeaders.RetryAfter])
        assertEquals(0, stored())
        // The report route sheds too (valid body, ADR-0036 contract).
        val report = client.post("/v1/client-reports") {
            header("SignalDock-Contract-Version", "1")
            header(HttpHeaders.Authorization, "Bearer collection-key")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"sdkVersion":"0.2.0","discardedEvents":[{"reason":"queue_full","count":3}]}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, report.status)
        assertEquals("120", report.headers[HttpHeaders.RetryAfter])
        assertEquals(0, runBlocking { database.transaction { query { fetchCount(CLIENT_REPORTS) } } })
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
