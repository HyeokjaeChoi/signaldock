package local.signaldock.server

import local.signaldock.persistence.DatabaseLimits
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseLimitsEnvTest {
    @Test
    fun `no variables give the local defaults`() {
        assertEquals(DatabaseLimits(), databaseLimitsFromEnv { null })
    }

    @Test
    fun `variables override single limits`() {
        val env = mapOf(
            "SIGNALDOCK_DB_POOL_SIZE" to "8", "SIGNALDOCK_DB_POOL_MIN_IDLE" to "8", "SIGNALDOCK_DB_CONCURRENCY" to "8",
            "SIGNALDOCK_DB_ADMISSION_MS" to "1000", "SIGNALDOCK_DB_CONNECTION_MS" to "1000",
            "SIGNALDOCK_DB_LOCK_MS" to "500", "SIGNALDOCK_DB_STATEMENT_MS" to "2000",
        )
        val limits = databaseLimitsFromEnv(env::get)
        assertEquals(DatabaseLimits(concurrency = 8, poolSize = 8, minimumIdle = 8, admissionMs = 1000,
            connectionMs = 1000, lockMs = 500, statementMs = 2000), limits)
    }

    @Test
    fun `a malformed value fails instead of using the default`() {
        val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
            databaseLimitsFromEnv(mapOf("SIGNALDOCK_DB_POOL_SIZE" to "8 ")::get)
        }
        assertEquals("SIGNALDOCK_DB_POOL_SIZE is not an integer", error.message)
    }
}
