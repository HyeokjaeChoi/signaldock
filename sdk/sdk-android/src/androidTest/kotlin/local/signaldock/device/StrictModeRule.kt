package local.signaldock.device

import android.os.StrictMode
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Gate A3 (ADR-0049): while a test runs, the main thread has a StrictMode thread policy with every detector
 * on (`detectAll`: disk reads and writes, network, unbuffered I/O, custom slow calls, resource mismatches) and
 * `penaltyDeath`. A public SDK call that the test makes on the main thread and that touches the disk or the
 * network there kills the app process, so the instrumentation run and the `device-test` job fail. The
 * stack goes to Logcat first (`penaltyLog`).
 *
 * A thread policy belongs to one thread: the test thread and the SDK's own IO threads are not checked, only
 * the work that runs on the main thread. A test that never uses the main thread is not affected.
 */
class MainThreadStrictMode : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var saved: StrictMode.ThreadPolicy
            instrumentation.runOnMainSync {
                saved = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().penaltyDeath().build())
            }
            try {
                base.evaluate()
            } finally {
                instrumentation.runOnMainSync { StrictMode.setThreadPolicy(saved) }
            }
        }
    }
}
