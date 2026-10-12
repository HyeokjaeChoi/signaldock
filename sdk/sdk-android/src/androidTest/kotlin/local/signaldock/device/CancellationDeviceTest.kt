package local.signaldock.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import local.signaldock.LogLevel
import local.signaldock.SignalDock
import local.signaldock.TrackResult
import local.signaldock.internal.collection.EventCollector
import local.signaldock.internal.init.AndroidLogger
import local.signaldock.internal.init.Clock
import local.signaldock.internal.init.Logger
import local.signaldock.internal.init.RandomUuidV4
import local.signaldock.internal.storage.AddOutcome
import local.signaldock.internal.storage.EventEntity
import local.signaldock.internal.storage.EventSink
import local.signaldock.internal.storage.QueueLimits
import local.signaldock.internal.storage.RoomEventSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Spec section 3 "Lifetime and cancellation" and section 7 "Collection cancellation" on a real device,
 * with the real Room storage and the real Logcat output of [AndroidLogger]. Content strings
 * (`secret_*`) must never reach the log.
 */
class CancellationDeviceTest {
    private lateinit var server: AckServer
    private lateinit var rig: Rig
    private lateinit var sdk: SignalDock
    private val inner get() = RoomEventSink(sdk.dao)

    @Before
    fun setUp() {
        server = AckServer().start()
        rig = Rig(server)
        sdk = runBlocking { rig.initialize() }
    }

    @After
    fun tearDown() = rig.close()

    /** The real [AndroidLogger] (Logcat), plus a record: logd merges identical consecutive lines, so Logcat cannot be counted. */
    private class RecordingLogger : Logger {
        private val real = AndroidLogger(LogLevel.WARN)
        val warnings = CopyOnWriteArrayList<String>()
        override fun debug(msg: String) = real.debug(msg)
        override fun warn(msg: String) { warnings += msg; real.warn(msg) }
        override fun error(msg: String) { warnings += msg; real.error(msg) }
    }

    private fun collector(sink: EventSink, logger: Logger = AndroidLogger(LogLevel.WARN)): EventCollector {
        val installationId = File(rig.context.noBackupFilesDir, "signaldock/installation-id").readText().trim()
        return EventCollector(installationId, sink, Clock { System.currentTimeMillis() }, RandomUuidV4, QueueLimits(), logger)
    }

    private fun assertNoContent(lines: List<String>) {
        val text = lines.joinToString("\n")
        for (secret in listOf("secret_name", "secret_key", "secret_value", "device-test-key")) assertFalse("$secret in log", text.contains(secret))
    }

    @Test
    fun cancellation_before_storage_stores_nothing_and_logs_no_warning() = runBlocking {
        val before = Logcat.snapshot()
        val j = rig.scope.launch {
            collector(inner).track("secret_name") {
                property("secret_key", "secret_value")
                this@launch.coroutineContext.job.cancel() // cancelled while the call is still validating
            }
        }
        j.join()
        assertTrue(j.isCancelled)
        assertEquals(0, sdk.dao.usage().count)
        assertEquals(emptyList<String>(), Logcat.warnings(Logcat.since(before)))
    }

    @Test
    fun cancellation_during_storage_before_the_commit_keeps_nothing_and_logs_one_content_free_warning() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val sink = object : EventSink {
            override suspend fun add(event: EventEntity, limits: QueueLimits): AddOutcome {
                started.complete(Unit)
                awaitCancellation() // storage is still running: nothing committed
            }
        }
        val before = Logcat.snapshot()
        val caught = CompletableDeferred<Throwable>()
        val j = rig.scope.launch {
            try { collector(sink).track("secret_name") { property("secret_key", "secret_value") } }
            catch (e: Throwable) { caught.complete(e); throw e }
        }
        started.await()
        j.cancel()
        j.join()
        assertTrue("CancellationException is propagated, not turned into a result", caught.await() is CancellationException)
        assertEquals(0, sdk.dao.usage().count)
        val lines = Logcat.since(before)
        assertEquals(1, Logcat.warnings(lines).size)
        assertTrue(Logcat.warnings(lines).single().startsWith("W/SignalDock"))
        assertNoContent(lines)
    }

    @Test
    fun cancellation_after_the_commit_keeps_the_event_warns_and_the_event_is_delivered_later() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val sink = object : EventSink {
            override suspend fun add(event: EventEntity, limits: QueueLimits): AddOutcome {
                inner.add(event, limits) // the commit happened ...
                committed.complete(Unit)
                awaitCancellation() // ... while the result is still on its way to the caller
            }
        }
        val before = Logcat.snapshot()
        val caught = CompletableDeferred<Throwable>()
        val j = rig.scope.launch {
            try { collector(sink).track("secret_name") { property("secret_key", "secret_value") } }
            catch (e: Throwable) { caught.complete(e); throw e }
        }
        committed.await()
        j.cancel()
        j.join()
        assertTrue(caught.await() is CancellationException)
        val lines = Logcat.since(before)
        assertEquals(1, Logcat.warnings(lines).size)
        assertNoContent(lines)

        // Retention: the committed event is still queued and is sent with its stored ID.
        val stored = sdk.dao.oldest(10)
        assertEquals(1, stored.size)
        sdk.delivery.sender.run()
        assertEquals(0, sdk.dao.usage().count)
        assertEquals(listOf(stored.single().eventId), server.receivedIds.toList())
    }

    @Test
    fun many_calls_cancelled_at_random_moments_never_lose_a_reported_success_or_duplicate_an_event() = runBlocking {
        val log = RecordingLogger()
        val success = CopyOnWriteArrayList<String>()
        var cancelled = 0
        val lock = Any()
        val c = collector(inner, log)
        val random = Random(7)
        val jobs = ArrayList<Job>()
        repeat(300) { n ->
            val j = rig.scope.launch {
                try {
                    val r = c.track("secret_name") { property("secret_key", "secret_value"); property("n", n) }
                    if (r is TrackResult.Success) success += r.eventId
                } catch (e: CancellationException) {
                    synchronized(lock) { cancelled++ }
                    throw e
                }
            }
            jobs += j
            val after = random.nextLong(0, 40)
            rig.scope.launch { delay(after); j.cancel() }
        }
        jobs.joinAll()
        val stored = sdk.dao.oldest(1000).map { it.eventId }
        assertEquals("no duplicate in the queue", stored.size, stored.toSet().size)
        assertTrue("every reported success is stored", stored.containsAll(success))
        assertTrue(stored.size in success.size..300)
        val warnings = log.warnings.toList()
        // Every event that committed although its caller was cancelled has a WARN; never more WARNs than cancellations.
        assertTrue("warnings ${warnings.size} < committed-but-cancelled ${stored.size - success.size}", warnings.size >= stored.size - success.size)
        assertTrue("warnings ${warnings.size} > cancelled $cancelled", warnings.size <= cancelled)
        assertNoContent(warnings)
        report("stress", "launched=300 reportedSuccess=${success.size} stored=${stored.size} cancelled=$cancelled warnings=${warnings.size}")
    }
}
