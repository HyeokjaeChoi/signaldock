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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.signaldock.ingestion.PlanStatus
import local.signaldock.persistence.Database
import local.signaldock.persistence.QueryStore
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.openapitools.client.models.ErrorResponse
import org.openapitools.client.models.EventPlanStatusCounts
import org.openapitools.client.models.EventReceiptDetail
import org.openapitools.client.models.EventReceiptPage
import org.openapitools.client.models.ItemAck
import org.openapitools.client.models.PlanStatus as ApiPlanStatus
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0022: stored events are marked against the tracking plan; nothing is rejected or dropped for plan reasons. */
class PlanStatusTest {
    private lateinit var database: Database
    private val json = Json
    private val plan = parseTrackingPlan("""{"version":1,"events":[
        {"name":"product_viewed","properties":[
            {"key":"product_id","type":"string","required":true},
            {"key":"price_minor","type":"integer","required":false}]},
        {"name":"app_opened","properties":[]}]}""")
    private val laxPlan = parseTrackingPlan("""{"version":1,"events":[{"name":"screen_opened","properties":[]}]}""")

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

    private fun event(n: Int, name: String, properties: String) =
        """{"eventId":"${id(n)}","name":"$name","occurredAt":"2026-10-09T21:30:00.123Z","properties":$properties,"sdkVersion":"0.1.0","contractVersion":"1"}"""

    private fun batch(vararg events: String) =
        """{"installationId":"47c4d89f-352a-41da-9b31-529ea756515a","events":[${events.joinToString(",")}]}"""

    private suspend fun HttpClient.ingest(body: String): HttpResponse = post("/v1/events:batch") {
        header("SignalDock-Contract-Version", "1")
        header(HttpHeaders.Authorization, "Bearer collection-key")
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.query(path: String, params: Map<String, String> = emptyMap()): HttpResponse = get(path) {
        header("SignalDock-Contract-Version", "1")
        header(HttpHeaders.Authorization, "Bearer query-key")
        (period() + params).forEach { (key, value) -> parameter(key, value) }
    }

    /** The details route takes no query parameters. */
    private suspend fun HttpClient.detail(n: Int): HttpResponse = get("/v1/events/${id(n)}") {
        header("SignalDock-Contract-Version", "1")
        header(HttpHeaders.Authorization, "Bearer query-key")
    }

    private fun period(): Map<String, String> {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        return mapOf("from" to now.minus(1, ChronoUnit.HOURS).toString(), "to" to now.plus(1, ChronoUnit.HOURS).toString())
    }

    private fun ApplicationTestBuilder.useModules(trackingPlan: local.signaldock.ingestion.TrackingPlan?) {
        application {
            ingestionModule(database, "collection-key", trackingPlan = trackingPlan)
            queryModule(database, "query-key", ZoneId.of("Asia/Seoul"))
        }
    }

    @Test
    fun `every event is stored and acknowledged as before, and marked with its plan status`() = testApplication {
        useModules(plan)
        val ack = client.ingest(batch(
            event(1, "product_viewed", """{"product_id":"p1","price_minor":100}"""), // planned
            event(2, "screen_opened", """{"x":1}"""), // unplanned_event
            event(3, "product_viewed", """{"price_minor":100}"""), // invalid: required key missing
            event(4, "product_viewed", """{"product_id":"p1","color":"red"}"""), // invalid: unknown key
            event(5, "product_viewed", """{"product_id":"p1","price_minor":1.5}"""), // invalid: wrong type
        ))
        assertEquals(HttpStatusCode.OK, ack.status)
        // The ACK has no plan field and no plan-related rejection: all five are accepted.
        val results = json.parseToJsonElement(ack.bodyAsText()).jsonObject["results"]!!.jsonArray
        assertEquals(List(5) { "accepted" }, results.map { it.jsonObject["status"]!!.jsonPrimitive.content })
        assertEquals(setOf("index", "status", "eventId"), results[0].jsonObject.keys)

        val expected = mapOf(1 to ApiPlanStatus.PLANNED, 2 to ApiPlanStatus.UNPLANNED_EVENT, 3 to ApiPlanStatus.INVALID_PROPERTIES,
            4 to ApiPlanStatus.INVALID_PROPERTIES, 5 to ApiPlanStatus.INVALID_PROPERTIES)
        val page = json.decodeFromString<EventReceiptPage>(client.query("/v1/events").bodyAsText())
        assertEquals(expected.mapKeys { id(it.key).toString() }, page.items.associate { it.eventId to it.planStatus })
        for ((n, status) in expected) {
            val detail = json.decodeFromString<EventReceiptDetail>(client.detail(n).bodyAsText())
            assertEquals(status, detail.planStatus, "event $n")
        }

        val violations = json.decodeFromString<EventReceiptPage>(client.query("/v1/events", mapOf("planStatus" to "invalid_properties")).bodyAsText())
        assertEquals(setOf(3, 4, 5).map { id(it).toString() }.toSet(), violations.items.map { it.eventId }.toSet())
        val unplanned = json.decodeFromString<EventReceiptPage>(client.query("/v1/events", mapOf("planStatus" to "unplanned_event")).bodyAsText())
        assertEquals(listOf(id(2).toString()), unplanned.items.map { it.eventId })
        val byName = client.query("/v1/event-counts/by-name", mapOf("planStatus" to "planned")).bodyAsText()
        assertEquals(listOf("product_viewed"), json.parseToJsonElement(byName).jsonObject["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(client.query("/v1/event-counts/by-time", mapOf("planStatus" to "planned")).status == HttpStatusCode.OK)

        val counts = json.decodeFromString<EventPlanStatusCounts>(client.query("/v1/event-counts/by-plan-status").bodyAsText())
        assertEquals(EventPlanStatusCounts(planned = 1, unplannedEvent = 1, invalidProperties = 3, notChecked = 0), counts)
        // The exact filters still apply to the counts.
        val one = json.decodeFromString<EventPlanStatusCounts>(client.query("/v1/event-counts/by-plan-status", mapOf("name" to "product_viewed")).bodyAsText())
        assertEquals(EventPlanStatusCounts(planned = 1, unplannedEvent = 0, invalidProperties = 3, notChecked = 0), one)
    }

    @Test
    fun `without a plan nothing is checked and the field is absent and the events count as not checked`() = testApplication {
        useModules(null)
        client.ingest(batch(event(1, "anything_at_all", """{"x":1}"""), event(2, "product_viewed", "{}")))
        val listText = client.query("/v1/events").bodyAsText()
        assertTrue(json.parseToJsonElement(listText).jsonObject["items"]!!.jsonArray.all { "planStatus" !in it.jsonObject })
        assertNull(json.decodeFromString<EventReceiptDetail>(client.detail(1).bodyAsText()).planStatus)
        assertTrue("planStatus" !in json.parseToJsonElement(client.detail(1).bodyAsText()).jsonObject)
        assertEquals(EventPlanStatusCounts(0, 0, 0, 2), json.decodeFromString(client.query("/v1/event-counts/by-plan-status").bodyAsText()))
        // A status filter never matches a row that was not checked.
        for (status in listOf("planned", "unplanned_event", "invalid_properties")) {
            val page = json.decodeFromString<EventReceiptPage>(client.query("/v1/events", mapOf("planStatus" to status)).bodyAsText())
            assertEquals(0, page.items.size, status)
        }
    }

    @Test
    fun `a duplicate keeps the status of the first insert`() = runBlocking {
        val first = Ingestion(database, plan)
        val unplannedAtFirst = parseBatch(batch(event(1, "screen_opened", "{}")).encodeToByteArray(), "1")
        assertEquals(ItemAck.Status.ACCEPTED, first.ingest(unplannedAtFirst).results.single().status)
        // The same event again, now under a plan that would call it planned: still a duplicate, status unchanged.
        assertEquals(ItemAck.Status.DUPLICATE, Ingestion(database, laxPlan).ingest(unplannedAtFirst).results.single().status)
        assertEquals(PlanStatus.UNPLANNED_EVENT, QueryStore(database).find(id(1))!!.summary.planStatus)

        // Stored without a plan (null), resent with one: stays null.
        val unchecked = parseBatch(batch(event(2, "screen_opened", "{}")).encodeToByteArray(), "1")
        assertEquals(ItemAck.Status.ACCEPTED, Ingestion(database).ingest(unchecked).results.single().status)
        assertEquals(ItemAck.Status.DUPLICATE, first.ingest(unchecked).results.single().status)
        assertNull(QueryStore(database).find(id(2))!!.summary.planStatus)

        // Different content under the same ID is still CONFLICT; the plan changes nothing about that.
        val conflict = parseBatch(batch(event(1, "product_viewed", "{}")).encodeToByteArray(), "1")
        assertEquals("CONFLICT", first.ingest(conflict).results.single().code)
    }

    @Test
    fun `plan status parameters are validated`() = testApplication {
        useModules(plan)
        val bad = client.query("/v1/events", mapOf("planStatus" to "violation"))
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("INVALID_PARAMETER", json.decodeFromString<ErrorResponse>(bad.bodyAsText()).code)
        // The plan-status counts split by status already, so the filter is not a parameter there.
        val split = client.query("/v1/event-counts/by-plan-status", mapOf("planStatus" to "planned"))
        assertEquals(HttpStatusCode.BadRequest, split.status)
        assertEquals("INVALID_PARAMETER", json.decodeFromString<ErrorResponse>(split.bodyAsText()).code)
    }

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
