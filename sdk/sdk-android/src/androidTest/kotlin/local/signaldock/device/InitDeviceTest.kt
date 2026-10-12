package local.signaldock.device

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import local.signaldock.LogLevel
import local.signaldock.SignalDockConfig
import local.signaldock.TrackResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Q110, Q111, Q115 on a real device: offline initialization, main-thread behavior and real Logcat output. */
class InitDeviceTest {
    private var rig: Rig? = null

    @After
    fun tearDown() { rig?.close() }

    private fun rig(config: SignalDockConfig? = null, server: AckServer = AckServer().start()): Rig =
        Rig(server, config = config ?: server.config()).also { rig = it }

    @Test
    fun Q110_initialization_succeeds_offline_without_a_reachable_server() = runBlocking {
        val r = rig(SignalDockConfig(endpoint = "http://127.0.0.1:9", collectionKey = "device-test-key"))
        val sdk = r.initialize() // nothing listens on port 9: no server connection is needed
        assertTrue(sdk.track("offline") is TrackResult.Success)
        assertEquals(1, sdk.diagnostics().queuedEvents)
    }

    @Test
    fun Q111_initialization_and_track_do_not_block_the_main_thread() = runBlocking {
        val r = rig()
        val main = Handler(Looper.getMainLooper())
        val running = AtomicBoolean(true)
        var last = SystemClock.uptimeMillis()
        var maxGap = 0L
        var ticks = 0
        val ticker = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                maxGap = maxOf(maxGap, now - last)
                last = now
                ticks++
                if (running.get()) main.postDelayed(this, 5)
            }
        }
        main.post(ticker)
        val queued = withContext(Dispatchers.Main) {
            val sdk = r.initialize()
            repeat(200) { check(sdk.track("main_thread") { property("n", it) } is TrackResult.Success) }
            sdk.diagnostics().queuedEvents
        }
        running.set(false)
        assertEquals(200, queued)
        assertTrue("main thread ticked $ticks times", ticks > 5)
        assertTrue("main thread stalled for $maxGap ms", maxGap < 1_000)
        report("Q111", "ticks=$ticks maxMainThreadGapMillis=$maxGap")
    }

    @Test
    fun Q115_default_level_logs_only_WARN_and_ERROR_and_DEBUG_adds_state_without_content() = runBlocking {
        for (level in listOf(LogLevel.WARN, LogLevel.DEBUG)) {
            val server = AckServer().start()
            rig?.close()
            val r = Rig(server, config = server.config(level)).also { rig = it }
            val before = Logcat.snapshot()
            val sdk = r.initialize()
            sdk.track("secret_event") { property("secret_key", "secret_value") }
            sdk.delivery.sender.run()
            val lines = Logcat.since(before)
            val installationId = File(r.context.noBackupFilesDir, "signaldock/installation-id").readText().trim()
            val text = lines.joinToString("\n")
            for (s in listOf("secret_event", "secret_key", "secret_value", "device-test-key", installationId, "127.0.0.1", "localhost")) {
                assertFalse("$level: '$s' in log", text.contains(s))
            }
            if (level == LogLevel.WARN) {
                assertEquals("WARN default: nothing below WARN", emptyList<String>(), lines)
            } else {
                assertTrue("DEBUG adds initialization state", lines.any { it.startsWith("D/SignalDock") && it.contains("initialized") })
                assertTrue("DEBUG adds delivery state", lines.any { it.startsWith("D/SignalDock") && it.contains("delivery attempt") })
            }
        }
    }
}
