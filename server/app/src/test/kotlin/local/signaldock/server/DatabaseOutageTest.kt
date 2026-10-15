package local.signaldock.server

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.ServerSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import local.signaldock.persistence.Database
import local.signaldock.server.jooq.tables.Events.EVENTS
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Failure mode 11: PostgreSQL stops while the server runs, then returns. No server restart (ADR-0026). */
class DatabaseOutageTest {
    @Test
    fun `ingest and readiness return 503 while the database is stopped and recover after it starts`() {
        // A fixed host port: a restarted container keeps it, so the pool reconnects to the same URL.
        val port = ServerSocket(0).use { it.localPort }
        val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres")).withCreateContainerCmdModifier { cmd ->
            cmd.hostConfig!!.withPortBindings(PortBinding(Ports.Binding.bindPort(port), ExposedPort(5432)))
        }
        postgres.start()
        val docker = DockerClientFactory.instance().client()
        val database = Database("jdbc:postgresql://${postgres.host}:$port/${postgres.databaseName}", postgres.username, postgres.password)
        try {
            database.migrate()
            testApplication {
                application { ingestionModule(database, "test-key") }
                suspend fun send(id: String) = client.post("/v1/events:batch") {
                    header("SignalDock-Contract-Version", "1")
                    header(HttpHeaders.Authorization, "Bearer test-key")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(batch(id))
                }

                assertEquals(HttpStatusCode.OK, client.get("/ready").status)
                assertEquals(HttpStatusCode.OK, send(FIRST).status)

                docker.stopContainerCmd(postgres.containerId).exec()

                val down = client.get("/ready")
                assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
                assertEquals("1", down.headers[HttpHeaders.RetryAfter])
                assertEquals(HttpStatusCode.OK, client.get("/health").status) // liveness ignores the database
                val refused = send(SECOND)
                assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
                assertEquals("1", refused.headers[HttpHeaders.RetryAfter])
                assertTrue(refused.bodyAsText().contains("TEMPORARILY_UNAVAILABLE"))

                docker.startContainerCmd(postgres.containerId).exec()
                // The pool must reconnect by itself. Poll readiness; never rebuild the Database.
                var back = false
                repeat(60) {
                    if (!back) {
                        back = client.get("/ready").status == HttpStatusCode.OK
                        if (!back) delay(500)
                    }
                }
                assertTrue(back, "readiness did not recover")
                assertEquals(HttpStatusCode.OK, send(SECOND).status)

                // SECOND was refused during the outage, so only the resend stored it: two rows, no loss, no extra.
                val ids = runBlocking {
                    database.transaction { query { select(EVENTS.EVENT_ID).from(EVENTS).fetch(EVENTS.EVENT_ID).map(Any::toString).sorted() } }
                }
                assertEquals(listOf(FIRST, SECOND), ids)
            }
        } finally {
            database.close()
            postgres.stop()
        }
    }

    private fun batch(id: String) = """{"installationId":"47c4d89f-352a-41da-9b31-529ea756515a","events":[{"eventId":"$id","name":"product_viewed","occurredAt":"2026-10-09T21:30:00.123Z","properties":{"price":1},"sdkVersion":"0.1.0","contractVersion":"1"}]}"""

    private companion object {
        const val FIRST = "a0000000-0000-4000-8000-000000000001"
        const val SECOND = "a0000000-0000-4000-8000-000000000002"
    }
}
