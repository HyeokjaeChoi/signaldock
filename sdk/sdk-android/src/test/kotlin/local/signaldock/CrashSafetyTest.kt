package local.signaldock

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.RoomDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import local.signaldock.internal.collection.EventCollector
import local.signaldock.internal.delivery.BatchHttpRequest
import local.signaldock.internal.init.InitCoordinator
import local.signaldock.internal.init.createSdk
import local.signaldock.internal.scheduling.NoopScheduler
import local.signaldock.internal.scheduling.WorkManagerScheduler
import local.signaldock.internal.storage.AddOutcome
import local.signaldock.internal.storage.EventEntity
import local.signaldock.internal.storage.EventSink
import local.signaldock.internal.storage.OptOutStore
import local.signaldock.internal.storage.QueueDao
import local.signaldock.internal.storage.QueueLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Issue 33 item 1 and ADR-0017: the public API does not throw into the host app. Each failure class
 * ends in the result that spec sections 3, 4 and 8.B define; only caller cancellation propagates.
 */
@RunWith(RobolectricTestRunner::class)
class CrashSafetyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The Room database behind a DAO. Robolectric runs the real SQLite, so closing it is a real closed database. */
    private fun roomOf(dao: QueueDao): RoomDatabase =
        dao.javaClass.getDeclaredField("__db").apply { isAccessible = true }.get(dao) as RoomDatabase

    private fun collector(sink: EventSink) =
        EventCollector("inst", sink, FakeClock(), SequenceIds(), QueueLimits(), FakeLogger(), OptOutStore(kotlin.io.path.createTempDirectory().toFile()))

    // ---- invalid input (spec 3, Q63, Q64, Q85) ------------------------------------------------

    @Test
    fun `invalid names, keys and values return Invalid with the defined reason and store nothing`() = runBlocking {
        val sdk = newSdk()
        val cases: List<Pair<InvalidReason, suspend () -> TrackResult>> = listOf(
            InvalidReason.EMPTY_NAME to { sdk.track("") },
            InvalidReason.EMPTY_NAME to { sdk.track(" \t") },
            InvalidReason.LEADING_OR_TRAILING_WHITESPACE to { sdk.track("a ") },
            InvalidReason.FORBIDDEN_CHARACTER to { sdk.track("a\u0000") },
            InvalidReason.FORBIDDEN_CHARACTER to { sdk.track("a\uD800") },
            InvalidReason.FORBIDDEN_CHARACTER to { sdk.track("e") { property("v", "x\uDC00") } },
            InvalidReason.DUPLICATE_KEY to { sdk.track("e") { property("k", 1); property("k", 1) } },
            InvalidReason.NON_FINITE_NUMBER to { sdk.track("e") { property("n", Double.NaN) } },
            InvalidReason.NON_FINITE_NUMBER to { sdk.track("e") { property("n", Double.POSITIVE_INFINITY) } },
            InvalidReason.NUMBER_OUT_OF_RANGE to { sdk.track("e") { property("n", Long.MIN_VALUE) } },
            InvalidReason.NUMBER_OUT_OF_RANGE to { sdk.track("e") { property("n", Long.MAX_VALUE) } },
            InvalidReason.NUMBER_OUT_OF_RANGE to { sdk.track("e") { property("n", 1e300) } },
            InvalidReason.EVENT_TOO_LARGE to { sdk.track("e") { property("big", "x".repeat(20_000)) } },
        )
        for ((reason, call) in cases) {
            val r = try { call() } catch (t: Throwable) { fail("threw $t for $reason"); return@runBlocking }
            assertTrue("$reason: $r", r is TrackResult.Invalid && r.reason == reason)
        }
        assertEquals(0, sdk.diagnostics().queuedEvents)
    }

    // ---- storage errors (spec 3 "expected storage failures", spec 4 queue limits) --------------

    @Test
    fun `a closed database gives StorageError from track and an unavailable snapshot from diagnostics`() = runBlocking {
        val log = FakeLogger()
        val sdk = newSdk(logger = log)
        assertTrue(sdk.track("before") is TrackResult.Success)
        roomOf(sdk.dao).close()
        val r = sdk.track("after")
        assertTrue("expected StorageError but was $r", r is TrackResult.StorageError)
        val d = sdk.diagnostics()
        assertFalse(d.storageAvailable)
        assertEquals(0, d.queuedEvents)
        // The delivery paths on the closed database end without an exception too.
        sdk.delivery.drainForeground()
        assertEquals(1, log.warnings.count { it.startsWith("delivery run failed:") })
        sdk.delivery.runWorker()
        assertEquals(2, log.warnings.count { it.startsWith("delivery run failed:") })
    }

    @Test
    fun `whatever the storage layer throws becomes StorageError, and only cancellation propagates`() = runBlocking {
        val thrown = listOf(
            SQLiteFullException("full"), SQLiteDiskIOException("io"), SQLiteCantOpenDatabaseException("open"),
            SQLiteException("other"), IOException("io"), IllegalStateException("closed"),
            SecurityException("denied"), IllegalArgumentException("bad"), RuntimeException("x"),
            // Room cancels its own scope when it is closed: the caller is still active (ADR-0017).
            CancellationException("room closed"),
        )
        for (t in thrown) {
            val sink = object : EventSink {
                override suspend fun add(event: EventEntity, limits: QueueLimits): AddOutcome = throw t
            }
            assertEquals(TrackResult.StorageError(t.javaClass.simpleName), collector(sink).track("e"))
        }
        val cancelling = object : EventSink {
            override suspend fun add(event: EventEntity, limits: QueueLimits): AddOutcome {
                currentCoroutineContext().cancel() // the caller is cancelled while storing
                throw CancellationException("c")
            }
        }
        try {
            async { collector(cancelling).track("e") }.await()
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            // expected (spec 3: cancellation is not converted)
        }
    }

    @Test
    fun `a fatal Error from storage still propagates from track (NoClassDefFoundError stays a crash, Q110)`() = runBlocking {
        val sink = object : EventSink {
            override suspend fun add(event: EventEntity, limits: QueueLimits): AddOutcome = throw NoClassDefFoundError("simulated")
        }
        try {
            collector(sink).track("e")
            fail("an Error must propagate")
        } catch (e: NoClassDefFoundError) {
            // expected; the same rule for initialize() is in InitContractTest
        }
    }

    @Test
    fun `a queue at its limit returns QueueFull, not an exception`() = runBlocking {
        val sdk = newSdk(limits = QueueLimits(maxEvents = 1))
        assertTrue(sdk.track("a") is TrackResult.Success)
        assertEquals(TrackResult.QueueFull, sdk.track("b"))
    }

    // ---- scheduling storage errors never reach the host ---------------------------------------

    @Test
    fun `a failing WorkManager does not fail initialization or track`() = runBlocking {
        val log = FakeLogger()
        val scheduler = WorkManagerScheduler(context, FakeClock(), log, workManager = { throw SQLiteException("work db broken") })
        val sdk = newSdk(scheduler = scheduler) // initialization ensures the periodic Worker
        assertTrue(sdk.track("e") is TrackResult.Success)
        scheduler.requestAt(0)
        scheduler.ensurePeriodic()
        assertEquals(1, sdk.diagnostics().queuedEvents)
        assertTrue(log.warnings.isNotEmpty())
    }

    // ---- network errors (spec 4 and 8.A: preserved, no exception) ------------------------------

    @Test
    fun `transport failures end delivery without an exception and keep the queue`() = runBlocking {
        val failures: List<suspend (BatchHttpRequest) -> Throwable?> = listOf(
            { IOException("reset") }, { java.net.SocketTimeoutException("t") }, { java.net.UnknownHostException("h") },
            { IllegalStateException("closed") }, { RuntimeException("surprise") },
        )
        // One id source for all cases: every case opens the same queue, so each event needs a new id.
        val ids = SequenceIds()
        for ((i, f) in failures.withIndex()) {
            val transport = FakeTransport { throw f(it)!! }
            val sdk = newSdk(transport = transport, ids = ids)
            assertTrue(sdk.track("e") is TrackResult.Success)
            sdk.delivery.drainForeground()
            sdk.delivery.runWorker()
            assertEquals(i + 1, sdk.diagnostics().queuedEvents)
        }
    }

    // ---- initialization (spec 3, Q110 to Q114, Q118) ------------------------------------------

    @Test
    fun `initialize returns results for invalid, conflicting and storage-failing calls`() = runBlocking {
        val co = InitCoordinator(scope) { c, cfg -> createSdk(c, cfg, foregroundTriggers = false, scheduler = NoopScheduler) }
        assertTrue((co.initialize(context, testConfig.copy(endpoint = "not a url")) as InitResult.Failure).error is InitError.InvalidConfiguration)
        assertTrue((co.initialize(context, testConfig.copy(collectionKey = " ")) as InitResult.Failure).error is InitError.InvalidConfiguration)
        assertTrue(co.initialize(context, testConfig) is InitResult.Success)
        assertEquals(InitResult.Failure(InitError.ConfigurationConflict), co.initialize(context, testConfig.copy(collectionKey = "other")))
        val broken = InitCoordinator(scope) { _, _ -> throw SQLiteFullException("full") }
        assertEquals(InitResult.Failure(InitError.StorageFailure("SQLiteFullException")), broken.initialize(context, testConfig))
    }

    // ---- many threads (spec 4: Mutex, atomic limit check and insert) --------------------------

    @Test
    fun `track, diagnostics and delivery from many threads return only defined results and lose nothing`() {
        val sdk = runBlocking { newSdk(limits = QueueLimits(maxEvents = 600)) }
        val errors = CopyOnWriteArrayList<Throwable>()
        val results = CopyOnWriteArrayList<TrackResult>()
        val workers = (0 until 8).map { n ->
            thread {
                try {
                    runBlocking {
                        repeat(100) { i ->
                            results += when (i % 4) {
                                0 -> sdk.track("") // Invalid
                                else -> sdk.track("e$n") { property("i", i) }
                            }
                            if (i % 10 == 0) sdk.diagnostics()
                        }
                    }
                } catch (t: Throwable) { errors += t }
            }
        }
        workers.forEach { it.join(60_000) }
        assertTrue("threw: $errors", errors.isEmpty())
        assertEquals(800, results.size)
        assertTrue(results.all { it is TrackResult.Success || it is TrackResult.Invalid || it === TrackResult.QueueFull })
        assertEquals(200, results.count { it is TrackResult.Invalid })
        assertEquals(results.count { it is TrackResult.Success }, runBlocking { sdk.diagnostics().queuedEvents })
        assertEquals(600, results.count { it is TrackResult.Success }) // the limit is exact, never exceeded
    }

    @Test
    fun `closing the database during many concurrent calls throws nothing`() = runBlocking {
        val sdk = newSdk()
        val closer = thread { Thread.sleep(20); roomOf(sdk.dao).close() }
        val all = (0 until 8).map { n ->
            async(Dispatchers.Default) {
                (0 until 100).map { i ->
                    sdk.track("e$n") { property("i", i) }.also { sdk.diagnostics() }
                }
            }
        }.awaitAll().flatten()
        closer.join()
        assertTrue(all.all { it is TrackResult.Success || it is TrackResult.StorageError })
        assertNotNull(sdk.diagnostics())
    }

    @Test
    fun `concurrent initialize calls with different configurations return one winner and conflicts`() = runBlocking {
        val co = InitCoordinator(scope) { c, cfg -> createSdk(c, cfg, foregroundTriggers = false, scheduler = NoopScheduler) }
        val rs = (0 until 16).map { i ->
            async(Dispatchers.Default) { co.initialize(context, testConfig.copy(collectionKey = "key-${i % 2}")) }
        }.awaitAll()
        val ok = rs.filterIsInstance<InitResult.Success>()
        assertTrue(ok.isNotEmpty())
        assertEquals(1, ok.map { it.sdk }.toSet().size)
        assertTrue(rs.all { it is InitResult.Success || it == InitResult.Failure(InitError.ConfigurationConflict) })
    }
}
