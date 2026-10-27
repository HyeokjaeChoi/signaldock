package local.signaldock.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import local.signaldock.persistence.Database
import local.signaldock.server.jooq.tables.ClientReports.CLIENT_REPORTS
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.openapitools.client.models.BatchAckResponse
import org.openapitools.client.models.ClientReportCounts
import org.openapitools.client.models.DiscardReason
import org.openapitools.client.models.ErrorResponse
import org.openapitools.client.models.EventNameCounts
import org.openapitools.client.models.EventPlanStatusCounts
import org.openapitools.client.models.EventReceiptDetail
import org.openapitools.client.models.EventReceiptPage
import org.openapitools.client.models.EventTimeCounts
import org.openapitools.client.models.ItemAck
import org.openapitools.client.models.SdkPlatform
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.ZoneId
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR-0046 on real PostgreSQL: ingest default and per-item rejection, client report body, the stored
 * value in query responses, and the `sdkPlatform` filter on the five endpoints.
 */
class SdkPlatformTest {
    private lateinit var database: Database
    private val json = Json
    private val installation = "47c4d89f-352a-41da-9b31-529ea756515a"

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

    /** `platform` is raw JSON for the `sdkPlatform` member, or null to leave the member out. */
    private fun event(n: Int, platform: String?, name: String = "product_viewed", version: String = "0.4.0") =
        """{"eventId":"${id(n)}","name":"$name","occurredAt":"2026-10-12T00:00:00.123Z","properties":{},"sdkVersion":"$version",""" +
            (platform?.let { """"sdkPlatform":$it,""" } ?: "") + """"contractVersion":"1"}"""

    private fun batch(vararg events: String) = """{"installationId":"$installation","events":[${events.joinToString(",")}]}"""

    private fun ApplicationTestBuilder.useModules() = application {
        ingestionModule(database, "collection-key")
        queryModule(database, "query-key", ZoneId.of("Asia/Seoul"))
    }

    private suspend fun HttpClient.ingest(body: String): HttpResponse = post("/v1/events:batch") {
        header("SignalDock-Contract-Version", "1")
        header(HttpHeaders.Authorization, "Bearer collection-key")
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.report(body: String): HttpResponse = post("/v1/client-reports") {
        header("SignalDock-Contract-Version", "1")
        header(HttpHeaders.Authorization, "Bearer collection-key")
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.query(path: String, params: Map<String, String> = emptyMap()): HttpResponse = get(path) {
        header("SignalDock-Contract-Version", "1")
        header(HttpHeaders.Authorization, "Bearer query-key")
        params.forEach { (key, value) -> parameter(key, value) }
    }

    private suspend fun HttpResponse.errorCode() = json.decodeFromString<ErrorResponse>(bodyAsText()).code

    private fun acks(text: String) = json.decodeFromString<BatchAckResponse>(text).results

    private fun storedPlatforms(): Map<UUID, String> = runBlocking {
        database.transaction { query { select(EVENTS.EVENT_ID, EVENTS.SDK_PLATFORM).from(EVENTS).fetch().associate { it.value1()!! to it.value2()!! } } }
    }

    private fun reportRows(): List<Triple<String, String, String>> = runBlocking {
        database.transaction {
            query {
                select(CLIENT_REPORTS.SDK_PLATFORM, CLIENT_REPORTS.SDK_VERSION, CLIENT_REPORTS.REASON).from(CLIENT_REPORTS)
                    .orderBy(CLIENT_REPORTS.ID).fetch { Triple(it.value1()!!, it.value2()!!, it.value3()!!) }
            }
        }
    }

    // ---- Ingest (D3) ----

    @Test
    fun `a missing field is stored as android and both values are stored as sent`() = testApplication {
        useModules()
        val results = acks(client.ingest(batch(event(1, null), event(2, "\"android\""), event(3, "\"ios\""))).bodyAsText())
        assertEquals(listOf(ItemAck.Status.ACCEPTED, ItemAck.Status.ACCEPTED, ItemAck.Status.ACCEPTED), results.map { it.status })
        assertEquals(mapOf(id(1) to "android", id(2) to "android", id(3) to "ios"), storedPlatforms())
    }

    @Test
    fun `an unknown or mistyped value is a per-item rejection and valid siblings are stored`() = testApplication {
        useModules()
        val bad = listOf("\"iOS\"", "\"IOS\"", "\"Android\"", "\"\"", "\"unknown\"", "1", "true", "null", "[]", "{}")
        val events = listOf(event(1, "\"ios\"")) + bad.mapIndexed { i, raw -> event(10 + i, raw) } + event(2, null)
        val response = client.ingest(batch(*events.toTypedArray()))
        assertEquals(HttpStatusCode.OK, response.status) // never a request-level 400 (ADR-0027)
        val results = acks(response.bodyAsText())
        assertEquals(ItemAck.Status.ACCEPTED, results.first().status)
        assertEquals(ItemAck.Status.ACCEPTED, results.last().status)
        results.drop(1).dropLast(1).forEachIndexed { i, ack ->
            assertEquals(ItemAck.Status.REJECTED, ack.status, bad[i])
            assertEquals("INVALID_EVENT", ack.code, bad[i])
            assertEquals(id(10 + i).toString(), ack.eventId, bad[i])
        }
        assertEquals(mapOf(id(1) to "ios", id(2) to "android"), storedPlatforms())
    }

    @Test
    fun `a resend is a duplicate only with the same platform`() = testApplication {
        useModules()
        client.ingest(batch(event(1, "\"ios\"")))
        assertEquals(ItemAck.Status.DUPLICATE, acks(client.ingest(batch(event(1, "\"ios\""))).bodyAsText()).single().status)
        val conflict = acks(client.ingest(batch(event(1, "\"android\""))).bodyAsText()).single()
        assertEquals(ItemAck.Status.REJECTED, conflict.status)
        assertEquals("CONFLICT", conflict.code)
        // An Android 0.3.0 resend that omits the field equals an explicit android one.
        client.ingest(batch(event(2, null)))
        assertEquals(ItemAck.Status.DUPLICATE, acks(client.ingest(batch(event(2, "\"android\""))).bodyAsText()).single().status)
        assertEquals(mapOf(id(1) to "ios", id(2) to "android"), storedPlatforms())
    }

    // ---- Client report body (ADR-0036, ADR-0046) ----

    private fun report(platform: String?, version: String = "\"0.4.0\"") =
        """{"sdkVersion":$version,""" + (platform?.let { """"sdkPlatform":$it,""" } ?: "") + """"discardedEvents":[{"reason":"queue_full","count":3}]}"""

    @Test
    fun `a client report stores the platform and a missing field is android`() = testApplication {
        useModules()
        assertEquals(HttpStatusCode.NoContent, client.report(report(null, "\"0.1.0\"")).status)
        assertEquals(HttpStatusCode.NoContent, client.report(report("\"android\"")).status)
        assertEquals(HttpStatusCode.NoContent, client.report(report("\"ios\"")).status)
        assertEquals(
            listOf(Triple("android", "0.1.0", "queue_full"), Triple("android", "0.4.0", "queue_full"), Triple("ios", "0.4.0", "queue_full")),
            reportRows(),
        )
    }

    @Test
    fun `an invalid platform in a client report gets the response of an invalid sdkVersion`() = testApplication {
        useModules()
        // A bad sdkVersion value (empty) is INVALID_REPORT; a value that is not a string is MALFORMED_REQUEST.
        val badVersion = client.report(report(null, "\"\""))
        assertEquals(HttpStatusCode.BadRequest, badVersion.status)
        assertEquals("INVALID_REPORT", badVersion.errorCode())
        val badVersionType = client.report(report(null, "1"))
        assertEquals("MALFORMED_REQUEST", badVersionType.errorCode())
        for (bad in listOf("\"iOS\"", "\"Android\"", "\"\"", "\"unknown\"")) {
            val response = client.report(report(bad))
            assertEquals(HttpStatusCode.BadRequest, response.status, bad)
            assertEquals("INVALID_REPORT", response.errorCode(), bad)
        }
        for (bad in listOf("1", "true", "null", "[]", "{}")) {
            val response = client.report(report(bad))
            assertEquals(HttpStatusCode.BadRequest, response.status, bad)
            assertEquals("MALFORMED_REQUEST", response.errorCode(), bad)
        }
        val twice = client.report("""{"sdkVersion":"v","sdkPlatform":"ios","sdkPlatform":"ios","discardedEvents":[{"reason":"queue_full","count":1}]}""")
        assertEquals("MALFORMED_REQUEST", twice.errorCode())
        assertEquals(emptyList(), reportRows()) // nothing is stored by a rejected report
    }

    // ---- Query responses and filters (D5) ----

    private suspend fun seedEvents(client: HttpClient) {
        client.ingest(batch(
            event(1, "\"android\"", name = "cart_add"),
            event(2, "\"ios\"", name = "cart_add"),
            event(3, "\"ios\"", name = "cart_view"),
            event(4, null, name = "cart_view", version = "0.3.0"),
        ))
    }

    private fun window(): Map<String, String> {
        val now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        return mapOf("from" to now.minusSeconds(3600).toString(), "to" to now.plusSeconds(3600).toString())
    }

    @Test
    fun `list and detail return the stored platform and the list filter works`() = testApplication {
        useModules()
        seedEvents(client)
        fun list(params: Map<String, String> = emptyMap()) =
            runBlocking { json.decodeFromString<EventReceiptPage>(client.query("/v1/events", window() + params + ("sort" to "receivedAt_asc")).bodyAsText()).items }
        assertEquals(
            listOf(SdkPlatform.ANDROID, SdkPlatform.IOS, SdkPlatform.IOS, SdkPlatform.ANDROID),
            list().map { it.sdkPlatform },
        )
        assertEquals(listOf(id(1), id(4)).map(UUID::toString), list(mapOf("sdkPlatform" to "android")).map { it.eventId })
        assertEquals(listOf(id(2), id(3)).map(UUID::toString), list(mapOf("sdkPlatform" to "ios")).map { it.eventId })
        assertEquals(listOf(id(2)).map(UUID::toString), list(mapOf("sdkPlatform" to "ios", "name" to "cart_add")).map { it.eventId })
        assertEquals(emptyList(), list(mapOf("sdkPlatform" to "ios", "installationId" to "11111111-1111-4111-8111-111111111111")))
        val detail = json.decodeFromString<EventReceiptDetail>(client.query("/v1/events/${id(3)}").bodyAsText())
        assertEquals(SdkPlatform.IOS, detail.sdkPlatform)
        assertEquals(SdkPlatform.ANDROID, json.decodeFromString<EventReceiptDetail>(client.query("/v1/events/${id(4)}").bodyAsText()).sdkPlatform)
    }

    @Test
    fun `the filter applies to the three event count endpoints`() = testApplication {
        useModules()
        seedEvents(client)
        suspend fun byName(p: String?) = json.decodeFromString<EventNameCounts>(
            client.query("/v1/event-counts/by-name", window() + listOfNotNull(p?.let { "sdkPlatform" to it })).bodyAsText(),
        ).items.associate { it.name to it.count }
        assertEquals(mapOf("cart_add" to 2L, "cart_view" to 2L), byName(null))
        assertEquals(mapOf("cart_add" to 1L, "cart_view" to 1L), byName("android"))
        assertEquals(mapOf("cart_add" to 1L, "cart_view" to 1L), byName("ios"))

        suspend fun byTime(p: String) = json.decodeFromString<EventTimeCounts>(
            client.query("/v1/event-counts/by-time", window() + ("bucket" to "day") + ("sdkPlatform" to p)).bodyAsText(),
        ).items.sumOf { it.count }
        assertEquals(2L, byTime("android"))
        assertEquals(2L, byTime("ios"))

        suspend fun byPlan(p: String?) = json.decodeFromString<EventPlanStatusCounts>(
            client.query("/v1/event-counts/by-plan-status", window() + listOfNotNull(p?.let { "sdkPlatform" to it })).bodyAsText(),
        ).notChecked
        assertEquals(4L, byPlan(null))
        assertEquals(2L, byPlan("ios"))
        assertEquals(2L, byPlan("android"))
    }

    @Test
    fun `client report counts group by platform and version and the filter applies`() = testApplication {
        useModules()
        client.report(report(null, "\"0.1.0\""))
        client.report(report("\"android\""))
        client.report(report("\"ios\""))
        client.report(report("\"ios\""))
        client.report(report("\"ios\"", "\"0.1.0\"")) // same version as the first, other platform: its own row
        fun counts(p: String?) = runBlocking {
            json.decodeFromString<ClientReportCounts>(client.query("/v1/client-report-counts", window() + listOfNotNull(p?.let { "sdkPlatform" to it })).bodyAsText())
                .items.map { listOf(it.sdkPlatform, it.sdkVersion, it.reason, it.count) }
        }
        val ios040 = listOf(SdkPlatform.IOS, "0.4.0", DiscardReason.QUEUE_FULL, 6L)
        val android010 = listOf(SdkPlatform.ANDROID, "0.1.0", DiscardReason.QUEUE_FULL, 3L)
        val android040 = listOf(SdkPlatform.ANDROID, "0.4.0", DiscardReason.QUEUE_FULL, 3L)
        val ios010 = listOf(SdkPlatform.IOS, "0.1.0", DiscardReason.QUEUE_FULL, 3L)
        // Count descending, then platform, version, reason ascending (ADR-0046 decision 6).
        assertEquals(listOf(ios040, android010, android040, ios010), counts(null))
        assertEquals(listOf(android010, android040), counts("android"))
        assertEquals(listOf(ios040, ios010), counts("ios"))
    }

    @Test
    fun `an invalid platform parameter is INVALID_PARAMETER on the five endpoints and not accepted on the detail route`() = testApplication {
        useModules()
        val paths = listOf("/v1/events", "/v1/event-counts/by-name", "/v1/event-counts/by-time", "/v1/event-counts/by-plan-status", "/v1/client-report-counts")
        for (path in paths) {
            for (bad in listOf("iOS", "IOS", "Android", "", "unknown")) {
                val response = client.query(path, window() + ("sdkPlatform" to bad))
                assertEquals(HttpStatusCode.BadRequest, response.status, "$path $bad")
                assertEquals("INVALID_PARAMETER", response.errorCode(), "$path $bad")
            }
            assertEquals(HttpStatusCode.OK, client.query(path, window() + ("sdkPlatform" to "ios")).status, path)
        }
        assertEquals("INVALID_PARAMETER", client.query("/v1/events/${id(1)}", mapOf("sdkPlatform" to "ios")).errorCode())
    }

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
