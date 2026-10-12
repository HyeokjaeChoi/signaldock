package local.signaldock.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.signaldock.persistence.Database
import local.signaldock.persistence.DatabaseLimits
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.jooq.JSONB
import org.openapitools.client.models.ErrorResponse
import org.openapitools.client.models.EventNameCounts
import org.openapitools.client.models.EventReceiptDetail
import org.openapitools.client.models.EventReceiptPage
import org.openapitools.client.models.EventTimeCounts
import org.openapitools.client.models.PropertyValue
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryRoutesTest {
    private lateinit var database: Database
    private val seoul = ZoneId.of("Asia/Seoul")
    private val installation = UUID.fromString("47c4d89f-352a-41da-9b31-529ea756515a")
    private val json = Json

    @BeforeTest
    fun startDatabase() {
        if (!postgres.isRunning) postgres.start()
        database = Database(postgres.jdbcUrl, postgres.username, postgres.password)
        database.migrate()
        runBlocking { database.transaction { query { deleteFrom(EVENTS).execute() } } }
    }

    @AfterTest
    fun stopDatabase() {
        database.close()
    }

    private fun id(n: Int) = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

    private fun insert(n: Int, receivedAt: String, name: String = "product_viewed", properties: String = "{}") = runBlocking {
        database.transaction {
            query {
                insertInto(EVENTS)
                    .set(EVENTS.EVENT_ID, id(n))
                    .set(EVENTS.INSTALLATION_ID, installation)
                    .set(EVENTS.NAME, name)
                    .set(EVENTS.OCCURRED_AT, Instant.parse("2026-10-12T00:00:00.250Z").atOffset(ZoneOffset.UTC))
                    .set(EVENTS.SDK_VERSION, "0.1.0")
                    .set(EVENTS.PROPERTIES, JSONB.valueOf(properties))
                    .set(EVENTS.RECEIVED_AT, Instant.parse(receivedAt).atOffset(ZoneOffset.UTC))
                    .execute()
            }
        }
        Unit
    }

    private suspend fun HttpClient.query(
        path: String,
        params: Map<String, String> = emptyMap(),
        version: String? = "1",
        auth: String? = "Bearer query-key",
    ): HttpResponse = get(path) {
        version?.let { header("SignalDock-Contract-Version", it) }
        auth?.let { header(HttpHeaders.Authorization, it) }
        params.forEach { (key, value) -> parameter(key, value) }
    }

    private val october12 = mapOf("from" to "2026-10-12T00:00:00+09:00", "to" to "2026-10-13T00:00:00+09:00")

    private fun io.ktor.server.testing.ApplicationTestBuilder.useQueryModule() {
        application { queryModule(database, "query-key", seoul) }
    }

    private suspend fun HttpResponse.errorCode(): String = json.decodeFromString<ErrorResponse>(bodyAsText()).code

    private val endpoints = listOf("/v1/events", "/v1/events/${id(1)}", "/v1/event-counts/by-name", "/v1/event-counts/by-time")

    @Test
    fun `every endpoint requires the contract version header`() = testApplication {
        useQueryModule()
        insert(1, "2026-10-12T01:00:00Z")
        for (path in endpoints) {
            val missing = client.query(path, october12, version = null)
            assertEquals(HttpStatusCode.BadRequest, missing.status, path)
            assertEquals("CONTRACT_HEADER_MISSING", missing.errorCode(), path)
            val unsupported = client.query(path, october12, version = "2")
            assertEquals(HttpStatusCode.BadRequest, unsupported.status, path)
            assertEquals("CONTRACT_VERSION_UNSUPPORTED", unsupported.errorCode(), path)
        }
    }

    @Test
    fun `every endpoint requires the query key and rejects the collection key`() = testApplication {
        useQueryModule()
        insert(1, "2026-10-12T01:00:00Z")
        for (path in endpoints) {
            assertEquals(HttpStatusCode.OK, client.query(path, october12.takeIf { "event-counts" in path || path == "/v1/events" }.orEmpty()).status, path)
            // No Authorization, wrong, collection key, missing scheme, wrong scheme: all the same 401 (Q130, never 403).
            for (auth in listOf(null, "Bearer wrong", "Bearer collection-key", "query-key", "Basic cXVlcnkta2V5")) {
                val response = client.query(path, october12, auth = auth)
                assertEquals(HttpStatusCode.Unauthorized, response.status, "$path $auth")
                assertTrue(response.headers[HttpHeaders.ContentType]?.startsWith("application/json") == true)
                assertEquals("QUERY_KEY_INVALID", response.errorCode(), "$path $auth")
            }
        }
    }

    @Test
    fun `details reject any query parameter`() = testApplication {
        useQueryModule()
        insert(1, "2026-10-12T01:00:00Z")
        for (query in listOf("?unknown=1", "?from=2026-10-12T00:00:00Z", "?a=1&a=2")) {
            val response = client.get("/v1/events/${id(1)}$query") {
                header("SignalDock-Contract-Version", "1")
                header(HttpHeaders.Authorization, "Bearer query-key")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            assertEquals("INVALID_PARAMETER", response.errorCode(), query)
        }
    }

    @Test
    fun `bad parameters are rejected with INVALID_PARAMETER`() = testApplication {
        useQueryModule()
        val bad = listOf(
            "/v1/events" to october12 + ("limit" to "101"),
            "/v1/events" to october12 + ("limit" to "0"),
            "/v1/events" to october12 + ("sort" to "name"),
            "/v1/events" to october12 + ("unknown" to "1"),
            "/v1/events" to october12 + ("eventId" to "not-a-uuid"),
            "/v1/events" to mapOf("from" to "2026-10-12T00:00:00+09:00"),
            "/v1/events" to mapOf("from" to "2026-10-12", "to" to "2026-10-13"),
            "/v1/events" to mapOf("from" to "2026-10-13T00:00:00Z", "to" to "2026-10-12T00:00:00Z"),
            "/v1/event-counts/by-name" to october12 + ("limit" to "5"),
            "/v1/event-counts/by-time" to october12 + ("bucket" to "week"),
            "/v1/events/not-a-uuid" to october12,
        )
        for ((path, params) in bad) {
            val response = client.query(path, params)
            assertEquals(HttpStatusCode.BadRequest, response.status, "$path $params")
            assertEquals("INVALID_PARAMETER", response.errorCode(), "$path $params")
        }
        // A repeated parameter is not silently collapsed.
        val repeated = client.get("/v1/events?from=2026-10-12T00:00:00Z&to=2026-10-13T00:00:00Z&limit=1&limit=2") {
            header("SignalDock-Contract-Version", "1")
            header(HttpHeaders.Authorization, "Bearer query-key")
        }
        assertEquals(HttpStatusCode.BadRequest, repeated.status)
        assertEquals("INVALID_CURSOR", client.query("/v1/events", october12 + ("cursor" to "AAAA")).errorCode())
    }

    @Test
    fun `a period over 30 calendar dates is rejected, not truncated`() = testApplication {
        useQueryModule()
        val period = mapOf("from" to "2026-09-12T00:00:00+09:00", "to" to "2026-10-13T00:00:00+09:00")
        for (path in listOf("/v1/events", "/v1/event-counts/by-name", "/v1/event-counts/by-time")) {
            val response = client.query(path, period)
            assertEquals(HttpStatusCode.BadRequest, response.status, path)
            assertEquals("PERIOD_TOO_LONG", response.errorCode(), path)
        }
    }

    @Test
    fun `list pages newest first with cursor and matches the contract schema`() = testApplication {
        useQueryModule()
        insert(1, "2026-10-12T01:00:00.500Z")
        insert(2, "2026-10-12T01:00:00.500Z")
        insert(3, "2026-10-12T01:00:00.500Z")
        insert(4, "2026-10-11T14:59:59.999Z") // before the period: outside [from, to)

        val first = client.query("/v1/events", october12 + ("limit" to "2"))
        assertEquals(HttpStatusCode.OK, first.status)
        assertTrue(first.headers[HttpHeaders.ContentType]?.startsWith("application/json") == true)
        val firstText = first.bodyAsText()
        val page = json.decodeFromString<EventReceiptPage>(firstText)
        assertEquals(listOf(id(3), id(2)).map(UUID::toString), page.items.map { it.eventId })
        assertEquals("2026-10-12T01:00:00.500Z", page.items[0].receivedAt)
        assertEquals("2026-10-12T00:00:00.250Z", page.items[0].occurredAt)
        assertEquals("0.1.0", page.items[0].sdkVersion)
        assertEquals(installation.toString(), page.items[0].installationId)
        // Lists carry no properties.
        assertTrue(json.parseToJsonElement(firstText).jsonObject["items"]!!.jsonArray.all { "properties" !in it.jsonObject })

        val second = json.decodeFromString<EventReceiptPage>(
            client.query("/v1/events", october12 + ("limit" to "2") + ("cursor" to page.nextCursor!!)).bodyAsText(),
        )
        assertEquals(listOf(id(1).toString()), second.items.map { it.eventId })
        assertNull(second.nextCursor)

        val oldest = json.decodeFromString<EventReceiptPage>(
            client.query("/v1/events", october12 + ("sort" to "receivedAt_asc")).bodyAsText(),
        )
        assertEquals(listOf(1, 2, 3).map { id(it).toString() }, oldest.items.map { it.eventId })
        // A cursor from a newest-first page is not valid for oldest-first.
        assertEquals("INVALID_CURSOR", client.query("/v1/events", october12 + ("sort" to "receivedAt_asc") + ("cursor" to page.nextCursor!!)).errorCode())
    }

    @Test
    fun `empty period returns an empty page without a cursor`() = testApplication {
        useQueryModule()
        val page = json.decodeFromString<EventReceiptPage>(client.query("/v1/events", october12).bodyAsText())
        assertEquals(0, page.items.size)
        assertNull(page.nextCursor)
    }

    @Test
    fun `details return occurredAt receivedAt and flat properties and 404 for a missing event`() = testApplication {
        useQueryModule()
        insert(1, "2026-10-12T01:00:00.500Z", properties = """{"price":1.50,"sku":"a","in_stock":true}""")
        val response = client.query("/v1/events/${id(1)}")
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        val detail = json.decodeFromString<EventReceiptDetail>(text)
        assertEquals(id(1).toString(), detail.eventId)
        assertEquals("2026-10-12T00:00:00.250Z", detail.occurredAt)
        assertEquals("2026-10-12T01:00:00.500Z", detail.receivedAt)
        assertEquals(PropertyValue.StringValue("a"), detail.properties["sku"])
        assertEquals(PropertyValue.BooleanValue(true), detail.properties["in_stock"])
        assertIs<PropertyValue.BigDecimalValue>(detail.properties["price"])
        val properties = json.parseToJsonElement(text).jsonObject["properties"]!!.jsonObject
        // JSON numbers compare by value (Q62): trailing zeros are not preserved.
        assertEquals(0, java.math.BigDecimal(properties["price"]!!.jsonPrimitive.content).compareTo(java.math.BigDecimal("1.50")))
        assertTrue(properties["in_stock"]!!.jsonPrimitive.boolean)

        val missing = client.query("/v1/events/${id(2)}")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("EVENT_NOT_FOUND", missing.errorCode())
    }

    @Test
    fun `counts by name and by time use the same period and filters`() = testApplication {
        useQueryModule()
        insert(1, "2026-10-12T01:00:00Z", name = "cart_add")
        insert(2, "2026-10-12T01:30:00Z", name = "cart_add")
        insert(3, "2026-10-12T03:00:00Z", name = "purchase")
        insert(4, "2026-10-12T15:00:00Z", name = "purchase") // end is exclusive

        val names = json.decodeFromString<EventNameCounts>(client.query("/v1/event-counts/by-name", october12).bodyAsText())
        assertEquals(listOf("cart_add" to 2L, "purchase" to 1L), names.items.map { it.name to it.count })
        assertEquals(false, names.truncated)

        val filtered = json.decodeFromString<EventNameCounts>(
            client.query("/v1/event-counts/by-name", october12 + ("name" to "purchase")).bodyAsText(),
        )
        assertEquals(listOf("purchase" to 1L), filtered.items.map { it.name to it.count })

        val text = client.query("/v1/event-counts/by-time", october12).bodyAsText()
        val times = json.decodeFromString<EventTimeCounts>(text)
        assertEquals("Asia/Seoul", times.timeZone)
        assertEquals(EventTimeCounts.Bucket.HOUR, times.bucket)
        assertEquals(24, times.items.size)
        assertEquals("2026-10-11T15:00:00.000Z", times.items.first().start)
        // Seoul 10:00-10:59 holds rows 1 and 2 (01:00Z and 01:30Z); 12:00 holds row 3; the rest are 0.
        assertEquals(mapOf(10 to 2L, 12 to 1L), times.items.withIndex().filter { it.value.count > 0 }.associate { it.index to it.value.count })
        assertEquals(3L, times.items.sumOf { it.count })

        val days = json.decodeFromString<EventTimeCounts>(
            client.query("/v1/event-counts/by-time", mapOf("from" to "2026-10-11T00:00:00+09:00", "to" to "2026-10-14T00:00:00+09:00")).bodyAsText(),
        )
        assertEquals(EventTimeCounts.Bucket.DAY, days.bucket)
        assertEquals(listOf(0L, 3L, 1L), days.items.map { it.count })
        val obj = json.parseToJsonElement(text) as JsonObject
        assertEquals(setOf("timeZone", "bucket", "items"), obj.keys)
        assertIs<JsonArray>(obj["items"])
        assertIs<JsonPrimitive>(obj["items"]!!.jsonArray[0].jsonObject["count"])
        assertEquals(0, obj["items"]!!.jsonArray[0].jsonObject["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `route returns retryable 503 with Retry-After when the database is saturated`() = testApplication {
        database.close()
        database = Database(postgres.jdbcUrl, postgres.username, postgres.password, DatabaseLimits(concurrency = 1, poolSize = 2,
            requestMs = 2_000, admissionMs = 100, connectionMs = 250, lockMs = 100, statementMs = 300, cleanupMs = 250, responseMs = 100))
        database.migrate()
        useQueryModule()
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
                val response = client.query("/v1/events", october12)
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertEquals("1", response.headers[HttpHeaders.RetryAfter])
                assertEquals("TEMPORARILY_UNAVAILABLE", response.errorCode())
            } finally {
                release.countDown()
                holder.await()
            }
        }
    }

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
