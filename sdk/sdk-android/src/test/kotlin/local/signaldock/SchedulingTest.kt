package local.signaldock

import android.app.Activity
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import local.signaldock.internal.delivery.Jitter
import local.signaldock.internal.init.InitCoordinator
import local.signaldock.internal.scheduling.DeliveryWorker
import local.signaldock.internal.scheduling.ForegroundExitObserver
import local.signaldock.internal.scheduling.ForegroundTrigger
import local.signaldock.internal.scheduling.ONE_TIME_WORK_NAME
import local.signaldock.internal.scheduling.PERIODIC_WORK_NAME
import local.signaldock.internal.scheduling.WorkManagerScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

private const val T0 = 1_760_000_000_123L

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingTest {
    private val clock = FakeClock(T0)
    private val transport = FakeTransport()
    private val scheduler = RecordingScheduler()
    private val original = InitCoordinator.default

    @After
    fun restore() {
        InitCoordinator.default = original
        DeliveryWorker.initWaitMillis = 15_000L
    }

    private fun worker() = TestListenableWorkerBuilder<DeliveryWorker>(context).build() as DeliveryWorker

    // ---------- Worker ----------

    @Test
    fun `the Worker and a foreground drain share one sender - one request in flight and no resend`() = runBlocking {
        InitCoordinator.default = InitCoordinator(CoroutineScope(Job())) { _, _ ->
            newSdk(clock = clock, transport = transport, scheduler = scheduler)
        }
        val sdk = (InitCoordinator.default.initialize(context, testConfig) as InitResult.Success).sdk
        repeat(10) { sdk.track("e") { property("n", it) } }
        transport.gate = CompletableDeferred()

        val foreground = async(Dispatchers.Default) { sdk.delivery.drainForeground() }
        withTimeout(5_000) { while (transport.requests.isEmpty()) delay(5) }
        val background = async(Dispatchers.Default) { worker().doWork() }
        delay(200)
        assertEquals(1, transport.inFlight.get())
        transport.gate!!.complete(Unit)
        foreground.await()
        assertEquals(ListenableWorker.Result.success(), background.await())

        assertEquals(1, transport.maxInFlight.get())
        assertEquals(1, transport.requests.size)
        assertEquals(0, sdk.diagnostics().queuedEvents)
        assertEquals(1, scheduler.started.get())
    }

    @Test
    fun `the Worker sends a restored queue and ends with success`() = runBlocking {
        InitCoordinator.default = InitCoordinator(CoroutineScope(Job())) { _, _ ->
            newSdk(clock = clock, transport = transport, scheduler = scheduler)
        }
        val sdk = (InitCoordinator.default.initialize(context, testConfig) as InitResult.Success).sdk
        repeat(5) { sdk.track("e") }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, sdk.diagnostics().queuedEvents)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `the Worker ends without HTTP when the SDK was never initialized`() = runBlocking {
        InitCoordinator.default = InitCoordinator(CoroutineScope(Job())) { _, _ -> newSdk(clock = clock, transport = transport) }
        DeliveryWorker.initWaitMillis = 100
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `a Worker run that must wait schedules the next run for the persisted time and does not sleep`() = runBlocking {
        val sdk = newSdk(clock = clock, transport = transport, scheduler = scheduler, jitter = Jitter { 5_000 })
        sdk.track("e")
        transport.handler = { http(503) }
        sdk.delivery.runWorker()
        val next = sdk.diagnostics().nextAttemptEpochMillis!!
        assertEquals(T0 + 5_000, next)
        assertEquals(next, scheduler.requests.last())
        sdk.delivery.runWorker() // woken early: the sender refuses, the SDK's own time is not advanced
        assertEquals(1, transport.requests.size)
        assertEquals(next, sdk.diagnostics().nextAttemptEpochMillis)
    }

    @Test
    fun `a Worker run stops after 10 batches and hands the rest to the next run`() = runBlocking {
        val sdk = newSdk(clock = clock, transport = transport, scheduler = scheduler)
        repeat(600) { sdk.track("e") }
        scheduler.requests.clear()
        sdk.delivery.runWorker()
        assertEquals(10, transport.requests.size)
        assertEquals(100, sdk.diagnostics().queuedEvents)
        assertEquals(listOf(T0), scheduler.requests)
        sdk.delivery.runWorker()
        assertEquals(0, sdk.diagnostics().queuedEvents)
    }

    // ---------- scheduling ----------

    @Test
    fun `initialization ensures the periodic Worker and queue additions request a one-time run after the foreground delay`() = runBlocking {
        val sdk = newSdk(clock = clock, scheduler = scheduler)
        assertTrue(scheduler.periodic.get() >= 1)
        assertEquals(listOf(T0 + 10_000), scheduler.requests)
        sdk.track("e")
        assertEquals(T0 + 10_000, scheduler.requests.last())

        // A restored queue is sent without waiting.
        val restored = RecordingScheduler()
        newSdk(clock = clock, scheduler = restored)
        assertEquals(listOf(T0), restored.requests)
    }

    @Test
    fun `WorkManager - unique periodic and one-time work with a network constraint, without duplicate pending requests`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val wm = WorkManager.getInstance(context)
        val s = WorkManagerScheduler(context, clock, FakeLogger())
        s.ensurePeriodic()
        s.ensurePeriodic()
        val periodic = wm.getWorkInfosForUniqueWork(PERIODIC_WORK_NAME).get()
        assertEquals(1, periodic.size)
        assertEquals(NetworkType.CONNECTED, periodic.single().constraints.requiredNetworkType)

        s.requestAt(T0 + 10_000)
        s.requestAt(T0 + 20_000) // an earlier pending request covers it
        assertEquals(1, wm.getWorkInfosForUniqueWork(ONE_TIME_WORK_NAME).get().size)
        assertEquals(NetworkType.CONNECTED, wm.getWorkInfosForUniqueWork(ONE_TIME_WORK_NAME).get().single().constraints.requiredNetworkType)
        s.requestAt(T0 + 1_000) // earlier than the pending one: a new request
        assertEquals(2, wm.getWorkInfosForUniqueWork(ONE_TIME_WORK_NAME).get().size)
        s.workerStarted()
        s.requestAt(T0 + 30_000)
        assertEquals(3, wm.getWorkInfosForUniqueWork(ONE_TIME_WORK_NAME).get().size)
    }

    @Test
    fun `leaving the foreground schedules the Worker`() = runBlocking {
        val sdk = newSdk(clock = clock, scheduler = scheduler)
        val observer = ForegroundExitObserver(clock, sdk.delivery)
        val a = Robolectric.buildActivity(Activity::class.java).create().get()
        val b = Robolectric.buildActivity(Activity::class.java).create().get()
        val periodicBefore = scheduler.periodic.get()
        val requestsBefore = scheduler.requests.size
        observer.onActivityStarted(a)
        observer.onActivityStarted(b)
        observer.onActivityStopped(a) // another activity is still started
        assertEquals(requestsBefore, scheduler.requests.size)
        observer.onActivityStopped(b)
        assertEquals(periodicBefore + 1, scheduler.periodic.get())
        assertEquals(requestsBefore + 1, scheduler.requests.size)
        assertEquals(T0, scheduler.requests.last())
    }

    // ---------- foreground trigger ----------

    @Test
    fun `foreground trigger - 20 stored events start a drain at once`() = runTest {
        val drains = AtomicInteger()
        val trigger = ForegroundTrigger(CoroutineScope(StandardTestDispatcher(testScheduler))) { drains.incrementAndGet() }
        repeat(19) { trigger.onEventStored() }
        runCurrent()
        assertEquals(0, drains.get())
        trigger.onEventStored()
        runCurrent()
        assertEquals(1, drains.get())
        advanceTimeBy(60_000) // the 10 s timer of the first batch of events was cancelled
        assertEquals(1, drains.get())
    }

    @Test
    fun `foreground trigger - 10 seconds after the first stored event`() = runTest {
        val drains = AtomicInteger()
        val trigger = ForegroundTrigger(CoroutineScope(StandardTestDispatcher(testScheduler))) { drains.incrementAndGet() }
        trigger.onEventStored()
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(0, drains.get())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, drains.get())
        trigger.onEventStored() // a new window starts
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, drains.get())
    }

    @Test
    fun `foreground trigger - one drain at a time, requests during a drain cause one more drain`() = runTest {
        val drains = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val trigger = ForegroundTrigger(CoroutineScope(StandardTestDispatcher(testScheduler))) {
            if (drains.incrementAndGet() == 1) gate.await()
        }
        repeat(20) { trigger.onEventStored() }
        runCurrent()
        assertEquals(1, drains.get())
        repeat(60) { trigger.onEventStored() } // three more thresholds while the first drain runs
        runCurrent()
        assertEquals(1, drains.get())
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, drains.get())
    }

    @Test
    fun `a foreground trigger respects the persisted retry wait`() = runBlocking {
        val sdk = newSdk(clock = clock, transport = transport, scheduler = scheduler, jitter = Jitter { b -> b })
        sdk.track("e")
        transport.handler = { http(503) }
        sdk.delivery.drainForeground()
        repeat(5) { sdk.delivery.drainForeground() } // more triggers during the wait
        assertEquals(1, transport.requests.size)
    }
}
