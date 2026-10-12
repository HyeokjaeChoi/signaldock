package local.signaldock.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec section 7 "Contract changes", prior-request fixture: `fixtures/compat/prior-request-v1.json`
 * is a frozen request in the shape the first SDK (contract 1) sends. The current server must keep
 * accepting it, store it, and answer a resend with duplicates (Q65: compatible server changes
 * deploy first). When the contract changes, this test keeps the old fixture and adds a new one.
 */
class PriorRequestFixtureTest {
    private lateinit var database: Database
    private val fixture = File("../../fixtures/compat/prior-request-v1.json").readText()

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

    @Test
    fun `the current server accepts the prior request and answers a resend with duplicates`() = testApplication {
        application { ingestionModule(database, "test-key") }
        suspend fun send(): List<ItemAck.Status> {
            val response = client.post("/v1/events:batch") {
                header("SignalDock-Contract-Version", "1")
                header(HttpHeaders.Authorization, "Bearer test-key")
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(fixture)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            // the prior SDK reads the ACK with ignoreUnknownKeys, like AckValidator in the SDK
            return Json { ignoreUnknownKeys = true }.decodeFromString<BatchAckResponse>(response.bodyAsText())
                .results.sortedBy { it.index }.map { it.status }
        }

        assertEquals(listOf(ItemAck.Status.ACCEPTED, ItemAck.Status.ACCEPTED), send())
        assertEquals(2, runBlocking { database.transaction { query { fetchCount(EVENTS) } } })
        assertEquals(listOf(ItemAck.Status.DUPLICATE, ItemAck.Status.DUPLICATE), send())
        assertEquals(2, runBlocking { database.transaction { query { fetchCount(EVENTS) } } })
    }

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
