package local.signaldock.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collects the profile of the SDK hot paths. One cold start of :profile-app runs initialize(), track(),
 * the Room queue, the sender (it fails on purpose: the endpoint does not answer) and diagnostics().
 * Run: sdk/gradlew -p sdk :sdk-android:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun generate() = rule.collect(packageName = "local.signaldock.profileapp") {
        pressHome()
        startActivityAndWait()
        // The app shows "Delivery attempted" when initialize(), track(), diagnostics(), and one failed
        // sender request are done. Stop only then, and fail if the signal never comes.
        check(device.wait(Until.hasObject(By.text("Delivery attempted")), SIGNAL_TIMEOUT_MS)) {
            "profile-app did not report a delivery attempt"
        }
    }

    private companion object { const val SIGNAL_TIMEOUT_MS = 30_000L }
}
