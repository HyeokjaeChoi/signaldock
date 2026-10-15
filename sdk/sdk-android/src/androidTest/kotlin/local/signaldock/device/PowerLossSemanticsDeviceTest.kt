package local.signaldock.device

import androidx.room.RoomDatabase
import kotlinx.coroutines.runBlocking
import local.signaldock.TrackResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Power loss is not tested (no emulator can cut power in the middle of a write). This test pins the setting
 * that decides the semantics, so that the text in ADR-0029 cannot drift silently: a committed event survives
 * process death (SIGKILL), not power loss, because the queue is in WAL mode with `synchronous=NORMAL`.
 * It asks the SDK's own database connection, not a new one: a plain connection reports its own default.
 */
class PowerLossSemanticsDeviceTest {
    private var rig: Rig? = null

    @After
    fun tearDown() { rig?.close() }

    @Test
    fun the_queue_runs_in_WAL_mode_with_synchronous_NORMAL() = runBlocking {
        val r = Rig(AckServer().start()).also { rig = it }
        val sdk = r.initialize()
        assertTrue(sdk.track("pragma") is TrackResult.Success)
        // Room's generated DAO keeps its database in a field; the test fails loudly if that changes.
        val db = sdk.dao.javaClass.declaredFields.first { RoomDatabase::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }.get(sdk.dao) as RoomDatabase
        val sqlite = db.openHelper.writableDatabase
        fun pragma(name: String) = sqlite.query("PRAGMA $name").use { it.moveToFirst(); it.getString(0) }
        assertEquals("wal", pragma("journal_mode").lowercase())
        assertEquals("synchronous is 1 (NORMAL), not 2 (FULL)", "1", pragma("synchronous"))
        report("synchronous", pragma("synchronous"), "journal_mode", pragma("journal_mode"))
    }
}
