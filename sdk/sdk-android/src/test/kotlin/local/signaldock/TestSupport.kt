package local.signaldock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import local.signaldock.internal.Clock
import local.signaldock.internal.IdGenerator
import local.signaldock.internal.Logger
import local.signaldock.internal.QueueLimits
import local.signaldock.internal.createSdk
import java.util.concurrent.atomic.AtomicLong

internal val context: Context get() = ApplicationProvider.getApplicationContext()

internal val testConfig = SignalDockConfig(endpoint = "http://localhost:8080", collectionKey = "test-key")

internal class FakeLogger : Logger {
    val warnings = mutableListOf<String>()
    override fun debug(msg: String) {}
    override fun warn(msg: String) { warnings += msg }
    override fun error(msg: String) { warnings += msg }
}

internal class SequenceIds : IdGenerator {
    private val n = AtomicLong()
    override fun newId(): String = "00000000-0000-4000-8000-%012d".format(n.incrementAndGet())
}

/** Real Room database in the Robolectric noBackupFilesDir; fake clock and ids. */
internal suspend fun newSdk(
    limits: QueueLimits = QueueLimits(),
    ids: IdGenerator = SequenceIds(),
    nowMillis: Long = 1_760_000_000_123L,
): SignalDock = createSdk(context, testConfig, Clock { nowMillis }, ids, limits)
