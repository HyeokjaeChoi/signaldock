package local.signaldock

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import local.signaldock.internal.delivery.Jitter
import local.signaldock.internal.delivery.RunBudget
import local.signaldock.internal.delivery.RunEnd
import local.signaldock.internal.delivery.TransportResult
import local.signaldock.internal.delivery.buildBatch
import local.signaldock.internal.delivery.parseRetryAfterMillis
import kotlinx.serialization.json.jsonPrimitive
import local.signaldock.internal.transport.batchRequestBytes
import local.signaldock.internal.transport.transportJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.openapitools.client.models.BatchIngestRequest
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val T0 = 1_760_000_000_123L
private const val SECOND = 1000L
private const val MINUTE = 60_000L

@RunWith(RobolectricTestRunner::class)
class DeliveryTest {
    private val clock = FakeClock(T0)
    private val transport = FakeTransport()

    private suspend fun sdkWith(
        jitter: Jitter = Jitter { 0 },
        events: Int = 3,
    ): SignalDock = newSdk(clock = clock, transport = transport, jitter = jitter).also { sdk ->
        repeat(events) { sdk.track("e") { property("n", it) } }
    }

    private suspend fun queued(sdk: SignalDock) = sdk.dao.oldest(100_000).map { it.eventId }

    // ---------- success and ACK application ----------

    @Test
    fun `valid ACK deletes the batch and records the ACK`() = runBlocking {
        val sdk = sdkWith()
        val result = sdk.delivery.sender.run()
        assertEquals(RunEnd.QUEUE_EMPTY, result.end)
        assertEquals(1, transport.requests.size)
        assertEquals("1", transport.requests[0].contractVersion)
        assertEquals(emptyList<String>(), queued(sdk))
        val d = sdk.diagnostics()
        assertEquals(AckDiagnostic(T0, accepted = 3, duplicate = 0, rejected = 0), d.lastAck)
        assertEquals(0, d.retryFailureCount)
        assertNull(d.nextAttemptEpochMillis)
    }

    @Test
    fun `partial ACK applies per item and removes duplicates and permanent rejections`() = runBlocking {
        val sdk = sdkWith(events = 4)
        val ids = queued(sdk)
        transport.handler = { ackAll(it, status = { i -> listOf("accepted", "duplicate", "rejected", "accepted")[i] }, code = "INVALID_NAME") }
        sdk.delivery.sender.run()
        assertEquals(emptyList<String>(), queued(sdk))
        val d = sdk.diagnostics()
        assertEquals(AckDiagnostic(T0, accepted = 2, duplicate = 1, rejected = 1), d.lastAck)
        assertEquals(listOf(RejectionDiagnostic(ids[2], "INVALID_NAME", T0)), d.recentRejections)
        assertEquals(0, d.retryFailureCount) // an ACK with permanent rejections still resets the count (Q75)
    }

    @Test
    fun `an ACK of only known rejections resets the failure count`() = runBlocking {
        val sdk = sdkWith(events = 2)
        transport.handler = { http(503) }
        sdk.delivery.sender.run()
        assertEquals(1, sdk.diagnostics().retryFailureCount)
        clock.now += 10 * SECOND
        transport.handler = { ackAll(it, status = { "rejected" }) }
        sdk.delivery.sender.run()
        val d = sdk.diagnostics()
        assertEquals(0, d.retryFailureCount)
        assertEquals(2, d.recentRejections.size)
        assertEquals(0, d.queuedEvents)
    }

    @Test
    fun `ACK application is atomic - a failing retry state write rolls back deletes and diagnostics`() = runBlocking {
        val sdk = sdkWith(events = 3)
        val ids = queued(sdk)
        transport.handler = { ackAll(it, status = { i -> if (i == 0) "rejected" else "accepted" }) }
        val path = File(File(context.noBackupFilesDir, "signaldock"), "queue.db").absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER fail_state BEFORE INSERT ON retry_state BEGIN SELECT RAISE(ABORT, 'boom'); END")
        }
        try {
            sdk.delivery.sender.run()
            fail("expected the ACK transaction to fail")
        } catch (e: android.database.sqlite.SQLiteException) {
            // expected
        }
        assertEquals(ids, queued(sdk)) // nothing was deleted
        assertEquals(emptyList<RejectionDiagnostic>(), sdk.diagnostics().recentRejections)
    }

    @Test
    fun `unreadable, incomplete or contradictory ACKs apply nothing, keep the batch and back off`() = runBlocking {
        val sdk = sdkWith(events = 2)
        val ids = queued(sdk)
        val (a, b) = ids
        val bodies = listOf(
            "not json",
            "",
            "{}",
            """{"results":[]}""",
            """{"results":[{"index":0,"eventId":"$a","status":"accepted"}]}""", // missing result
            """{"results":[{"index":0,"eventId":"$a","status":"accepted"},{"index":0,"eventId":"$a","status":"accepted"}]}""", // duplicate index
            """{"results":[{"index":0,"eventId":"$a","status":"accepted"},{"index":2,"eventId":"$b","status":"accepted"}]}""", // index out of range
            """{"results":[{"index":0,"eventId":"$a","status":"accepted"},{"index":1,"eventId":"$a","status":"accepted"}]}""", // id mismatch
            """{"results":[{"index":0,"eventId":"$a","status":"accepted"},{"index":1,"status":"accepted"}]}""", // accepted without id
            """{"results":[{"index":0,"eventId":"$a","status":"accepted"},{"index":1,"eventId":"$b","status":"stored"}]}""", // unknown status
            """{"results":[{"index":0,"eventId":"$a","status":"accepted"},{"index":"1","eventId":"$b","status":"accepted"}]}""", // type error
            """{"results":[{"index":0,"eventId":"$a","status":"accepted"},{"index":1,"eventId":"$b","status":"accepted"},{"index":2,"eventId":"$b","status":"accepted"}]}""", // extra result
            """{"results":[{"index":0,"eventId":"$a","status":"accepted"},{"index":1,"eventId":"other","status":"rejected"}]}""", // rejected, wrong id
            """{"results":[{"index":0,"eventId":"$a","status":"accepted"},{"index":1,"eventId":"$b","status":"rejected"}""", // truncated
        )
        for ((n, body) in bodies.withIndex()) {
            transport.handler = { TransportResult.Http(200, body, null) }
            clock.now += 10 * MINUTE
            val result = sdk.delivery.sender.run()
            assertEquals("body #$n", RunEnd.WAITING, result.end)
            assertEquals("body #$n queue", ids, queued(sdk))
            val d = sdk.diagnostics()
            assertEquals("body #$n kind", DeliveryErrorKind.INVALID_ACK, d.lastDeliveryError?.kind)
            assertEquals(n + 1, d.retryFailureCount)
            assertNull("body #$n ack", d.lastAck)
            assertEquals(emptyList<RejectionDiagnostic>(), d.recentRejections)
        }
    }

    @Test
    fun `compatible extra ACK fields are ignored`() = runBlocking {
        val sdk = sdkWith(events = 1)
        val id = queued(sdk).single()
        transport.handler = { TransportResult.Http(200, """{"extra":1,"results":[{"index":0,"eventId":"$id","status":"accepted","newField":{"x":1}}]}""", null) }
        sdk.delivery.sender.run()
        assertEquals(0, sdk.diagnostics().queuedEvents)
    }

    @Test
    fun `a 2xx other than 200 is not an ACK`() = runBlocking {
        val sdk = sdkWith(events = 1)
        transport.handler = { TransportResult.Http(204, null, null) }
        sdk.delivery.sender.run()
        assertEquals(1, sdk.diagnostics().queuedEvents)
        assertEquals(DeliveryErrorKind.INVALID_ACK, sdk.diagnostics().lastDeliveryError?.kind)
    }

    @Test
    fun `an empty queue sends no HTTP request`() = runBlocking {
        val sdk = newSdk(clock = clock, transport = transport)
        assertEquals(RunEnd.QUEUE_EMPTY, sdk.delivery.sender.run().end)
        assertEquals(0, transport.requests.size)
    }

    // ---------- backoff, Retry-After ----------

    @Test
    fun `backoff base doubles from 10 seconds to a 5 minute cap and Full Jitter stays within 0 to B`() = runBlocking {
        val bounds = mutableListOf<Long>()
        val sdk = sdkWith(jitter = Jitter { b -> bounds += b; b }, events = 1)
        transport.handler = { TransportResult.Failed(DeliveryErrorKind.NETWORK) }
        repeat(8) {
            val r = sdk.delivery.sender.run()
            assertEquals(RunEnd.WAITING, r.end)
            clock.now = r.nextAttemptMillis!!
        }
        assertEquals(listOf(10L, 20, 40, 80, 160, 300, 300, 300).map { it * SECOND }, bounds)
        assertEquals(8, sdk.diagnostics().retryFailureCount)
    }

    @Test
    fun `jitter draws below the bound and above it are limited to the bound and may be zero`() = runBlocking {
        val sdk = sdkWith(jitter = Jitter { 0 }, events = 1)
        transport.handler = { TransportResult.Failed(DeliveryErrorKind.TIMEOUT) }
        val r = sdk.delivery.sender.run()
        assertEquals(T0, r.nextAttemptMillis) // retry may be almost immediate
        val sdk2 = newSdk(clock = clock, transport = transport, jitter = Jitter { 10 * MINUTE }) // out of range draw
        val r2 = sdk2.delivery.sender.run()
        assertEquals(T0 + 10 * SECOND * 2, r2.nextAttemptMillis) // second failure: bound 20 s, draw clamped to it
    }

    @Test
    fun `the next attempt time is persisted and survives reopening the database`() = runBlocking {
        val sdk = sdkWith(jitter = Jitter { 100 * SECOND }, events = 2)
        transport.handler = { http(503) }
        val r = sdk.delivery.sender.run()
        val next = T0 + 10 * SECOND // bound 10 s caps the draw
        assertEquals(next, r.nextAttemptMillis)

        // A new SDK instance on the same files, as after a process restart.
        val transport2 = FakeTransport()
        val reopened = newSdk(clock = clock, transport = transport2)
        assertEquals(next, reopened.diagnostics().nextAttemptEpochMillis)
        assertEquals(1, reopened.diagnostics().retryFailureCount)
        assertEquals(DeliveryErrorKind.SERVER_ERROR, reopened.diagnostics().lastDeliveryError?.kind)
        assertEquals(503, reopened.diagnostics().lastDeliveryError?.httpStatus)

        clock.now = next - 1
        assertEquals(RunEnd.WAITING, reopened.delivery.sender.run().end) // new trigger cannot bypass the wait
        assertEquals(0, transport2.requests.size)
        clock.now = next
        reopened.delivery.sender.run()
        assertEquals(1, transport2.requests.size)
        assertEquals(0, reopened.diagnostics().queuedEvents)
        assertEquals(0, reopened.diagnostics().retryFailureCount) // reset after the valid ACK was applied
        assertNull(reopened.diagnostics().nextAttemptEpochMillis)
    }

    @Test
    fun `network, timeout, 408, 429 and 5xx keep the queue and are classified`() = runBlocking {
        val cases = listOf(
            TransportResult.Failed(DeliveryErrorKind.NETWORK) to (DeliveryErrorKind.NETWORK to null),
            TransportResult.Failed(DeliveryErrorKind.TIMEOUT) to (DeliveryErrorKind.TIMEOUT to null),
            http(408) to (DeliveryErrorKind.TIMEOUT to 408),
            http(429) to (DeliveryErrorKind.RATE_LIMITED to 429),
            http(500) to (DeliveryErrorKind.SERVER_ERROR to 500),
            http(503) to (DeliveryErrorKind.SERVER_ERROR to 503),
        )
        val sdk = sdkWith(events = 1)
        val ids = queued(sdk)
        for ((n, c) in cases.withIndex()) {
            transport.handler = { c.first }
            clock.now += 10 * MINUTE
            assertEquals(RunEnd.WAITING, sdk.delivery.sender.run().end)
            val d = sdk.diagnostics()
            assertEquals(c.second.first, d.lastDeliveryError?.kind)
            assertEquals(c.second.second, d.lastDeliveryError?.httpStatus)
            assertEquals(n + 1, d.retryFailureCount)
            assertEquals(ids, queued(sdk))
        }
    }

    @Test
    fun `Retry-After longer than the backoff wins and a shorter one does not shorten it`() = runBlocking {
        val sdk = sdkWith(jitter = Jitter { b -> b }, events = 1)
        transport.handler = { http(503, retryAfter = "120") }
        assertEquals(T0 + 120 * SECOND, sdk.delivery.sender.run().nextAttemptMillis)
        clock.now += 120 * SECOND
        transport.handler = { http(503, retryAfter = "1") }
        // second failure: backoff bound is 20 s, which is longer than 1 s
        assertEquals(clock.now + 20 * SECOND, sdk.delivery.sender.run().nextAttemptMillis)
        // A huge value is kept; it is not capped to 5 minutes, and does not overflow.
        clock.now += 20 * SECOND
        transport.handler = { http(429, retryAfter = "99999999999999999999") }
        val far = sdk.delivery.sender.run().nextAttemptMillis!!
        assertTrue(far > clock.now + 100 * 365 * 24 * 3600 * SECOND)
    }

    @Test
    fun `Retry-After as an HTTP date and invalid values`() {
        val now = 1_760_000_000_000L
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
        assertEquals(90 * SECOND, parseRetryAfterMillis(date.format(java.util.Date(now + 90 * SECOND)), now))
        assertNull(parseRetryAfterMillis(date.format(java.util.Date(now - SECOND)), now))
        assertEquals(5 * SECOND, parseRetryAfterMillis(" 5 ", now))
        for (bad in listOf(null, "", "0", "-5", "abc", "1.5", "5s", "Sun, 99 Foo 2020")) assertNull("'$bad'", parseRetryAfterMillis(bad, now))
    }

    @Test
    fun `Retry-After on 429 is honored with the HTTP date form`() = runBlocking {
        val sdk = sdkWith(events = 1)
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
        transport.handler = { http(429, retryAfter = date.format(java.util.Date(T0 + 45 * SECOND + 500))) }
        val next = sdk.delivery.sender.run().nextAttemptMillis!!
        assertTrue(next in (T0 + 44 * SECOND)..(T0 + 46 * SECOND))
    }

    // ---------- holds ----------

    @Test
    fun `401 stops normal sending, rechecks one batch every 30 minutes and resumes on a valid ACK`() = runBlocking {
        val sdk = sdkWith(events = 120)
        val ids = queued(sdk)
        transport.handler = { http(401) }
        val first = sdk.delivery.sender.run()
        assertEquals(RunEnd.WAITING, first.end)
        assertEquals(T0 + 30 * MINUTE, first.nextAttemptMillis)
        assertEquals(1, transport.requests.size)
        assertEquals(DeliveryErrorKind.AUTH_OR_CONFIGURATION, sdk.diagnostics().lastDeliveryError?.kind)
        assertEquals(0, sdk.diagnostics().retryFailureCount) // not a temporary failure
        assertEquals(ids, queued(sdk))

        // New events and triggers cannot bypass the wait.
        sdk.track("late")
        clock.now = T0 + 30 * MINUTE - 1
        sdk.delivery.sender.run()
        assertEquals(1, transport.requests.size)

        // Recheck: exactly one batch, which fails again.
        clock.now = T0 + 30 * MINUTE
        sdk.delivery.sender.run()
        assertEquals(2, transport.requests.size)
        assertEquals(50, eventIds(transport.requests[1]).size)
        assertEquals(clock.now + 30 * MINUTE, sdk.diagnostics().nextAttemptEpochMillis)

        // The recheck succeeds: normal sending resumes at once and drains the queue.
        transport.handler = { ackAll(it) }
        clock.now += 30 * MINUTE
        assertEquals(RunEnd.QUEUE_EMPTY, sdk.delivery.sender.run().end)
        assertEquals(0, sdk.diagnostics().queuedEvents)
        assertNull(sdk.diagnostics().nextAttemptEpochMillis)
    }

    @Test
    fun `a longer Retry-After extends the hold`() = runBlocking {
        val sdk = sdkWith(events = 1)
        transport.handler = { http(403, retryAfter = "7200") }
        assertEquals(T0 + 120 * MINUTE, sdk.delivery.sender.run().nextAttemptMillis)
        transport.handler = { http(403, retryAfter = "10") } // shorter than 30 minutes: 30 minutes apply
        clock.now = T0 + 120 * MINUTE
        assertEquals(clock.now + 30 * MINUTE, sdk.delivery.sender.run().nextAttemptMillis)
    }

    @Test
    fun `a hold with an empty queue sends no HTTP and a hold survives reopening`() = runBlocking {
        val sdk = sdkWith(events = 1)
        transport.handler = { http(401) }
        sdk.delivery.sender.run()
        transport.handler = { ackAll(it) }
        val reopened = newSdk(clock = clock, transport = transport)
        clock.now = T0 + 29 * MINUTE
        reopened.delivery.sender.run()
        assertEquals(1, transport.requests.size) // still held after the restart
        clock.now = T0 + 31 * MINUTE
        reopened.delivery.sender.run() // the probe succeeds and empties the queue
        assertEquals(2, transport.requests.size)
        clock.now += 100 * MINUTE
        reopened.delivery.sender.run()
        assertEquals(2, transport.requests.size) // empty queue: no HTTP
    }

    @Test
    fun `configuration, contract and redirect responses hold the queue`() = runBlocking {
        val cases = mapOf(
            400 to DeliveryErrorKind.CONTRACT,
            401 to DeliveryErrorKind.AUTH_OR_CONFIGURATION,
            403 to DeliveryErrorKind.AUTH_OR_CONFIGURATION,
            404 to DeliveryErrorKind.AUTH_OR_CONFIGURATION,
            415 to DeliveryErrorKind.AUTH_OR_CONFIGURATION,
            301 to DeliveryErrorKind.AUTH_OR_CONFIGURATION,
            302 to DeliveryErrorKind.AUTH_OR_CONFIGURATION,
            307 to DeliveryErrorKind.AUTH_OR_CONFIGURATION,
            501 to DeliveryErrorKind.AUTH_OR_CONFIGURATION,
            505 to DeliveryErrorKind.AUTH_OR_CONFIGURATION,
        )
        val sdk = sdkWith(events = 1)
        val ids = queued(sdk)
        for ((status, kind) in cases) {
            transport.handler = { http(status) }
            clock.now += 31 * MINUTE
            val before = transport.requests.size
            assertEquals("status $status", RunEnd.WAITING, sdk.delivery.sender.run().end)
            assertEquals(before + 1, transport.requests.size)
            assertEquals("status $status", kind, sdk.diagnostics().lastDeliveryError?.kind)
            assertEquals(status, sdk.diagnostics().lastDeliveryError?.httpStatus)
            assertEquals(ids, queued(sdk))
        }
    }

    @Test
    fun `a transient failure during a hold still waits at least 30 minutes`() = runBlocking {
        val sdk = sdkWith(events = 1)
        transport.handler = { http(401) }
        sdk.delivery.sender.run()
        clock.now = T0 + 30 * MINUTE
        transport.handler = { http(503) }
        assertEquals(clock.now + 30 * MINUTE, sdk.delivery.sender.run().nextAttemptMillis)
    }

    // ---------- batches ----------

    @Test
    fun `batches hold at most 50 events and are sent oldest first`() = runBlocking {
        val sdk = sdkWith(events = 120)
        val ids = queued(sdk)
        sdk.delivery.sender.run()
        assertEquals(listOf(50, 50, 20), transport.requests.map { eventIds(it).size })
        assertEquals(ids, transport.requests.flatMap { eventIds(it) })
        assertEquals(0, sdk.diagnostics().queuedEvents)
    }

    @Test
    fun `the request body is the canonical JSON and stays within 256 KiB`() = runBlocking {
        val sdk = newSdk(clock = clock, transport = transport)
        val big = "x".repeat(9_000) + "é\"\\\n" // escaping and multi-byte characters count as sent
        repeat(60) { sdk.track("e") { property("p", big) } }
        sdk.delivery.sender.run()
        assertTrue(transport.requests.size >= 3)
        var total = 0
        for (r in transport.requests) {
            val decoded = transportJson.decodeFromString(BatchIngestRequest.serializer(), String(r.body, Charsets.UTF_8))
            assertEquals(batchRequestBytes(decoded.installationId, decoded.events), r.body.size) // canonical size function
            assertEquals(String(r.body, Charsets.UTF_8), transportJson.encodeToString(BatchIngestRequest.serializer(), decoded)) // stored bytes == re-encoded
            assertTrue(r.body.size <= 256 * 1024)
            total += decoded.events.size
        }
        assertEquals(60, total)
        // A batch is full: the next event would not fit.
        val first = transport.requests[0]
        val nextEventBytes = (first.body.size / eventIds(first).size) // roughly one event
        assertTrue(first.body.size + nextEventBytes > 256 * 1024)
    }

    @Test
    fun `batch size boundary - exactly the limit fits and one byte less drops the last event`() = runBlocking {
        val sdk = sdkWith(events = 5)
        val rows = sdk.dao.oldest(10)
        val full = buildBatch(rows, 50, Int.MAX_VALUE)
        assertEquals(5, full.events.size)
        assertEquals(5, buildBatch(rows, 50, full.body.size).events.size)
        assertEquals(4, buildBatch(rows, 50, full.body.size - 1).events.size)
        assertEquals(5, buildBatch(rows, 5, Int.MAX_VALUE).events.size)
        assertEquals(3, buildBatch(rows, 3, Int.MAX_VALUE).events.size)
        assertEquals(1, buildBatch(rows, 50, 1).events.size) // always at least one
    }

    @Test
    fun `batches are grouped by stored contract version and installation id`() = runBlocking {
        val sdk = sdkWith(events = 2)
        val base = sdk.dao.oldest(10)[0]
        fun copyOf(id: String) = base.copy(seq = 0, eventId = id, payloadJson = base.payloadJson.replace(base.eventId, id))
        sdk.dao.insert(copyOf("00000000-0000-4000-8000-0000000000a1").copy(contractVersion = "2"))
        sdk.dao.insert(copyOf("00000000-0000-4000-8000-0000000000a2").copy(installationId = "other-installation"))
        sdk.track("after")
        sdk.delivery.sender.run()
        // The group of the oldest event goes first, then the group of the oldest remaining event.
        assertEquals(listOf("1", "2", "1"), transport.requests.map { it.contractVersion })
        assertEquals(listOf(3, 1, 1), transport.requests.map { eventIds(it).size })
        assertEquals(
            listOf(base.installationId, base.installationId, "other-installation"),
            transport.requests.map { requestEvents(it)["installationId"]!!.jsonPrimitive.content },
        )
        assertEquals(0, sdk.diagnostics().queuedEvents)
    }

    // ---------- 413 ----------

    @Test
    fun `413 for a multi-event batch splits into smaller batches with the same ids`() = runBlocking {
        val sdk = sdkWith(events = 50)
        val ids = queued(sdk)
        transport.handler = { if (eventIds(it).size > 10) http(413) else ackAll(it) }
        val r = sdk.delivery.sender.run()
        assertEquals(RunEnd.QUEUE_EMPTY, r.end)
        assertEquals(listOf(50, 25, 12, 6), transport.requests.take(4).map { eventIds(it).size })
        assertEquals(ids, transport.requests.flatMap { eventIds(it) }.distinct()) // same ids, same order
        assertEquals(0, sdk.diagnostics().queuedEvents)
        assertEquals(0, sdk.diagnostics().retryFailureCount) // a split is not a temporary failure
    }

    @Test
    fun `413 does not retry before Retry-After and the smaller size survives reopening`() = runBlocking {
        val sdk = sdkWith(events = 50)
        transport.handler = { http(413, retryAfter = "60") }
        val r = sdk.delivery.sender.run()
        assertEquals(T0 + 60 * SECOND, r.nextAttemptMillis)
        assertEquals(1, transport.requests.size)
        val transport2 = FakeTransport()
        val reopened = newSdk(clock = clock, transport = transport2)
        clock.now = T0 + 60 * SECOND
        reopened.delivery.sender.run()
        assertEquals(listOf(25, 25), transport2.requests.map { eventIds(it).size })
        assertEquals(0, reopened.diagnostics().queuedEvents)
    }

    @Test
    fun `413 for a single event holds the queue and rechecks that single request every 30 minutes`() = runBlocking {
        val sdk = sdkWith(events = 3)
        val ids = queued(sdk)
        transport.handler = { http(413) }
        val r = sdk.delivery.sender.run() // 3 -> 1 -> hold
        assertEquals(RunEnd.WAITING, r.end)
        assertEquals(listOf(3, 1), transport.requests.map { eventIds(it).size })
        assertEquals(DeliveryErrorKind.PAYLOAD_TOO_LARGE, sdk.diagnostics().lastDeliveryError?.kind)
        assertEquals(T0 + 30 * MINUTE, r.nextAttemptMillis)
        assertEquals(ids, queued(sdk))
        clock.now = T0 + 30 * MINUTE
        sdk.delivery.sender.run()
        assertEquals(3, transport.requests.size)
        assertEquals(1, eventIds(transport.requests[2]).size)
        transport.handler = { ackAll(it) }
        clock.now += 30 * MINUTE
        assertEquals(RunEnd.QUEUE_EMPTY, sdk.delivery.sender.run().end)
    }

    // ---------- budget and contention ----------

    @Test
    fun `a Worker budget stops starting batches after 10 batches or 30 seconds`() = runBlocking {
        val sdk = sdkWith(events = 700)
        val r = sdk.delivery.sender.run(RunBudget(10, 30_000))
        assertEquals(RunEnd.BUDGET_EXHAUSTED, r.end)
        assertEquals(10, r.batches)
        assertEquals(200, sdk.diagnostics().queuedEvents)

        transport.handler = { clock.now += 20 * SECOND; ackAll(it) }
        val r2 = sdk.delivery.sender.run(RunBudget(10, 30_000))
        assertEquals(RunEnd.BUDGET_EXHAUSTED, r2.end)
        assertEquals(2, r2.batches) // 20 s, then 40 s >= 30 s
    }

    @Test
    fun `foreground and background senders never have two requests in flight and never resend`() = runBlocking {
        val sdk = sdkWith(events = 10)
        transport.gate = CompletableDeferred()
        val a = async(Dispatchers.Default) { sdk.delivery.sender.run() }
        val b = async(Dispatchers.Default) { sdk.delivery.sender.run(RunBudget(10, 30_000)) }
        withTimeout(5_000) { while (transport.requests.isEmpty()) delay(5) }
        delay(200) // the second sender is waiting on the lock
        assertEquals(1, transport.requests.size)
        assertEquals(1, transport.inFlight.get())
        transport.gate!!.complete(Unit)
        listOf(a, b).awaitAll()
        assertEquals(1, transport.maxInFlight.get())
        assertEquals(1, transport.requests.size) // the second run found an empty queue: no second request
        assertEquals(0, sdk.diagnostics().queuedEvents)
    }

    @Test
    fun `a failure seen by one sender makes the other wait instead of resending`() = runBlocking {
        val sdk = sdkWith(jitter = Jitter { b -> b }, events = 3)
        transport.handler = { http(503) }
        val runs = List(4) { async(Dispatchers.Default) { sdk.delivery.sender.run() } }.awaitAll()
        assertTrue(runs.all { it.end == RunEnd.WAITING })
        assertEquals(1, transport.requests.size)
        assertEquals(1, sdk.diagnostics().retryFailureCount)
    }

    @Test
    fun `cancelling a run during HTTP leaves the queue and retry state untouched`() = runBlocking {
        val sdk = sdkWith(events = 3)
        transport.gate = CompletableDeferred()
        val job = async(Dispatchers.Default) { sdk.delivery.sender.run() }
        withTimeout(5_000) { while (transport.requests.isEmpty()) delay(5) }
        job.cancel()
        try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { /* expected */ }
        assertEquals(3, sdk.diagnostics().queuedEvents)
        assertEquals(0, sdk.diagnostics().retryFailureCount)
        assertNull(sdk.diagnostics().nextAttemptEpochMillis)
        transport.gate = null
        sdk.delivery.sender.run() // the lock was released
        assertEquals(0, sdk.diagnostics().queuedEvents)
        assertFalse(transport.requests.isEmpty())
    }

    @Test
    fun `permanent rejection codes are shortened and content free`() = runBlocking {
        val sdk = sdkWith(events = 1)
        transport.handler = { ackAll(it, status = { "rejected" }, code = "C".repeat(500)) }
        sdk.delivery.sender.run()
        assertEquals(64, sdk.diagnostics().recentRejections.single().code.length)
    }

    @Test
    fun `the most recent 100 rejections are kept`() = runBlocking {
        val sdk = sdkWith(events = 0)
        val n = AtomicInteger()
        repeat(3) {
            repeat(50) { sdk.track("e") { property("n", n.incrementAndGet()) } }
            transport.handler = { ackAll(it, status = { "rejected" }) }
            sdk.delivery.sender.run()
        }
        assertEquals(100, sdk.diagnostics().recentRejections.size)
    }
}
