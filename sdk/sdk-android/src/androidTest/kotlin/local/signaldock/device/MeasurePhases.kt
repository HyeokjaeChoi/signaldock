package local.signaldock.device

import androidx.test.platform.app.InstrumentationRegistry
import local.signaldock.measure.MeasureRunner
import org.junit.Assume.assumeTrue
import org.junit.Test

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
}
