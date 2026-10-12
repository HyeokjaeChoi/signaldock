package local.signaldock.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import local.signaldock.InvalidReason
import local.signaldock.SignalDock
import local.signaldock.TrackResult
import local.signaldock.internal.delivery.MAX_BATCH_BYTES
import local.signaldock.internal.delivery.RunBudget
import local.signaldock.internal.delivery.buildBatch
import local.signaldock.internal.storage.EventEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec section 4 and section 7 "Capacity boundaries" on a real device: real Room/SQLite, real OkHttp. */
class CapacityDeviceTest {
    private lateinit var server: AckServer
    private lateinit var rig: Rig
    private lateinit var sdk: SignalDock

    private val kib = 1024

    @Before
    fun setUp() {
        server = AckServer().start()
        rig = Rig(server)
        sdk = runBlocking { rig.initialize() }
    }

    @After
    fun tearDown() = rig.close()

    /** Property value that makes one event exactly [target] bytes of transport JSON. */
    private suspend fun padFor(target: Int): String {
        sdk.track("e") { property("k", "") }
        val base = sdk.dao.oldest(1).single().sizeBytes
        sdk.dao.deleteByIds(listOf(sdk.dao.oldest(1).single().eventId))
        return "x".repeat(target - base)
    }

    @Test
    fun an_event_of_exactly_16_KiB_is_stored_and_one_more_byte_is_refused() = runBlocking {
        val pad = padFor(16 * kib)
        assertTrue(sdk.track("e") { property("k", pad) } is TrackResult.Success)
        assertEquals(16 * kib, sdk.dao.oldest(1).single().sizeBytes)
        val r = sdk.track("e") { property("k", pad + "x") }
        assertEquals(TrackResult.Invalid(InvalidReason.EVENT_TOO_LARGE, null), r)
        assertEquals(1, sdk.dao.usage().count)
    }

    @Test
    fun a_batch_is_cut_at_256_KiB_and_50_events_and_the_exact_limit_fits() = runBlocking {
        val big = padFor(16 * kib)
        repeat(40) { assertTrue(sdk.track("e") { property("k", big) } is TrackResult.Success) }
        repeat(120) { assertTrue(sdk.track("small") { property("n", it) } is TrackResult.Success) }
        val ids = sdk.dao.oldest(1000).map { it.eventId }
        assertEquals(160, ids.size)

        // Pure boundary on real stored rows: the exact limit fits, one byte less drops the last event.
        val rows = sdk.dao.oldest(50)
        val whole = buildBatch(rows, 50, Int.MAX_VALUE)
        assertEquals(rows.size, buildBatch(rows, 50, whole.body.size).events.size)
        assertEquals(rows.size - 1, buildBatch(rows, 50, whole.body.size - 1).events.size)

        // End to end: every request body stays within 256 KiB and 50 events; nothing is lost.
        sdk.delivery.sender.run()
        assertEquals(0, sdk.dao.usage().count)
        assertTrue(server.bodies.all { it.toByteArray(Charsets.UTF_8).size <= MAX_BATCH_BYTES })
        assertTrue(server.bodies.all { eventPayloads(it).size <= 50 })
        // 16 KiB events (wrapper included in that size): 16 events fit in 256 KiB because the batch counts the
        // wrapper once; a 17th would not. The first body is the 16 big events and is within one event of the limit.
        val first = server.bodies.first()
        assertEquals(16, eventPayloads(first).size)
        assertTrue(first.toByteArray(Charsets.UTF_8).size > MAX_BATCH_BYTES - 16 * kib)
        assertEquals(ids, server.receivedIds.toList())
    }

    @Test
    fun the_queue_holds_10000_events_then_QueueFull_and_space_returns_after_delivery() = runBlocking {
        repeat(10_000) { assertTrue("track #$it", sdk.track("e") { property("n", it) } is TrackResult.Success) }
        assertEquals(10_000, sdk.dao.usage().count)
        assertEquals(TrackResult.QueueFull, sdk.track("overflow"))
        assertEquals(10_000, sdk.dao.usage().count) // existing events are kept

        // One acknowledged batch frees exactly 50 places.
        sdk.delivery.sender.run(RunBudget(maxBatches = 1, softMillis = 60_000))
        assertEquals(9_950, sdk.dao.usage().count)
        repeat(50) { assertTrue(sdk.track("again") is TrackResult.Success) }
        assertEquals(TrackResult.QueueFull, sdk.track("overflow"))

        // Full drain: everything is delivered once, and the queue accepts events again.
        sdk.delivery.sender.run()
        assertEquals(0, sdk.dao.usage().count)
        assertEquals(10_050, server.committed.size)
        assertEquals(10_050, server.receivedIds.size)
        assertTrue(sdk.track("after_recovery") is TrackResult.Success)
    }

    @Test
    fun the_queue_holds_16_MiB_of_transport_JSON_then_QueueFull_and_space_returns() = runBlocking {
        val pad = padFor(16 * kib)
        repeat(1024) { assertTrue("track #$it", sdk.track("e") { property("k", pad) } is TrackResult.Success) }
        val usage = sdk.dao.usage()
        assertEquals(1024, usage.count)
        assertEquals(16L * 1024 * 1024, usage.bytes) // exactly the limit still fits
        assertEquals(TrackResult.QueueFull, sdk.track("e") { property("k", "") })
        assertEquals(1024, sdk.dao.usage().count)

        // One acknowledged batch frees exactly its events (16 KiB each): as many fit again, one more does not.
        sdk.delivery.sender.run(RunBudget(maxBatches = 1, softMillis = 60_000))
        val freed = eventPayloads(server.bodies.single()).size
        assertEquals(1024 - freed, sdk.dao.usage().count)
        repeat(freed) { assertTrue(sdk.track("e") { property("k", pad) } is TrackResult.Success) }
        assertEquals(TrackResult.QueueFull, sdk.track("e") { property("k", pad) })
    }

    @Test
    fun concurrent_insertion_never_exceeds_10000_events_and_loses_none() = runBlocking {
        // Fill to 9,000 with committed rows, then race 2,000 tracks over the last 1,000 places.
        sdk.track("seed")
        val seed = sdk.dao.oldest(1).single()
        for (i in 1 until 9_000) {
            sdk.dao.insert(seed.copy(seq = 0, eventId = "00000000-0000-4000-8000-%012d".format(i), createdAtMillis = seed.createdAtMillis + i))
        }
        assertEquals(9_000, sdk.dao.usage().count)
        val results = (1..2_000).map { n -> async(Dispatchers.IO) { sdk.track("race") { property("n", n) } } }.awaitAll()
        assertEquals(1_000, results.count { it is TrackResult.Success })
        assertEquals(1_000, results.count { it == TrackResult.QueueFull })
        assertTrue(results.none { it is TrackResult.StorageError })
        val stored = sdk.dao.oldest(20_000)
        assertEquals(10_000, stored.size)
        assertEquals(10_000, stored.map(EventEntity::eventId).toSet().size)
        val succeeded = results.filterIsInstance<TrackResult.Success>().map { it.eventId }.toSet()
        assertTrue(stored.map { it.eventId }.containsAll(succeeded))
    }
}
