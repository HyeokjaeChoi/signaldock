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
import org.openapitools.client.models.ClientReportCounts
import org.openapitools.client.models.DiscardReason
import org.openapitools.client.models.ErrorResponse
import org.openapitools.client.models.SdkPlatform
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SDK client reports (ADR-0036): `POST /v1/client-reports` and `GET /v1/client-report-counts`, on real PostgreSQL. */
class ClientReportTest {
    private lateinit var database: Database
    private val json = Json
    private val valid = """{"sdkVersion":"0.1.0","discardedEvents":[{"reason":"queue_full","count":3}]}"""

    @BeforeTest
    fun startDatabase() {
        if (!postgres.isRunning) postgres.start()
        database = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        database.migrate()
        runBlocking { database.transaction { query { deleteFrom(CLIENT_REPORTS).execute(); deleteFrom(EVENTS).execute() } } }
    }

    @AfterTest
    fun stopDatabase() {
        database.close()
    }

    private fun ApplicationTestBuilder.useModules() {
        application {
            ingestionModule(database, "collection-key")
            queryModule(database, "query-key", ZoneId.of("Asia/Seoul"))
        }
    }

    private suspend fun HttpClient.report(
        body: Any = valid,
        version: String? = "1",
        auth: String? = "Bearer collection-key",
        contentType: String? = "application/json",
        encoding: String? = null,
    ): HttpResponse = post("/v1/client-reports") {
        version?.let { header("SignalDock-Contract-Version", it) }
        auth?.let { header(HttpHeaders.Authorization, it) }
        contentType?.let { header(HttpHeaders.ContentType, it) }
        encoding?.let { header(HttpHeaders.ContentEncoding, it) }
        setBody(body)
    }

    private suspend fun HttpClient.counts(
        params: Map<String, String> = period,
        version: String? = "1",
        auth: String? = "Bearer query-key",
    ): HttpResponse = get("/v1/client-report-counts") {
        version?.let { header("SignalDock-Contract-Version", it) }
        auth?.let { header(HttpHeaders.Authorization, it) }
        params.forEach { (key, value) -> parameter(key, value) }
    }

    /** 2026-10-12 in Asia/Seoul: [2026-10-11T15:00Z, 2026-10-12T15:00Z). */
    private val period = mapOf("from" to "2026-10-12T00:00:00+09:00", "to" to "2026-10-13T00:00:00+09:00")

    private suspend fun HttpResponse.errorCode(): String = json.decodeFromString<ErrorResponse>(bodyAsText()).code

    private fun rows(): List<Triple<String, String, Int>> = runBlocking {
        database.transaction {
            query {
                selectFrom(CLIENT_REPORTS).orderBy(CLIENT_REPORTS.ID).fetch { Triple(it.sdkVersion!!, it.reason!!, it.count!!) }
            }
        }
    }

    private fun insert(receivedAt: String, sdkVersion: String, reason: String, count: Int) = runBlocking {
        database.transaction {
            query {
                execute(
                    "INSERT INTO client_reports (received_at, sdk_platform, sdk_version, reason, count) VALUES (?::timestamptz, 'android', ?, ?, ?)",
                    receivedAt, sdkVersion, reason, count,
                )
            }
        }
        Unit
    }

    @Test
    fun `stores one row per entry and answers 204 without a body`() = testApplication {
        useModules()
        val body = """{"sdkVersion":"0.1.0","discardedEvents":[{"reason":"queue_full","count":3},{"reason":"storage_error","count":1}]}"""
        val response = client.report(body)
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText())
        assertEquals(listOf(Triple("0.1.0", "queue_full", 3), Triple("0.1.0", "storage_error", 1)), rows())
    }

    @Test
    fun `accepts each of the five reasons`() = testApplication {
        useModules()
        for (reason in listOf("queue_full", "storage_error", "invalid_event", "event_too_large", "server_rejected")) {
            assertEquals(HttpStatusCode.NoContent, client.report("""{"sdkVersion":"s","discardedEvents":[{"reason":"$reason","count":1}]}""").status, reason)
        }
        assertEquals(5, rows().map { it.second }.toSet().size)
    }

    @Test
    fun `a resend counts twice because a report is not idempotent`() = testApplication {
        useModules()
        client.report()
        client.report()
        assertEquals(2, rows().size)
    }

    @Test
    fun `server accepts the request body the SDK builds`() = testApplication {
        useModules()
        val file = File("../../fixtures/roundtrip/client-report-request.json")
        assertEquals(HttpStatusCode.NoContent, client.report(file.readBytes()).status)
        assertEquals(
            listOf("queue_full" to 3, "storage_error" to 1, "invalid_event" to 2, "event_too_large" to 4, "server_rejected" to 1_000_000),
            rows().map { it.second to it.third },
        )
    }

    @Test
    fun `server accepts the request body the iOS SDK builds and stores the platform ios`() = testApplication {
        useModules()
        val file = File("../../fixtures/roundtrip/client-report-request-ios.json")
        assertEquals(HttpStatusCode.NoContent, client.report(file.readBytes()).status)
        assertEquals(
            listOf("queue_full" to 3, "storage_error" to 1, "invalid_event" to 2, "event_too_large" to 4, "server_rejected" to 1_000_000),
            rows().map { it.second to it.third },
        )
        val platforms = runBlocking {
            database.transaction { query { selectFrom(CLIENT_REPORTS).fetch { it.sdkPlatform!! }.toSet() } }
        }
        assertEquals(setOf("ios"), platforms)
    }

    @Test
    fun `a body of exactly 4096 bytes is accepted and one more byte is too large`() = testApplication {
        useModules()
        assertEquals(HttpStatusCode.NoContent, client.report(valid + " ".repeat(4096 - valid.length)).status)
        val large = client.report(valid + " ".repeat(4097 - valid.length))
        assertEquals(HttpStatusCode.PayloadTooLarge, large.status)
        assertEquals("PAYLOAD_TOO_LARGE", large.errorCode())
        assertEquals(1, rows().size)
    }

    @Test
    fun `unknown and duplicate fields are rejected and nothing is stored`() = testApplication {
        useModules()
        val cases = mapOf(
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":1}],"x":1}""" to "UNKNOWN_FIELD",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":1,"x":1}]}""" to "UNKNOWN_FIELD",
            """{"sdkVersion":"s","sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":1}]}""" to "MALFORMED_REQUEST",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","reason":"queue_full","count":1}]}""" to "MALFORMED_REQUEST",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":1,"count":1}]}""" to "MALFORMED_REQUEST",
        )
        for ((body, code) in cases) {
            val response = client.report(body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals(code, response.errorCode(), body)
        }
        assertEquals(0, rows().size)
    }

    @Test
    fun `malformed bodies are rejected`() = testApplication {
        useModules()
        val bodies = listOf(
            "", "{", "[]", "null", """{"sdkVersion":"s"}""", """{"discardedEvents":[]}""",
            """{"sdkVersion":1,"discardedEvents":[{"reason":"queue_full","count":1}]}""",
            """{"sdkVersion":"s","discardedEvents":{}}""",
            """{"sdkVersion":"s","discardedEvents":[1]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full"}]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":1,"count":1}]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":"3"}]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":null}]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":true}]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":[1]}]}""",
        )
        for (body in bodies) {
            val response = client.report(body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals("MALFORMED_REQUEST", response.errorCode(), body)
        }
        val badUtf8 = client.report(byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte()))
        assertEquals("MALFORMED_REQUEST", badUtf8.errorCode())
        assertEquals(0, rows().size)
    }

    @Test
    fun `report rules are enforced with INVALID_REPORT`() = testApplication {
        useModules()
        val entry = """{"reason":"queue_full","count":1}"""
        val bodies = listOf(
            """{"sdkVersion":"s","discardedEvents":[]}""",
            """{"sdkVersion":"","discardedEvents":[$entry]}""",
            """{"sdkVersion":"${"a".repeat(65)}","discardedEvents":[$entry]}""",
            """{"sdkVersion":"a\u0000","discardedEvents":[$entry]}""",
            """{"sdkVersion":"a\ud800","discardedEvents":[$entry]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"nope","count":1}]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"QUEUE_FULL","count":1}]}""",
            """{"sdkVersion":"s","discardedEvents":[$entry,$entry]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":0}]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":1000001}]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":-1}]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":3.5}]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":1e3}]}""",
            """{"sdkVersion":"s","discardedEvents":[{"reason":"queue_full","count":99999999999999999999}]}""",
            """{"sdkVersion":"s","discardedEvents":[${(1..6).joinToString(",") { """{"reason":"queue_full","count":1}""" }}]}""",
        )
        for (body in bodies) {
            val response = client.report(body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals("INVALID_REPORT", response.errorCode(), body)
        }
        assertEquals(0, rows().size)
    }

    @Test
    fun `headers and keys are checked in order`() = testApplication {
        useModules()
        assertEquals("CONTRACT_HEADER_MISSING", client.report(version = null).errorCode())
        assertEquals("CONTRACT_VERSION_UNSUPPORTED", client.report(version = "2").errorCode())
        for (auth in listOf(null, "Bearer wrong", "Bearer query-key", "collection-key", "Basic Y29sbGVjdGlvbi1rZXk=")) {
            val response = client.report(auth = auth)
            assertEquals(HttpStatusCode.Unauthorized, response.status, "$auth")
            assertEquals("COLLECTION_KEY_INVALID", response.errorCode(), "$auth")
        }
        for (type in listOf("text/plain", null)) {
            val response = client.report(contentType = type)
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, "$type")
            assertEquals("CONTENT_TYPE_UNSUPPORTED", response.errorCode(), "$type")
        }
        val encoded = client.report(encoding = "gzip")
        assertEquals(HttpStatusCode.UnsupportedMediaType, encoded.status)
        assertEquals("CONTENT_ENCODING_UNSUPPORTED", encoded.errorCode())
        assertEquals(HttpStatusCode.NoContent, client.report(encoding = "identity").status)
        assertEquals(1, rows().size)
    }

    @Test
    fun `an event batch still works next to client reports`() = testApplication {
        useModules()
        val batch = """{"installationId":"47c4d89f-352a-41da-9b31-529ea756515a","events":[{"eventId":"30d66f95-2435-4e56-b52a-70b1c8bba4ef","name":"product_viewed","occurredAt":"2026-10-09T21:30:00.123Z","properties":{},"sdkVersion":"0.1.0","contractVersion":"1"}]}"""
        val response = client.post("/v1/events:batch") {
            header("SignalDock-Contract-Version", "1")
            header(HttpHeaders.Authorization, "Bearer collection-key")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(batch)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("accepted"))
        assertEquals(HttpStatusCode.NoContent, client.report().status)
    }

    @Test
    fun `counts need the contract header and the query key only`() = testApplication {
        useModules()
        assertEquals("CONTRACT_HEADER_MISSING", client.counts(version = null).errorCode())
        assertEquals("CONTRACT_VERSION_UNSUPPORTED", client.counts(version = "2").errorCode())
        for (auth in listOf(null, "Bearer wrong", "Bearer collection-key", "query-key")) {
            val response = client.counts(auth = auth)
            assertEquals(HttpStatusCode.Unauthorized, response.status, "$auth")
            assertEquals("QUERY_KEY_INVALID", response.errorCode(), "$auth")
        }
        assertEquals(HttpStatusCode.OK, client.counts().status)
    }

    @Test
    fun `counts reject bad parameters and long periods`() = testApplication {
        useModules()
        val bad = listOf(
            period + ("limit" to "5"),
            period + ("sdkVersion" to "0.1.0"),
            mapOf("from" to period.getValue("from")),
            mapOf("to" to period.getValue("to")),
            mapOf("from" to "2026-10-12", "to" to "2026-10-13"),
            mapOf("from" to period.getValue("to"), "to" to period.getValue("from")),
        )
        for (params in bad) {
            val response = client.counts(params)
            assertEquals(HttpStatusCode.BadRequest, response.status, "$params")
            assertEquals("INVALID_PARAMETER", response.errorCode(), "$params")
        }
        val repeated = client.get("/v1/client-report-counts?from=2026-10-12T00:00:00Z&to=2026-10-13T00:00:00Z&to=2026-10-14T00:00:00Z") {
            header("SignalDock-Contract-Version", "1")
            header(HttpHeaders.Authorization, "Bearer query-key")
        }
        assertEquals("INVALID_PARAMETER", repeated.errorCode())
        val long = client.counts(mapOf("from" to "2026-09-12T00:00:00+09:00", "to" to "2026-10-13T00:00:00+09:00"))
        assertEquals(HttpStatusCode.BadRequest, long.status)
        assertEquals("PERIOD_TOO_LONG", long.errorCode())
    }

    @Test
    fun `counts sum by version and reason, order, and filter the period`() = testApplication {
        useModules()
        insert("2026-10-11T15:00:00.000Z", "0.1.0", "queue_full", 2) // exactly from: in
        insert("2026-10-12T01:00:00.000Z", "0.1.0", "queue_full", 3)
        insert("2026-10-12T02:00:00.000Z", "0.1.0", "storage_error", 5)
        insert("2026-10-12T03:00:00.000Z", "0.2.0", "queue_full", 9)
        insert("2026-10-12T04:00:00.000Z", "0.2.0", "invalid_event", 5)
        insert("2026-10-11T14:59:59.999Z", "0.1.0", "queue_full", 100) // before from
        insert("2026-10-12T15:00:00.000Z", "0.1.0", "queue_full", 100) // exactly to: out
        val response = client.counts()
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]?.startsWith("application/json") == true)
        val body = json.decodeFromString<ClientReportCounts>(response.bodyAsText())
        assertEquals(false, body.truncated)
        assertTrue(body.items.all { it.sdkPlatform == SdkPlatform.ANDROID })
        // Largest first; the tie at 5 goes by SDK version, then reason.
        assertEquals(
            listOf(
                Triple("0.2.0", DiscardReason.QUEUE_FULL, 9L),
                Triple("0.1.0", DiscardReason.QUEUE_FULL, 5L),
                Triple("0.1.0", DiscardReason.STORAGE_ERROR, 5L),
                Triple("0.2.0", DiscardReason.INVALID_EVENT, 5L),
            ),
            body.items.map { Triple(it.sdkVersion, it.reason, it.count) },
        )
    }

    @Test
    fun `a report posted through the route shows in the counts`() = testApplication {
        useModules()
        client.report()
        val now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val params = mapOf("from" to now.minusSeconds(3600).toString(), "to" to now.plusSeconds(3600).toString())
        val body = json.decodeFromString<ClientReportCounts>(client.counts(params).bodyAsText())
        assertEquals(listOf(Triple("0.1.0", DiscardReason.QUEUE_FULL, 3L)), body.items.map { Triple(it.sdkVersion, it.reason, it.count) })
    }

    @Test
    fun `counts return at most 100 rows and say when more exist`() = testApplication {
        useModules()
        runBlocking {
            database.transaction {
                query {
                    execute(
                        """INSERT INTO client_reports (received_at, sdk_platform, sdk_version, reason, count)
                           SELECT timestamptz '2026-10-12 00:00:00+00', 'android', 'v' || lpad(g::text, 3, '0'), 'queue_full', 1 FROM generate_series(1, 101) g""",
                    )
                }
            }
        }
        val body = json.decodeFromString<ClientReportCounts>(client.counts().bodyAsText())
        assertEquals(100, body.items.size)
        assertEquals(true, body.truncated)
        assertEquals("v001", body.items.first().sdkVersion)
        assertEquals("v100", body.items.last().sdkVersion)
    }

    @Test
    fun `counts of an empty period are an empty list`() = testApplication {
        useModules()
        val body = json.decodeFromString<ClientReportCounts>(client.counts().bodyAsText())
        assertEquals(emptyList(), body.items)
        assertEquals(false, body.truncated)
    }

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
