package local.signaldock

import kotlinx.coroutines.runBlocking
import local.signaldock.internal.delivery.BatchHttpRequest
import local.signaldock.internal.delivery.RunEnd
import local.signaldock.internal.delivery.TransportResult
import local.signaldock.internal.delivery.parseDeprecationMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DEPRECATION = "@1767225600" // 2026-01-01T00:00:00Z
private const val SUNSET = "Thu, 31 Dec 2026 23:59:59 GMT"
private const val SUNSET_MILLIS = 1_798_761_599_000L

/** ADR-0030: the SDK reads Deprecation and Sunset, warns once, shows them in diagnostics, and never changes delivery. */
@RunWith(RobolectricTestRunner::class)
class DeprecationTest {
    private val logger = FakeLogger()
    private val transport = FakeTransport()

    private fun withHeaders(deprecation: String?, sunset: String?): suspend (BatchHttpRequest) -> TransportResult =
        { req ->
            val a = ackAll(req)
            TransportResult.Http(a.status, a.body, null, deprecation, sunset)
        }

    private suspend fun sendBatches(n: Int): SignalDock {
        val sdk = newSdk(transport = transport, logger = logger)
        repeat(n) {
            sdk.track("e")
            assertEquals(RunEnd.QUEUE_EMPTY, sdk.delivery.sender.run().end)
        }
        return sdk
    }

    private fun deprecationWarnings() = logger.warnings.filter { it.contains("deprecated") }

    @Test
    fun `valid headers give one WARN across many requests and show in diagnostics`() = runBlocking {
        transport.handler = withHeaders(DEPRECATION, SUNSET)
        val sdk = sendBatches(5)
        assertEquals(5, transport.requests.size)
        assertEquals(1, deprecationWarnings().size)
        assertTrue(deprecationWarnings()[0].contains("2026-12-31T23:59:59Z"))
        val d = sdk.diagnostics().contractDeprecation!!
        assertEquals(1_767_225_600_000L, d.deprecatedAtEpochMillis)
        assertEquals(SUNSET_MILLIS, d.sunsetAtEpochMillis)
    }

    @Test
    fun `absent headers leave nothing`() = runBlocking {
        val sdk = sendBatches(2)
        assertTrue(deprecationWarnings().isEmpty())
        assertNull(sdk.diagnostics().contractDeprecation)
    }

    @Test
    fun `malformed headers are ignored and delivery is unchanged`() = runBlocking {
        val cases = listOf("true" to "soon", "@abc" to "2026-12-31", "@" to "", "@99999999999999999999" to "Fri, 31 Dec 2026 23:59:59 GMT", "x" to "Thu, 31 Dec 2026 23:59:59 GMTbogus")
        for ((dep, sun) in cases) {
            transport.handler = withHeaders(dep, sun)
            val sdk = sendBatches(1)
            assertTrue("$dep / $sun", deprecationWarnings().isEmpty())
            assertNull(sdk.diagnostics().contractDeprecation)
            assertEquals(0, sdk.diagnostics().queuedEvents)
        }
    }

    @Test
    fun `one valid header is enough and the other field stays null`() = runBlocking {
        transport.handler = withHeaders("garbage", SUNSET)
        val d = sendBatches(1).diagnostics().contractDeprecation!!
        assertNull(d.deprecatedAtEpochMillis)
        assertEquals(SUNSET_MILLIS, d.sunsetAtEpochMillis)
    }

    @Test
    fun `delivery results are identical with and without the headers`() = runBlocking {
        suspend fun outcome(dep: String?, sun: String?): List<Any?> {
            val sdk = newSdk(transport = FakeTransport(withHeaders(dep, sun)), logger = FakeLogger())
            repeat(3) { sdk.track("e") }
            val run = sdk.delivery.sender.run()
            val d = sdk.diagnostics()
            return listOf(run.end, run.batches, d.queuedEvents, d.lastAck?.accepted, d.lastDeliveryError, d.retryFailureCount, d.nextAttemptEpochMillis)
        }
        assertEquals(outcome(null, null), outcome(DEPRECATION, SUNSET))
    }

    @Test
    fun `an error response with the headers is read and still handled as before`() = runBlocking {
        transport.handler = { TransportResult.Http(400, null, null, DEPRECATION, SUNSET) }
        val sdk = newSdk(transport = transport, logger = logger)
        sdk.track("e")
        sdk.delivery.sender.run()
        val d = sdk.diagnostics()
        assertEquals(DeliveryErrorKind.CONTRACT, d.lastDeliveryError!!.kind)
        assertEquals(1, d.queuedEvents)
        assertEquals(SUNSET_MILLIS, d.contractDeprecation!!.sunsetAtEpochMillis)
    }

    @Test
    fun `Deprecation parser accepts only an integer structured date`() {
        assertEquals(1_767_225_600_000L, parseDeprecationMillis(" @1767225600 "))
        assertEquals(0L, parseDeprecationMillis("@0"))
        for (bad in listOf(null, "", "true", "@", "@1.5", "1767225600", "@1767225600, @1", "@999999999999999")) {
            assertNull(bad, parseDeprecationMillis(bad))
        }
    }
}
