package local.signaldock.device

import android.content.res.Resources
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import local.signaldock.TrackResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Power loss is not tested (no emulator can cut power in the middle of a write). This test pins the setting
 * that decides the semantics, so that the text in ADR-0029 cannot drift silently: the queue is in WAL mode, and
 * the SDK sets no `synchronous` of its own, so the connection uses the platform's WAL sync mode (the system
 * property `debug.sqlite.wal.syncmode` if set, else the resource `db_wal_sync_mode`). On new images this is NORMAL: a committed event survives process death (SIGKILL),
 * not power loss. On API 24 images it is FULL: every commit syncs.
 * It asks the SDK's own database connection, not a new one: a plain connection reports its own default.
 */
class PowerLossSemanticsDeviceTest {
    private var rig: Rig? = null

    @After
    fun tearDown() { rig?.close() }

    @Test
    fun the_queue_runs_in_WAL_mode_with_the_platform_WAL_sync_mode() = runBlocking {
        val r = Rig(AckServer().start()).also { rig = it }
        val sdk = r.initialize()
        assertTrue(sdk.track("pragma") is TrackResult.Success)
        val sqlite = sdk.sqlite()
        fun pragma(name: String) = sqlite.query("PRAGMA $name").use { it.moveToFirst(); it.getString(0) }
        val property = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("getprop debug.sqlite.wal.syncmode")
            .let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes().decodeToString().trim() } }
        val resources = Resources.getSystem()
        val id = resources.getIdentifier("db_wal_sync_mode", "string", "android")
        val platformMode = when {
            property.isNotEmpty() -> property
            id != 0 -> resources.getString(id)
            else -> throw AssertionError("no db_wal_sync_mode resource and no debug.sqlite.wal.syncmode")
        }
        val expected = when (platformMode.uppercase()) {
            "NORMAL" -> "1"
            "FULL" -> "2"
            else -> throw AssertionError("db_wal_sync_mode is '$platformMode': expected NORMAL or FULL")
        }
        val synchronous = pragma("synchronous")
        assertEquals("journal_mode", "wal", pragma("journal_mode").lowercase())
        assertEquals("synchronous is $synchronous, the platform WAL sync mode $platformMode is $expected", expected, synchronous)
        report("synchronous", synchronous, "platformWalSyncMode", platformMode, "journal_mode", pragma("journal_mode"))
    }
}
