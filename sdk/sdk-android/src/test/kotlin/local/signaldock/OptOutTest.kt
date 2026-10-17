package local.signaldock

import androidx.room.RoomDatabase
import kotlinx.coroutines.runBlocking
import local.signaldock.internal.init.RandomUuidV4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Opt-out and clearQueue (ADR-0034, spec Q156). */
@RunWith(RobolectricTestRunner::class)
class OptOutTest {
    private val sdkDir get() = File(context.noBackupFilesDir, "signaldock")

    @Test
    fun `collection is on by default`() = runBlocking {
        val sdk = newSdk()
        assertFalse(sdk.isOptedOut())
        assertTrue(sdk.track("e") is TrackResult.Success)
    }

    @Test
    fun `while opted out track stores nothing, even for invalid input`() = runBlocking {
        val sdk = newSdk()
        assertTrue(sdk.setOptOut(true))
        assertTrue(sdk.isOptedOut())
        assertEquals(TrackResult.OptedOut, sdk.track("e") { property("k", 1) })
        assertEquals(TrackResult.OptedOut, sdk.track(" bad name "))
        assertEquals(0, sdk.diagnostics().queuedEvents)
    }

    @Test
    fun `setOptOut false resumes collection`() = runBlocking {
        val sdk = newSdk()
        sdk.setOptOut(true)
        assertTrue(sdk.setOptOut(false))
        assertFalse(sdk.isOptedOut())
        assertTrue(sdk.track("e") is TrackResult.Success)
        assertFalse(File(sdkDir, "opt-out").exists())
    }

    @Test
    fun `queued events stay and are still sent while opted out`() = runBlocking {
        val transport = FakeTransport()
        val sdk = newSdk(transport = transport)
        assertTrue(sdk.track("before") is TrackResult.Success)
        sdk.setOptOut(true)
        assertEquals(1, sdk.diagnostics().queuedEvents)
        sdk.delivery.drainForeground()
        assertEquals(1, transport.requests.size)
        assertEquals(0, sdk.diagnostics().queuedEvents)
        // Nothing new was stored or sent after the first batch.
        sdk.track("after")
        sdk.delivery.drainForeground()
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `the state survives re-creating the SDK on the same files, in the backup-excluded directory`() = runBlocking {
        val first = newSdk(ids = RandomUuidV4)
        first.setOptOut(true)
        assertTrue(File(sdkDir, "opt-out").isFile)
        assertTrue(sdkDir.absolutePath.startsWith(context.noBackupFilesDir.absolutePath))

        // Same storage opened again, as after a process restart (a real process restart: not run).
        val second = newSdk(ids = RandomUuidV4)
        assertTrue(second.isOptedOut())
        assertEquals(TrackResult.OptedOut, second.track("e"))

        second.setOptOut(false)
        assertFalse(newSdk(ids = RandomUuidV4).isOptedOut())
    }

    @Test
    fun `setOptOut reports false when the state cannot be stored, and still applies in memory`() = runBlocking {
        val sdk = newSdk()
        // A non-empty directory at the marker path cannot be deleted, so removing the marker fails.
        File(sdkDir, "opt-out").mkdirs()
        File(sdkDir, "opt-out/x").writeText("x")
        assertFalse(sdk.setOptOut(false))
        assertFalse(sdk.isOptedOut())
        assertTrue(sdk.track("e") is TrackResult.Success)
    }

    @Test
    fun `clearQueue deletes every queued event and returns the count`() = runBlocking {
        val sdk = newSdk()
        repeat(3) { sdk.track("e") }
        assertEquals(3, sdk.clearQueue())
        assertEquals(0, sdk.diagnostics().queuedEvents)
        assertEquals(0, sdk.clearQueue())
        assertTrue(sdk.track("after") is TrackResult.Success)
    }

    @Test
    fun `clearQueue returns null on a storage failure and does not throw`() = runBlocking {
        val log = FakeLogger()
        val sdk = newSdk(logger = log)
        (sdk.dao.javaClass.getDeclaredField("__db").apply { isAccessible = true }.get(sdk.dao) as RoomDatabase).close()
        assertNull(sdk.clearQueue())
        assertTrue(log.warnings.any { it.startsWith("clearQueue storage failed:") })
    }
}
