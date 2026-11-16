package local.signaldock.device

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import local.signaldock.InitResult
import local.signaldock.SignalDock
import local.signaldock.SignalDockConfig
import local.signaldock.TrackResult
import local.signaldock.measure.MeasureRunner
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * SDK load measurement phases (issue 24, ADR-0014), debug build. The phases live in
 * `src/measure/kotlin/.../MeasureRunner.kt`, shared with the R8 release measure app (issue 33, ADR-0018).
 * Started by `scripts/device-measure.sh`:
 *
 *   adb shell am instrument -w -e class local.signaldock.device.MeasurePhases -e phase <name> \
 *       -e endpoint <url> -e key <collection key> -e runId <id> <runner>
 *
 * Without a `phase` argument every phase is skipped.
 */
class MeasurePhases {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments().keySet().associateWith { InstrumentationRegistry.getArguments().getString(it).orEmpty() }
    private val runner = MeasureRunner(inst.targetContext, args) { inst.sendStatus(0, it) }

    private fun phase(name: String) {
        assumeTrue("phase $name not selected", runner.selected(name))
        runner.run()
    }

    @Test fun normal() = phase("normal")

    @Test fun burst() = phase("burst")

    /** Unreachable endpoint (`http://127.0.0.1:9`): 1,000 events back to back. No delivery can succeed. */
    @Test fun offline_store() = phase("offline_store")

    /** Process restarted (script force-stops it), endpoint reachable (ADR-0011 item 6). */
    @Test fun offline_drain() = phase("offline_drain")

    /**
     * Gate A4 (ADR-0049): `track()` called from a coroutine on the main thread, 1,000 events back to back, endpoint
     * unreachable (as `offline_store`). One call is timed from before it starts to its return, so the time bounds from
     * above how long the call keeps the main thread (the thread is free while the Room write runs). p50, p99 and the
     * maximum use nearest rank, like `perf/summarize.py`. `scripts/device-durability.sh` case `maincost` judges p99.
     */
    @Test fun main_thread() {
        assumeTrue("phase main_thread not selected", runner.selected("main_thread"))
        val config = SignalDockConfig(endpoint = "http://127.0.0.1:9", collectionKey = "device-test-key", allowInsecureEndpoint = true)
        val sdk = (runBlocking { SignalDock.initialize(inst.targetContext, config) } as InitResult.Success).sdk
        val micros = LongArray(1000)
        runBlocking(Dispatchers.Main) {
            for (i in micros.indices) {
                val start = System.nanoTime()
                val result = sdk.track("main_thread") { property("seq", i) }
                micros[i] = (System.nanoTime() - start) / 1000
                check(result is TrackResult.Success) { "track returned $result" }
            }
        }
        val sorted = micros.sorted()
        fun rank(p: Double) = sorted[ceil(p * sorted.size).toInt() - 1]
        inst.sendStatus(
            0,
            Bundle().apply {
                putString("phase", "main_thread"); putString("status", "pass"); putString("calls", "${micros.size}")
                putString("p50Micros", "${rank(0.5)}"); putString("p99Micros", "${rank(0.99)}"); putString("maxMicros", "${sorted.last()}")
            },
        )
    }
}
