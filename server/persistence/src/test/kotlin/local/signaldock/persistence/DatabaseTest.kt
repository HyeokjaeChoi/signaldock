package local.signaldock.persistence

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
                        EVENTS.SDK_VERSION, EVENTS.PROPERTIES)
                        .values(id, id, "timeout", java.time.OffsetDateTime.now(), "0.1.0", org.jooq.JSONB.valueOf("{}"))
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

    private fun newDatabase(limits: DatabaseLimits = DatabaseLimits()) =
        Database(postgres.jdbcUrl, postgres.username, postgres.password, limits)

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
        ).asCompatibleSubstituteFor("postgres"))
    }
}
