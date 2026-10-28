package local.signaldock

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.signaldock.internal.delivery.RunEnd
import local.signaldock.internal.delivery.TransportResult
import local.signaldock.internal.delivery.buildClientReport
import local.signaldock.internal.delivery.dropReason
import local.signaldock.internal.storage.DroppedCountEntity
import local.signaldock.internal.storage.MAX_DROPPED_COUNT
import local.signaldock.internal.storage.QueueLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openapitools.client.models.DiscardReason
import org.robolectric.RobolectricTestRunner
import java.io.File

/** SDK client reports (Q164, ADR-0036): counting, saturation, sending, and what each answer does to the counters. */
@RunWith(RobolectricTestRunner::class)
class ClientReportTest {
    private val transport = FakeTransport()

    private suspend fun sdk(limits: QueueLimits = QueueLimits(), logger: FakeLogger = FakeLogger()) =
        newSdk(limits = limits, transport = transport, logger = logger)

    private suspend fun counts(sdk: SignalDock): Map<String, Int> = sdk.dao.droppedCounts().associate { it.reason to it.count }

    private fun reportEntries(r: local.signaldock.internal.delivery.BatchHttpRequest): Map<String, Int> =
        Json.parseToJsonElement(String(r.body, Charsets.UTF_8)).jsonObject["discardedEvents"]!!.jsonArray
            .associate { it.jsonObject["reason"]!!.jsonPrimitive.content to it.jsonObject["count"]!!.jsonPrimitive.int }

    // ---------- counting ----------

    @Test
    fun `track results map to drop reasons and a stored event or a non-drop result maps to none`() {
        assertNull(TrackResult.Success("e").dropReason())
        assertEquals(DiscardReason.QUEUE_FULL, TrackResult.QueueFull.dropReason())
        assertEquals(DiscardReason.STORAGE_ERROR, TrackResult.StorageError("IOException").dropReason())
        assertEquals(DiscardReason.EVENT_TOO_LARGE, TrackResult.Invalid(InvalidReason.EVENT_TOO_LARGE, null).dropReason())
        for (r in InvalidReason.entries - InvalidReason.EVENT_TOO_LARGE) {
            assertEquals("$r", DiscardReason.INVALID_EVENT, TrackResult.Invalid(r, "name").dropReason())
        }
    }

    @Test
    fun `track counts queue full, invalid and too large events and does not count a stored event`() = runBlocking {
        val sdk = sdk(QueueLimits(maxEvents = 1))
        assertTrue(sdk.track("ok") is TrackResult.Success)
        assertEquals(TrackResult.QueueFull, sdk.track("full"))
        assertEquals(TrackResult.QueueFull, sdk.track("full"))
        assertTrue(sdk.track(" bad") is TrackResult.Invalid)
        assertTrue(sdk.track("") is TrackResult.Invalid)
        assertEquals(mapOf("queue_full" to 2, "invalid_event" to 2), counts(sdk))
    }

    @Test
    fun `an event over 16 KiB is counted as too large, not as invalid`() = runBlocking {
        val sdk = sdk()
        assertEquals(TrackResult.Invalid(InvalidReason.EVENT_TOO_LARGE, null), sdk.track("big") { property("k", "x".repeat(20_000)) })
        assertEquals(mapOf("event_too_large" to 1), counts(sdk))
    }

    @Test
    fun `counters stop at the limit and never overflow`() = runBlocking {
        val sdk = sdk()
        sdk.dao.addDropped("queue_full", MAX_DROPPED_COUNT - 1)
        sdk.dao.addDropped("queue_full", 1)
        assertEquals(MAX_DROPPED_COUNT, counts(sdk)["queue_full"])
        sdk.dao.addDropped("queue_full", 1)
        sdk.dao.addDropped("queue_full", Int.MAX_VALUE)
        assertEquals(MAX_DROPPED_COUNT, counts(sdk)["queue_full"])
    }

    @Test
    fun `counters survive a restart of the SDK`() = runBlocking {
        val first = sdk()
        first.track("") // invalid
        val second = sdk() // a new SDK instance on the same files: a restart
        assertEquals(mapOf("invalid_event" to 1), counts(second))
    }

    // ---------- the server-rejected count is part of the ACK commit ----------

    @Test
    fun `permanent rejections in a valid ACK are counted in the same commit and reported`() = runBlocking {
        val sdk = sdk()
        repeat(3) { sdk.track("e") { property("n", it) } }
        transport.handler = { ackAll(it, status = { i -> if (i == 1) "rejected" else "accepted" }) }
        assertEquals(RunEnd.QUEUE_EMPTY, sdk.delivery.sender.run().end)
        assertEquals(1, transport.reports.size)
        assertEquals(mapOf("server_rejected" to 1), reportEntries(transport.reports[0]))
        assertEquals(emptyMap<String, Int>(), counts(sdk)) // sent, so subtracted
    }

    @Test
    fun `an invalid ACK applies nothing, so it counts nothing`() = runBlocking {
        val sdk = sdk()
        sdk.track("e")
        transport.handler = { TransportResult.Http(200, """{"results":[]}""", null) }
        sdk.delivery.sender.run()
        assertEquals(emptyMap<String, Int>(), counts(sdk))
        assertTrue(transport.reports.isEmpty())
    }

    // ---------- sending ----------

    @Test
    fun `the report goes once per send cycle, after the event batches, with the sdk version and all reasons`() = runBlocking {
        val sdk = sdk()
        sdk.dao.addDropped("queue_full", 3)
        sdk.dao.addDropped("storage_error", 1)
        repeat(120) { sdk.track("e") { property("n", it) } } // 3 batches of at most 50
        assertEquals(RunEnd.QUEUE_EMPTY, sdk.delivery.sender.run().end)
        assertEquals(listOf("batch", "batch", "batch", "report"), transport.calls)
        val report = transport.reports.single()
        assertEquals("1", report.contractVersion)
        val root = Json.parseToJsonElement(String(report.body, Charsets.UTF_8)).jsonObject
        assertEquals(setOf("sdkVersion", "sdkPlatform", "discardedEvents"), root.keys)
        assertEquals("0.4.0", root["sdkVersion"]!!.jsonPrimitive.content)
        assertEquals("android", root["sdkPlatform"]!!.jsonPrimitive.content)
        assertEquals(mapOf("queue_full" to 3, "storage_error" to 1), reportEntries(report))
        // Nothing left to report: the next cycle sends no second report.
        sdk.track("e")
        sdk.delivery.sender.run()
        assertEquals(1, transport.reports.size)
    }

    @Test
    fun `no report is sent when no counter is set or when the queue is empty`() = runBlocking {
        val sdk = sdk()
        sdk.delivery.sender.run() // empty queue: no HTTP at all
        assertEquals(emptyList<String>(), transport.calls)
        sdk.track("e")
        sdk.delivery.sender.run()
        assertEquals(listOf("batch"), transport.calls) // events, but nothing dropped
    }

    @Test
    fun `no report is sent when the cycle applied no valid ACK`() = runBlocking {
        val sdk = sdk()
        sdk.dao.addDropped("queue_full", 2)
        sdk.track("e")
        transport.handler = { TransportResult.Http(503, null, null) }
        assertEquals(RunEnd.WAITING, sdk.delivery.sender.run().end)
        assertTrue(transport.reports.isEmpty())
        assertEquals(mapOf("queue_full" to 2), counts(sdk))
    }

    @Test
    fun `the report body is the one the server tests post (drift check with fixtures roundtrip)`() {
        val rows = listOf(
            DroppedCountEntity("queue_full", 3), DroppedCountEntity("storage_error", 1), DroppedCountEntity("invalid_event", 2),
            DroppedCountEntity("event_too_large", 4), DroppedCountEntity("server_rejected", 1_000_000),
        )
        val body = Json.parseToJsonElement(String(buildClientReport(rows)!!.body, Charsets.UTF_8)).jsonObject
        val fixture = Json.parseToJsonElement(File("../../fixtures/roundtrip/client-report-request.json").readText()).jsonObject
        fun entries(o: JsonObject) = o["discardedEvents"]!!.jsonArray.map { it.jsonObject["reason"]!!.jsonPrimitive.content to it.jsonObject["count"]!!.jsonPrimitive.int }.toSet()
        assertEquals("0.4.0", body["sdkVersion"]!!.jsonPrimitive.content) // the fixture keeps the 0.3.0 value
        assertEquals(entries(fixture), entries(body))
        // The fixture is the 0.3.0 form (no platform, still valid for old SDKs); 0.4.0 adds sdkPlatform (ADR-0046).
        assertEquals(fixture.keys + "sdkPlatform", body.keys)
        assertEquals("android", body["sdkPlatform"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a counter above the limit is cut, an unknown reason or a zero count is not sent`() {
        val report = buildClientReport(
            listOf(DroppedCountEntity("queue_full", Int.MAX_VALUE), DroppedCountEntity("made_up", 5), DroppedCountEntity("storage_error", 0)),
        )!!
        assertEquals(listOf(DroppedCountEntity("queue_full", MAX_DROPPED_COUNT)), report.rows)
        assertNull(buildClientReport(listOf(DroppedCountEntity("made_up", 5))))
        assertNull(buildClientReport(emptyList()))
    }

    // ---------- answers ----------

    private suspend fun cycleWithReportStatus(status: Int?): SignalDock {
        val sdk = sdk()
        sdk.dao.addDropped("queue_full", 4)
        sdk.track("e")
        transport.reportHandler = { if (status == null) TransportResult.Failed(DeliveryErrorKind.NETWORK) else TransportResult.Http(status, null, null) }
        assertEquals(RunEnd.QUEUE_EMPTY, sdk.delivery.sender.run().end)
        assertEquals("one report", 1, transport.reports.size)
        return sdk
    }

    @Test
    fun `2xx removes the sent counts`() = runBlocking {
        for (status in listOf(200, 204)) {
            transport.reports.clear()
            val sdk = cycleWithReportStatus(status)
            assertEquals("$status", emptyMap<String, Int>(), counts(sdk))
        }
    }

    @Test
    fun `404 from an old server and other permanent answers discard the report with no retry`() = runBlocking {
        for (status in listOf(404, 400, 401, 403, 413, 415, 302, 501, 505)) {
            transport.reports.clear()
            val sdk = cycleWithReportStatus(status)
            assertEquals("$status discards the counts", emptyMap<String, Int>(), counts(sdk))
            // No retry: with nothing new dropped, the next cycle sends no report.
            sdk.track("e")
            sdk.delivery.sender.run()
            assertEquals("$status: no retry", 1, transport.reports.size)
            // The report answer never touches the retry state of the events.
            val d = sdk.diagnostics()
            assertEquals(0, d.retryFailureCount)
            assertNull(d.nextAttemptEpochMillis)
            assertNull(d.lastDeliveryError)
        }
    }

    @Test
    fun `a transient failure keeps the counters and the next cycle sends them with the new ones`() = runBlocking {
        for (status in listOf(null, 408, 429, 500, 502, 503)) {
            transport.reports.clear()
            val sdk = cycleWithReportStatus(status)
            assertEquals("$status keeps the counts", mapOf("queue_full" to 4), counts(sdk))
            val d = sdk.diagnostics()
            assertEquals("$status leaves the event retry state alone", 0, d.retryFailureCount)
            assertNull(d.nextAttemptEpochMillis)
            assertNull(d.lastDeliveryError)
            // Next cycle: the server is back. Counters add up and go out in one report.
            sdk.dao.addDropped("queue_full", 1)
            sdk.track("e")
            transport.reportHandler = { TransportResult.Http(204, null, null) }
            sdk.delivery.sender.run()
            assertEquals("$status", 2, transport.reports.size)
            assertEquals("$status", mapOf("queue_full" to 5), reportEntries(transport.reports[1]))
            assertEquals("$status", emptyMap<String, Int>(), counts(sdk))
        }
    }

    @Test
    fun `drops counted while the report is in flight stay`() = runBlocking {
        val sdk = sdk()
        sdk.dao.addDropped("queue_full", 2)
        sdk.track("e")
        transport.reportHandler = {
            sdk.dao.addDropped("queue_full", 3) // a new drop during the HTTP call
            sdk.dao.addDropped("invalid_event", 1)
            TransportResult.Http(204, null, null)
        }
        sdk.delivery.sender.run()
        assertEquals(mapOf("queue_full" to 3, "invalid_event" to 1), counts(sdk))
    }

    @Test
    fun `a failure of the counter store does not hide the batch result`() = runBlocking {
        val log = FakeLogger()
        val sdk = sdk(logger = log)
        sdk.dao.addDropped("queue_full", 1)
        sdk.track("e")
        transport.reportHandler = { throw IllegalStateException("boom") }
        val result = sdk.delivery.sender.run()
        assertEquals(RunEnd.QUEUE_EMPTY, result.end)
        assertTrue(log.warnings.any { it.startsWith("client report failed") })
        assertEquals(mapOf("queue_full" to 1), counts(sdk))
    }
}
