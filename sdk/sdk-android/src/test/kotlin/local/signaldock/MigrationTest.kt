package local.signaldock

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Schema 1 shipped in v0.1.0. A later schema must keep that data (ADR-0025). Built from the committed 1.json. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private fun createV1(path: String) {
        val json = JSONObject(File("schemas/local.signaldock.internal.SignalDockDatabase/1.json").readText())
        val db = json.getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sql ->
            val entities = db.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val idx = e.optJSONArray("indices")
                for (j in 0 until (idx?.length() ?: 0)) sql.execSQL(idx!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = db.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO events (eventId, installationId, contractVersion, payloadJson, sizeBytes, createdAtMillis) VALUES ('e1','i1','1','{}',2,5)")
            sql.execSQL("INSERT INTO retry_state (id, failureCount, nextAttemptAtMillis, lastAckAccepted, lastAckDuplicate, lastAckRejected, hold) VALUES (1,3,9000,0,0,0,1)")
            sql.version = 1
        }
    }

    @Test
    fun `a schema 1 database keeps its events and retry state and has no stored wait`() = runBlocking {
        val dir = File(context.noBackupFilesDir, "signaldock").apply { mkdirs() }
        createV1(File(dir, "queue.db").absolutePath)
        val sdk = newSdk()
        assertEquals(listOf("e1"), sdk.dao.oldest(10).map { it.eventId })
        val state = sdk.dao.retryState()!!
        assertEquals(3, state.failureCount)
        assertEquals(9000L, state.nextAttemptAtMillis)
        assertEquals(true, state.hold)
        assertNull(state.waitMillis)
    }
}
