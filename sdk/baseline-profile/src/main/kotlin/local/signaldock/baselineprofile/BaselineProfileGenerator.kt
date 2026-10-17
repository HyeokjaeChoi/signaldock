package local.signaldock.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Test
    fun generate() = rule.collect(packageName = "local.signaldock.profileapp") {
        pressHome()
        startActivityAndWait()
        // The sender runs in WorkManager after track(). Give it time to run one cycle and fail.
        Thread.sleep(SENDER_WAIT_MS)
    }

    private companion object { const val SENDER_WAIT_MS = 3_000L }
}
