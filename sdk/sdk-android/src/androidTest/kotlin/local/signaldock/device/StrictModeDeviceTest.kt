package local.signaldock.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import local.signaldock.TrackResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Gate A3 (ADR-0049): the public SDK calls that a host makes on the main thread do no disk or network I/O
 * there. [MainThreadStrictMode] kills the process on the first violation, so a pass means none happened.
 * The rig has the production defaults that touch the caller's thread: the real WorkManager scheduler and the
 * foreground trigger. `initialize` runs the SDK's own composition (`InitCoordinator`) like `SignalDock.initialize`.
 * The SDK has no public `flush()`: the foreground drain stands in for it.
 */
class StrictModeDeviceTest {
    @get:Rule val strictMode = MainThreadStrictMode()

    private var rig: Rig? = null

    @After
    fun tearDown() { rig?.close() }

    @Test
    fun public_calls_on_the_main_thread_do_no_disk_or_network_io() = runBlocking {
        val server = AckServer().start()
        val r = Rig(server, scheduler = null, foregroundTriggers = true).also { rig = it }
        withContext(Dispatchers.Main) {
            val sdk = r.initialize()
            // 25 events: the 20th starts a foreground drain (a coroutine on the SDK scope, off the main thread).
            repeat(25) { assertTrue(sdk.track("strict_$it") { property("n", it) } is TrackResult.Success) }
            assertTrue(sdk.diagnostics().storageAvailable)
            sdk.delivery.drainForeground()
            sdk.delivery.ensureScheduled(System.currentTimeMillis()) // what ForegroundExitObserver runs on the main thread

            assertTrue(sdk.setOptOut(true))
            assertTrue(sdk.isOptedOut())
            assertEquals(TrackResult.OptedOut, sdk.track("opted_out"))
            assertTrue(sdk.setOptOut(false))
            assertFalse(sdk.isOptedOut())
            assertTrue(sdk.track("collected_again") is TrackResult.Success)
            assertTrue(sdk.clearQueue() != null)
        }
        report("strictMode", "no violation", "ackedEvents", "${server.committed.size}")
    }
}
