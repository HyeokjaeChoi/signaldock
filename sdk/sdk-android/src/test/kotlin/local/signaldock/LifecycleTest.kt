package local.signaldock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import local.signaldock.internal.storage.AddOutcome
import local.signaldock.internal.init.Clock
import local.signaldock.internal.collection.EventCollector
import local.signaldock.internal.storage.EventEntity
import local.signaldock.internal.storage.EventSink
import local.signaldock.internal.storage.OptOutStore
import local.signaldock.internal.init.InitCoordinator
import local.signaldock.internal.storage.QueueLimits
import local.signaldock.internal.init.createSdk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class LifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Counting(val gate: CompletableDeferred<Unit>? = null, val failFirst: Int = 0) {
        val builds = AtomicInteger()
        val build: suspend (android.content.Context, SignalDockConfig) -> SignalDock = { c, cfg ->
            val n = builds.incrementAndGet()
            gate?.await()
            if (n <= failFirst) throw IOException("disk")
            createSdk(c, cfg)
        }
    }

    private fun coordinator(c: Counting) = InitCoordinator(scope, c.build)

    @Test
    fun `concurrent initialize with the same config shares one attempt and one instance`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val c = Counting(gate)
        val co = coordinator(c)
        val calls = (1..20).map { scope.async { co.initialize(context, testConfig) } }
        gate.complete(Unit)
        val results = calls.awaitAll()
        assertEquals(1, c.builds.get())
        val sdks = results.map { (it as InitResult.Success).sdk }
        assertTrue(sdks.all { it === sdks[0] })
        // later same-config call reuses the instance without a new attempt
        assertSame(sdks[0], (co.initialize(context, testConfig) as InitResult.Success).sdk)
        assertEquals(1, c.builds.get())
    }

    @Test
    fun `cancelling a waiter does not cancel the shared attempt`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val c = Counting(gate)
        val co = coordinator(c)
        val waiter = scope.launch { co.initialize(context, testConfig) }
        val survivor = scope.async { co.initialize(context, testConfig) }
        while (c.builds.get() == 0) kotlinx.coroutines.yield()
        waiter.cancelAndJoin()
        gate.complete(Unit)
        assertTrue(withTimeout(30.seconds) { survivor.await() } is InitResult.Success)
        // even with every waiter cancelled, the attempt finishes and the next call reuses it
        val gate2 = CompletableDeferred<Unit>()
        val c2 = Counting(gate2)
        val co2 = coordinator(c2)
        val only = scope.launch { co2.initialize(context, testConfig) }
        while (c2.builds.get() == 0) kotlinx.coroutines.yield()
        only.cancelAndJoin()
        gate2.complete(Unit)
        val later = withTimeout(30.seconds) { co2.initialize(context, testConfig) }
        assertTrue(later is InitResult.Success)
        assertEquals(1, c2.builds.get())
    }

    @Test
    fun `a different config is a conflict during the attempt and after success`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val c = Counting(gate)
        val co = coordinator(c)
        val first = scope.async { co.initialize(context, testConfig) }
        while (c.builds.get() == 0) kotlinx.coroutines.yield()
        val other = testConfig.copy(collectionKey = "other")
        assertEquals(InitResult.Failure(InitError.ConfigurationConflict), co.initialize(context, other))
        gate.complete(Unit)
        val ok = first.await() as InitResult.Success
        assertEquals(InitResult.Failure(InitError.ConfigurationConflict), co.initialize(context, other))
        // the existing instance is not discarded by the failed reinitialization
        assertSame(ok.sdk, (co.initialize(context, testConfig) as InitResult.Success).sdk)
        assertEquals(1, c.builds.get())
    }

    @Test
    fun `storage failure is shared, not retried automatically, and retried by a new call keeping the queue`() = runBlocking {
        val c = Counting(failFirst = 1)
        val co = coordinator(c)
        // seed a queue and installation id that must survive the failed attempt
        val seed = newSdk(); seed.track("kept")
        val r1 = co.initialize(context, testConfig)
        assertEquals(InitResult.Failure(InitError.StorageFailure("IOException")), r1)
        assertEquals(1, c.builds.get())
        val r2 = co.initialize(context, testConfig)
        val sdk = (r2 as InitResult.Success).sdk
        assertEquals(2, c.builds.get())
        assertEquals(1, sdk.diagnostics().queuedEvents)
    }

    @Test
    fun `unexpected exception during storage creation returns a failure result and allows retry`() = runBlocking {
        val calls = AtomicInteger()
        val co = InitCoordinator(scope) { c, cfg ->
            if (calls.incrementAndGet() == 1) throw SecurityException("denied") else createSdk(c, cfg)
        }
        assertEquals(InitResult.Failure(InitError.StorageFailure("SecurityException")), co.initialize(context, testConfig))
        assertTrue(co.initialize(context, testConfig) is InitResult.Success)
    }

    @Test
    fun `invalid configuration fails without creating state`() = runBlocking {
        val c = Counting()
        val co = coordinator(c)
        val bad = listOf(
            testConfig.copy(collectionKey = " "),
            testConfig.copy(endpoint = "not a url"),
            testConfig.copy(endpoint = "ftp://host/"),
            testConfig.copy(endpoint = "http:///path"),
            testConfig.copy(callTimeout = Duration.ZERO),
            testConfig.copy(connectTimeout = (-1).seconds),
            testConfig.copy(readTimeout = Duration.INFINITE),
            testConfig.copy(writeTimeout = Duration.parse("30d")),
        )
        for (b in bad) assertTrue("$b", co.initialize(context, b) is InitResult.Failure && (co.initialize(context, b) as InitResult.Failure).error is InitError.InvalidConfiguration)
        assertEquals(0, c.builds.get())
        assertTrue(co.initialize(context, testConfig.copy(callTimeout = 5.seconds, connectTimeout = 1.seconds)) is InitResult.Success)
    }

    @Test
    fun `https only unless allowInsecureEndpoint is set (ADR-0035)`() = runBlocking {
        val c = Counting()
        val co = coordinator(c)
        suspend fun message(cfg: SignalDockConfig) =
            ((co.initialize(context, cfg) as InitResult.Failure).error as InitError.InvalidConfiguration).message
        val http = testConfig.copy(endpoint = "http://example.com", allowInsecureEndpoint = false)
        assertTrue(message(http).contains("allowInsecureEndpoint"))
        assertFalse(message(http.copy(endpoint = "HTTP://example.com")).isBlank())
        assertFalse(message(http).contains(http.collectionKey))
        assertTrue(message(testConfig.copy(endpoint = "ftp://example.com")).contains("https"))
        assertTrue(message(testConfig.copy(endpoint = "not a url")).isNotBlank())
        assertEquals(0, c.builds.get())
        assertTrue(co.initialize(context, testConfig.copy(endpoint = "HTTPS://example.com", allowInsecureEndpoint = false)) is InitResult.Success)
    }

    @Test
    fun `the default of allowInsecureEndpoint is false`() = runBlocking {
        val cfg = SignalDockConfig(endpoint = "http://10.0.2.2:8080", collectionKey = "k")
        assertFalse(cfg.allowInsecureEndpoint)
        val c = Counting()
        val r = coordinator(c).initialize(context, cfg)
        assertTrue(r is InitResult.Failure && r.error is InitError.InvalidConfiguration)
        assertEquals(0, c.builds.get())
    }

    @Test
    fun `http with allowInsecureEndpoint is accepted`() = runBlocking {
        val co = coordinator(Counting())
        val cfg = SignalDockConfig(endpoint = "http://10.0.2.2:8080", collectionKey = "k", allowInsecureEndpoint = true)
        assertTrue(co.initialize(context, cfg) is InitResult.Success)
    }

    @Test
    fun `config toString hides the collection key`() {
        assertFalse(testConfig.toString().contains("test-key"))
    }

    @Test
    fun `public initialize then track then diagnostics`() = runBlocking {
        // The only test that uses the process-wide coordinator.
        val sdk = (SignalDock.initialize(context, testConfig) as InitResult.Success).sdk
        assertSame(sdk, (SignalDock.initialize(context, testConfig) as InitResult.Success).sdk)
        assertTrue(sdk.track("product_viewed") { property("product_id", "P-1") } is TrackResult.Success)
        assertEquals(1, sdk.diagnostics().queuedEvents)
    }

    // --- cancellation of track ---

    private class RecordingSink(val onAdd: suspend () -> Unit) : EventSink {
        val stored = mutableListOf<EventEntity>()
        override suspend fun add(event: EventEntity, limits: QueueLimits): AddOutcome {
            stored += event // the commit happened
            onAdd()
            return AddOutcome.ADDED
        }
    }

    private fun collector(sink: EventSink, log: FakeLogger) =
        EventCollector("inst", sink, Clock { 0 }, SequenceIds(), QueueLimits(), log, OptOutStore(kotlin.io.path.createTempDirectory().toFile()))

    @Test
    fun `cancellation during storage propagates and warns without content`() = runBlocking {
        val log = FakeLogger()
        val started = CompletableDeferred<Unit>()
        val sink = RecordingSink { started.complete(Unit); awaitCancellation() }
        val caught = CompletableDeferred<Throwable>()
        val j = scope.launch {
            try { collector(sink, log).track("secret_name") { property("secret_key", "secret_value") } }
            catch (e: Throwable) { caught.complete(e); throw e }
        }
        started.await()
        j.cancelAndJoin()
        assertTrue(caught.await() is kotlinx.coroutines.CancellationException)
        assertEquals(1, sink.stored.size) // the event may be committed; it stays queued
        assertEquals(1, log.warnings.size)
        assertFalse(log.warnings.single().contains("secret"))
    }

    @Test
    fun `cancellation before storage does not warn and stores nothing`() = runBlocking {
        val log = FakeLogger()
        val sink = RecordingSink { }
        val j = scope.launch {
            collector(sink, log).track("e") { this@launch.coroutineContext.job.cancel() }
        }
        j.join()
        assertTrue(j.isCancelled)
        assertEquals(0, sink.stored.size)
        assertEquals(0, log.warnings.size)
    }

    @Test
    fun `expected storage failure becomes a result, not an exception`() = runBlocking {
        val log = FakeLogger()
        val sink = object : EventSink {
            override suspend fun add(event: EventEntity, limits: QueueLimits): AddOutcome = throw IOException("disk full /secret/path")
        }
        val r = collector(sink, log).track("e")
        assertEquals(TrackResult.StorageError("IOException"), r)
        assertFalse(log.warnings.single().contains("secret"))
    }
}
