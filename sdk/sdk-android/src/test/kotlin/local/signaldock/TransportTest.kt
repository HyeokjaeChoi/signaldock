package local.signaldock

import kotlinx.coroutines.runBlocking
import local.signaldock.internal.delivery.BatchHttpRequest
import local.signaldock.internal.delivery.BatchTransport
import local.signaldock.internal.delivery.RetrofitBatchTransport
import local.signaldock.internal.delivery.RunEnd
import local.signaldock.internal.delivery.TransportResult
import local.signaldock.internal.init.Clock
import local.signaldock.internal.init.createSdk
import local.signaldock.internal.scheduling.NoopScheduler
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Real Retrofit/OkHttp against MockWebServer on localhost. */
@RunWith(RobolectricTestRunner::class)
class TransportTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() { server = MockWebServer().apply { start() } }

    @After
    fun stop() { runCatching { server.close() } }

    private fun config(callTimeout: kotlin.time.Duration = 30.seconds, readTimeout: kotlin.time.Duration = 10.seconds) = SignalDockConfig(
        endpoint = server.url("/").toString().trimEnd('/'), collectionKey = "secret-key",
        callTimeout = callTimeout, readTimeout = readTimeout,
    )

    private val body = """{"installationId":"i","events":[]}""".toByteArray()

    @Test
    fun `request carries the Bearer key, contract header, JSON content type and the exact body`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"results":[]}""").build())
        val result = RetrofitBatchTransport(config()).send(BatchHttpRequest("1", body)) as TransportResult.Http
        assertEquals(200, result.status)
        assertEquals("""{"results":[]}""", result.body)
        val r = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", r.method)
        assertEquals("/v1/events:batch", r.url.encodedPath)
        assertEquals("Bearer secret-key", r.headers["Authorization"])
        assertEquals("1", r.headers["SignalDock-Contract-Version"])
        assertEquals("application/json", r.headers["Content-Type"])
        assertNull(r.headers["Content-Encoding"])
        assertArrayEquals(body, r.body!!.toByteArray())
    }

    @Test
    fun `a base path in the endpoint is kept`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val cfg = config().copy(endpoint = server.url("/api/").toString())
        RetrofitBatchTransport(cfg).send(BatchHttpRequest("1", body))
        assertEquals("/api/v1/events:batch", server.takeRequest(5, TimeUnit.SECONDS)!!.url.encodedPath)
    }

    @Test
    fun `redirects are not followed`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/elsewhere").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"results":[]}""").build())
        val result = RetrofitBatchTransport(config()).send(BatchHttpRequest("1", body)) as TransportResult.Http
        assertEquals(302, result.status)
        assertNull(result.body)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Retry-After and the status of an error response are passed through`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "120").body("""{"code":"BUSY","message":"x"}""").build())
        val result = RetrofitBatchTransport(config()).send(BatchHttpRequest("1", body)) as TransportResult.Http
        assertEquals(503, result.status)
        assertEquals("120", result.retryAfter)
        assertNull(result.body) // bodies are read only for 200
    }

    @Test
    fun `a response that does not arrive in time is a TIMEOUT`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").headersDelay(3, TimeUnit.SECONDS).build())
        val cfg = config(callTimeout = 1.seconds, readTimeout = 300.milliseconds)
        val result = RetrofitBatchTransport(cfg).send(BatchHttpRequest("1", body))
        assertEquals(DeliveryErrorKind.TIMEOUT, (result as TransportResult.Failed).kind)
    }

    @Test
    fun `a refused connection is a NETWORK failure`() = runBlocking {
        val cfg = config()
        server.close()
        val result = RetrofitBatchTransport(cfg).send(BatchHttpRequest("1", body))
        assertEquals(DeliveryErrorKind.NETWORK, (result as TransportResult.Failed).kind)
    }

    @Test
    fun `an ACK body over 1 MiB is not read`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat((1 shl 20) + 10)).build())
        val result = RetrofitBatchTransport(config()).send(BatchHttpRequest("1", body)) as TransportResult.Http
        assertEquals(200, result.status)
        assertNull(result.body)
    }

    @Test
    fun `end to end - tracked events reach the server and a valid ACK empties the queue`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val fake = BatchHttpRequest("1", request.body!!.toByteArray())
                val ack = ackAll(fake, status = { i -> if (i == 1) "duplicate" else "accepted" })
                return MockResponse.Builder().code(200).body(ack.body!!).build()
            }
        }
        val sdk = createSdk(
            context, config(), Clock { 1_760_000_000_123L }, SequenceIds(),
            scheduler = NoopScheduler, foregroundTriggers = false,
        )
        repeat(3) { sdk.track("e") { property("n", it) } }
        val r = sdk.delivery.sender.run()
        assertEquals(RunEnd.QUEUE_EMPTY, r.end)
        assertEquals(1, server.requestCount)
        assertEquals(0, sdk.diagnostics().queuedEvents)
        assertEquals(AckDiagnostic(1_760_000_000_123L, 2, 1, 0), sdk.diagnostics().lastAck)
        assertTrue(sdk.diagnostics().lastDeliveryError == null)
    }

    @Test
    fun `end to end - a 401 holds the queue`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"code":"UNAUTHORIZED","message":"x"}""").build())
        val sdk = createSdk(
            context, config(), Clock { 1_760_000_000_123L }, SequenceIds(),
            scheduler = NoopScheduler, foregroundTriggers = false,
        )
        sdk.track("e")
        assertEquals(RunEnd.WAITING, sdk.delivery.sender.run().end)
        assertEquals(1, sdk.diagnostics().queuedEvents)
        assertEquals(DeliveryErrorKind.AUTH_OR_CONFIGURATION, sdk.diagnostics().lastDeliveryError?.kind)
        assertEquals(1_760_000_000_123L + 30 * 60_000L, sdk.diagnostics().nextAttemptEpochMillis)
    }

    // ---- Q119 to Q124 (issue 21) ----

    private fun transportClient(cfg: SignalDockConfig) = RetrofitBatchTransport(cfg).client

    @Test
    fun `Q119 Q120 - defaults are a 30 s call timeout and 10 s connect, read and write timeouts, applied to the client`() {
        val d = SignalDockConfig(endpoint = "http://localhost", collectionKey = "k")
        assertEquals(listOf(30.seconds, 10.seconds, 10.seconds, 10.seconds), listOf(d.callTimeout, d.connectTimeout, d.readTimeout, d.writeTimeout))
        val c = transportClient(d)
        assertEquals(listOf(30_000, 10_000, 10_000, 10_000), listOf(c.callTimeoutMillis, c.connectTimeoutMillis, c.readTimeoutMillis, c.writeTimeoutMillis))
    }

    @Test
    fun `Q119 - the whole-call timeout ends a slow response even when the read timeout is long`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").headersDelay(5, TimeUnit.SECONDS).build())
        val t0 = System.nanoTime()
        val result = RetrofitBatchTransport(config(callTimeout = 500.milliseconds, readTimeout = 10.seconds)).send(BatchHttpRequest("1", body))
        assertEquals(DeliveryErrorKind.TIMEOUT, (result as TransportResult.Failed).kind)
        assertTrue("ended by the call timeout", (System.nanoTime() - t0) / 1_000_000 < 3_000)
    }

    @Test
    fun `Q120 - the read timeout ends a slow response even when the call timeout is long`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").headersDelay(5, TimeUnit.SECONDS).build())
        val t0 = System.nanoTime()
        val result = RetrofitBatchTransport(config(callTimeout = 30.seconds, readTimeout = 300.milliseconds)).send(BatchHttpRequest("1", body))
        assertEquals(DeliveryErrorKind.TIMEOUT, (result as TransportResult.Failed).kind)
        assertTrue("ended by the read timeout", (System.nanoTime() - t0) / 1_000_000 < 3_000)
    }

    // ---- Q120 behavior: connect and write timeouts against real sockets, with the SDK sender ----

    private suspend fun sdkWith(cfg: SignalDockConfig, transport: BatchTransport = RetrofitBatchTransport(cfg)) =
        createSdk(context, cfg, Clock { 1_760_000_000_123L }, SequenceIds(), transport = transport, scheduler = NoopScheduler, foregroundTriggers = false)

    @Test
    fun `Q120 - a connect that never completes ends at the connect timeout as TIMEOUT and the sender keeps the queue`() = runBlocking {
        // Loopback server with backlog 1 that never accepts. Raw sockets fill the accept queue; the next
        // SYN gets no answer, so the SDK connect hangs until connectTimeout (no external network needed).
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val fillers = mutableListOf<Socket>()
        try {
            var full = false
            repeat(20) {
                if (full) return@repeat
                val s = Socket()
                try { s.connect(listener.localSocketAddress, 200); fillers += s } catch (_: java.io.IOException) { s.close(); full = true }
            }
            assertTrue("test setup: the accept queue did not fill", full)
            val cfg = SignalDockConfig(
                endpoint = "http://127.0.0.1:${listener.localPort}", collectionKey = "secret-key",
                callTimeout = 30.seconds, connectTimeout = 300.milliseconds,
            )
            val sdk = sdkWith(cfg)
            sdk.track("e")
            val t0 = System.nanoTime()
            assertEquals(RunEnd.WAITING, sdk.delivery.sender.run().end)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("ended by the connect timeout, not the 30 s call timeout: $ms ms", ms < 5_000)
            val d = sdk.diagnostics()
            assertEquals(DeliveryErrorKind.TIMEOUT, d.lastDeliveryError?.kind)
            assertEquals(1, d.queuedEvents)
            assertEquals(1, d.retryFailureCount)
        } finally {
            fillers.forEach { runCatching { it.close() } }
            listener.close()
        }
    }

    @Test
    fun `Q120 - a write the peer never reads ends at the write timeout as TIMEOUT and the sender keeps the queue`() = runBlocking {
        // The server accepts and never reads. A 64 MB body cannot fit in the socket buffers, so the write blocks.
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val accepted = mutableListOf<Socket>()
        val acceptor = Thread { runCatching { while (true) accepted += listener.accept() } }.apply { isDaemon = true; start() }
        try {
            val cfg = SignalDockConfig(
                endpoint = "http://127.0.0.1:${listener.localPort}", collectionKey = "secret-key",
                callTimeout = 30.seconds, writeTimeout = 300.milliseconds,
            )
            val real = RetrofitBatchTransport(cfg)
            val big = ByteArray(64 shl 20)
            val sdk = sdkWith(cfg, object : BatchTransport {
                override suspend fun send(request: BatchHttpRequest) = real.send(BatchHttpRequest(request.contractVersion, big))
            })
            sdk.track("e")
            val t0 = System.nanoTime()
            assertEquals(RunEnd.WAITING, sdk.delivery.sender.run().end)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("ended by the write timeout, not the 30 s call timeout: $ms ms", ms < 5_000)
            val d = sdk.diagnostics()
            assertEquals(DeliveryErrorKind.TIMEOUT, d.lastDeliveryError?.kind)
            assertEquals(1, d.queuedEvents)
            assertEquals(1, d.retryFailureCount)
        } finally {
            listener.close()
            accepted.forEach { runCatching { it.close() } }
            acceptor.interrupt()
        }
    }

    @Test
    fun `Q121 - host overrides reach the client (invalid values are covered by LifecycleTest invalid configuration)`() {
        val cfg = config().copy(callTimeout = 7.seconds, connectTimeout = 2.seconds, readTimeout = 3.seconds, writeTimeout = 4.seconds)
        val c = transportClient(cfg)
        assertEquals(listOf(7_000, 2_000, 3_000, 4_000), listOf(c.callTimeoutMillis, c.connectTimeoutMillis, c.readTimeoutMillis, c.writeTimeoutMillis))
    }

    @Test
    fun `Q122 - retryOnConnectionFailure is on, and the SDK sender alone records the failure after the final transport result`() = runBlocking {
        assertTrue(transportClient(config()).retryOnConnectionFailure)
        // The connection is cut after the request arrived (a lost ACK). OkHttp has no other route to
        // recover on, so this is the final result of the Call and the sender records exactly one failure.
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        val sdk = createSdk(context, config(), Clock { 1_760_000_000_123L }, SequenceIds(), scheduler = NoopScheduler, foregroundTriggers = false)
        sdk.track("e")
        assertEquals(RunEnd.WAITING, sdk.delivery.sender.run().end)
        val d = sdk.diagnostics()
        assertEquals(1, d.queuedEvents)
        assertEquals(1, d.retryFailureCount)
        assertEquals(DeliveryErrorKind.NETWORK, d.lastDeliveryError?.kind)
        assertTrue(d.nextAttemptEpochMillis != null)
    }

    @Test
    fun `Q123 - the client does not follow redirects, and a 302 keeps the queue under the configuration hold`() = runBlocking {
        val c = transportClient(config())
        assertTrue(!c.followRedirects && !c.followSslRedirects)
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/elsewhere").build())
        val sdk = createSdk(context, config(), Clock { 1_760_000_000_123L }, SequenceIds(), scheduler = NoopScheduler, foregroundTriggers = false)
        sdk.track("e")
        assertEquals(RunEnd.WAITING, sdk.delivery.sender.run().end)
        assertEquals(1, server.requestCount)
        assertEquals(1, sdk.diagnostics().queuedEvents)
        assertEquals(DeliveryErrorKind.AUTH_OR_CONFIGURATION, sdk.diagnostics().lastDeliveryError?.kind)
        assertEquals(302, sdk.diagnostics().lastDeliveryError?.httpStatus)
    }

    @Test
    fun `Q124 - a 503 with a positive Retry-After keeps the queue and the next attempt honors it`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "120").body("""{"code":"SERVICE_BUSY","message":"x"}""").build())
        val sdk = createSdk(context, config(), Clock { 1_760_000_000_123L }, SequenceIds(), scheduler = NoopScheduler, foregroundTriggers = false)
        sdk.track("e")
        assertEquals(RunEnd.WAITING, sdk.delivery.sender.run().end)
        val d = sdk.diagnostics()
        assertEquals(1, d.queuedEvents)
        assertEquals(DeliveryErrorKind.SERVER_ERROR, d.lastDeliveryError?.kind)
        assertEquals(1, d.retryFailureCount)
        assertTrue(d.nextAttemptEpochMillis!! >= 1_760_000_000_123L + 120_000L)
        // Before that time the sender sends nothing.
        assertEquals(RunEnd.WAITING, sdk.delivery.sender.run().end)
        assertEquals(1, server.requestCount)
    }
}
