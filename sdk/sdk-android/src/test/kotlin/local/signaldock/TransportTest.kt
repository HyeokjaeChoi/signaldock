package local.signaldock

import kotlinx.coroutines.runBlocking
import local.signaldock.internal.delivery.BatchHttpRequest
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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
