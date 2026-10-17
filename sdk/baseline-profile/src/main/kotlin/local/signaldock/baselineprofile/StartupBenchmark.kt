package local.signaldock.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start of :profile-app. The metric is the async trace section "profileapp:sdk_ready": from
 * Application.onCreate to the end of initialize(), 20 track() calls, and diagnostics() (ADR-0040). On an
 * emulator, pass
 * -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
 * (emulator numbers are not device numbers).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    // The SDK schedules WorkManager jobs. When a compile step kills the process, JobScheduler starts it again,
    // and Macrobenchmark refuses a cold start of a running process. "pm clear" also removes the jobs.
    private fun clearApp() { device.executeShellCommand("pm clear $PACKAGE") }

    @Before fun clearAppBeforeTest() = clearApp()

    @Test fun startupNoCompilation() = startup(CompilationMode.None())

    @Test fun startupBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(TraceSectionMetric("profileapp:sdk_ready")),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 20,
        // Same state for every iteration: an empty queue and no scheduled job.
        setupBlock = { clearApp() },
    ) {
        pressHome()
        startActivityAndWait()
        // The first frame comes before the SDK work ends. The activity shows the diagnostics when it ends (and later a \"Delivery\" text).
        check(device.wait(Until.hasObject(By.text(Pattern.compile("(Diagnostics|Delivery).*"))), 15_000)) { "SDK work did not finish" }
    }

    private companion object { const val PACKAGE = "local.signaldock.profileapp" }
}
