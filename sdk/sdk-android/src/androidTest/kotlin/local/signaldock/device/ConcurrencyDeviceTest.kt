package local.signaldock.device

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import local.signaldock.SignalDock
import local.signaldock.TrackResult
import local.signaldock.internal.scheduling.DeliveryWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Spec section 7 "Concurrency" on a real device: the real WorkManager Worker and the foreground trigger
 * share one sender. No queue loss, no duplicates in the queue, no two requests in flight.
 */
class ConcurrencyDeviceTest {
    private var rig: Rig? = null

    @After
    fun tearDown() { rig?.close() }

    private fun start(delayMillis: Long, foreground: Boolean, scheduler: local.signaldock.internal.scheduling.WorkScheduler? = local.signaldock.internal.scheduling.NoopScheduler): Pair<AckServer, SignalDock> {
        val server = AckServer(delayMillis).start()
        val r = Rig(server, scheduler = scheduler, foregroundTriggers = foreground).also { rig = it }
        return server to runBlocking { r.initialize() }
    }

    private val work get() = WorkManager.getInstance(targetContext)

    private fun state(id: java.util.UUID) = work.getWorkInfoById(id).get(10, TimeUnit.SECONDS)?.state

    private suspend fun trackN(sdk: SignalDock, n: Int, from: Int = 0): List<String> =
        (from until from + n).map { (sdk.track("e") { property("n", it) } as TrackResult.Success).eventId }

    @Test
    fun foreground_drains_and_the_real_Worker_contend_without_loss_or_resend() = runBlocking {
        val (server, sdk) = start(delayMillis = 400, foreground = true, scheduler = null) // real WorkManager scheduling too
        val ids = ArrayList<String>()
        ids += trackN(sdk, 20) // 20 events: the foreground trigger drains at once
        awaitTrue(30_000, "first foreground request in flight") { server.inFlight.get() == 1 }

        // Two Workers start while the foreground request is in flight. They must wait for the sender lock.
        val requests = List(2) { OneTimeWorkRequestBuilder<DeliveryWorker>().build() }
        requests.forEach { work.enqueue(it) }
        awaitTrue(30_000, "both Workers running") { requests.all { state(it.id) == WorkInfo.State.RUNNING || state(it.id) == WorkInfo.State.SUCCEEDED } }
        ids += trackN(sdk, 280, from = 20)

        awaitTrue(180_000, "queue drained") { runBlocking { sdk.dao.usage().count } == 0 }
        awaitTrue(30_000, "Workers finished") { requests.all { state(it.id) == WorkInfo.State.SUCCEEDED } }

        assertEquals("never two requests in flight", 1, server.maxInFlight.get())
        assertEquals(300, ids.toSet().size)
        assertEquals(ids.toSet(), server.committed.toSet())
        assertEquals("every event was sent exactly once", 300, server.receivedIds.size)
        assertEquals(0, server.duplicateAcks.get())
        assertEquals(0, sdk.diagnostics().retryFailureCount)
    }

    @Test
    fun an_interrupted_foreground_delivery_resumes_and_resends_the_same_events() = runBlocking {
        val (server, sdk) = start(delayMillis = 1_500, foreground = false)
        val ids = trackN(sdk, 120)
        val job = rig!!.scope.launch { sdk.delivery.drainForeground() }
        awaitTrue(30_000, "request in flight") { server.inFlight.get() == 1 }
        job.cancel()
        job.join()

        // The ACK was never applied: nothing was removed or changed, and cancellation is not a failure.
        assertEquals(120, sdk.dao.usage().count)
        assertEquals(ids, sdk.dao.oldest(200).map { it.eventId })
        assertEquals(0, sdk.diagnostics().retryFailureCount)
        awaitTrue(30_000, "server finished the abandoned request") { server.inFlight.get() == 0 }
        assertEquals(50, server.committed.size) // the server committed that batch although the client gave up

        server.delay = 0
        sdk.delivery.drainForeground()
        assertEquals(0, sdk.dao.usage().count)
        assertEquals(ids.toSet(), server.committed.toSet())
        assertEquals("the abandoned batch came back as duplicate", 50, server.duplicateAcks.get())
        assertEquals(120 + 50, server.receivedIds.size)
    }

    @Test
    fun a_lost_ACK_keeps_the_batch_and_the_resend_carries_the_same_bytes() = runBlocking {
        val (server, sdk) = start(delayMillis = 0, foreground = false)
        val ids = trackN(sdk, 120)
        server.mode = AckServer.Mode.DROP_AFTER_COMMIT
        sdk.delivery.sender.run() // first batch committed on the server, response lost
        assertEquals(120, sdk.dao.usage().count)
        assertEquals(1, sdk.diagnostics().retryFailureCount)
        assertEquals(50, server.committed.size)

        server.mode = AckServer.Mode.OK
        Thread.sleep(250) // jitter is 0 in this rig, the stored time is already due; this only keeps clocks apart
        sdk.delivery.sender.run()
        assertEquals(0, sdk.dao.usage().count)
        assertEquals(ids.toSet(), server.committed.toSet())
        assertTrue(server.duplicateAcks.get() >= 50)
        // Every body that carried the first batch is byte-identical (same IDs and content, ADR-0006 decision 3).
        val firstBatchBodies = server.bodies.filter { eventIdOf(eventPayloads(it).first()) == ids.first() }
        assertTrue(firstBatchBodies.size >= 2)
        assertEquals(1, firstBatchBodies.toSet().size)
        assertEquals(0, sdk.diagnostics().retryFailureCount) // reset after the valid ACK was applied
    }

    @Test
    fun a_Worker_stopped_by_WorkManager_leaves_the_queue_intact_and_the_next_Worker_finishes() = runBlocking {
        val (server, sdk) = start(delayMillis = 1_500, foreground = false)
        val ids = trackN(sdk, 100)
        val first = OneTimeWorkRequestBuilder<DeliveryWorker>().build()
        work.enqueue(first)
        awaitTrue(30_000, "Worker request in flight") { server.inFlight.get() == 1 }
        work.cancelWorkById(first.id).result.get(10, TimeUnit.SECONDS)
        awaitTrue(30_000, "Worker cancelled") { state(first.id) == WorkInfo.State.CANCELLED }
        assertEquals(100, sdk.dao.usage().count)
        awaitTrue(30_000, "server idle") { server.inFlight.get() == 0 }

        server.delay = 0
        val second = OneTimeWorkRequestBuilder<DeliveryWorker>().build()
        work.enqueue(second)
        awaitTrue(120_000, "second Worker drained the queue") { runBlocking { sdk.dao.usage().count } == 0 }
        awaitTrue(30_000, "second Worker finished") { state(second.id) == WorkInfo.State.SUCCEEDED }
        assertEquals(ids.toSet(), server.committed.toSet())
        assertEquals(1, server.maxInFlight.get())
        assertEquals(50, server.duplicateAcks.get())
    }
}
