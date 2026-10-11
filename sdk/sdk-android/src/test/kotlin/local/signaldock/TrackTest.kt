package local.signaldock

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import local.signaldock.internal.QueueLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackTest {
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun `track stores one event with common fields and typed properties`() = runBlocking {
        val sdk = newSdk()
        val r = sdk.track("product_viewed") {
            property("product_id", "P-001")
            property("quantity", 2)
            property("is_test", true)
            property("price", 12.5)
        }
        val id = (r as TrackResult.Success).eventId
        val row = sdk.dao.oldest(10).single()
        assertEquals(id, row.eventId)
        assertEquals("1", row.contractVersion)
        // size = the event as sent: a single-event batch request, wrapper included
        val request = "{\"installationId\":\"${row.installationId}\",\"events\":[${row.payloadJson}]}"
        assertEquals(request.toByteArray().size, row.sizeBytes)
        val o = Json.parseToJsonElement(row.payloadJson).jsonObject
        assertEquals(id, o["eventId"]!!.jsonPrimitive.content)
        assertEquals("product_viewed", o["name"]!!.jsonPrimitive.content)
        assertEquals("2025-10-09T08:53:20.123Z", o["occurredAt"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", o["sdkVersion"]!!.jsonPrimitive.content)
        assertEquals("1", o["contractVersion"]!!.jsonPrimitive.content)
        val p = o["properties"] as JsonObject
        assertEquals("P-001", p["product_id"]!!.jsonPrimitive.content)
        assertEquals("2", p["quantity"]!!.jsonPrimitive.content) // a JSON number, not a string
        assertTrue(!p["quantity"]!!.jsonPrimitive.isString)
        assertTrue(p["is_test"]!!.jsonPrimitive.boolean)
        assertEquals("12.5", p["price"]!!.jsonPrimitive.content)
    }

    @Test
    fun `event ids are random UUIDv4`() = runBlocking {
        val sdk = newSdk(ids = local.signaldock.internal.RandomUuidV4)
        val ids = (1..20).map { (sdk.track("e") as TrackResult.Success).eventId }
        assertEquals(20, ids.toSet().size)
        assertTrue(ids.all { uuidV4.matches(it) })
    }

    private suspend fun SignalDock.invalid(name: String, block: EventBuilder.() -> Unit = {}): InvalidReason {
        val r = track(name, block)
        assertTrue("expected Invalid but was $r", r is TrackResult.Invalid)
        assertEquals("nothing stored on invalid input", 0, dao.usage().count)
        return (r as TrackResult.Invalid).reason
    }

    @Test
    fun `names and keys are rejected, never trimmed or normalized`() = runBlocking {
        val sdk = newSdk()
        assertEquals(InvalidReason.EMPTY_NAME, sdk.invalid(""))
        assertEquals(InvalidReason.EMPTY_NAME, sdk.invalid("   "))
        assertEquals(InvalidReason.LEADING_OR_TRAILING_WHITESPACE, sdk.invalid(" a"))
        assertEquals(InvalidReason.LEADING_OR_TRAILING_WHITESPACE, sdk.invalid("a\n"))
        assertEquals(InvalidReason.FORBIDDEN_CHARACTER, sdk.invalid("a\u0000b"))
        assertEquals(InvalidReason.FORBIDDEN_CHARACTER, sdk.invalid("a\uD800"))
        assertEquals(InvalidReason.FORBIDDEN_CHARACTER, sdk.invalid("\uDC00a"))
        assertEquals(InvalidReason.EMPTY_NAME, sdk.invalid("ok") { property("", 1) })
        assertEquals(InvalidReason.LEADING_OR_TRAILING_WHITESPACE, sdk.invalid("ok") { property("k ", 1) })
        assertEquals(InvalidReason.FORBIDDEN_CHARACTER, sdk.invalid("ok") { property("k\u0000", 1) })
        assertEquals(InvalidReason.FORBIDDEN_CHARACTER, sdk.invalid("ok") { property("k", "v\u0000") })
        assertEquals(InvalidReason.FORBIDDEN_CHARACTER, sdk.invalid("ok") { property("k", "v\uD83D") })
    }

    private val whitespaceSet: List<Char> =
        ((0x09..0x0D) + 0x20 + 0x85 + 0xA0 + 0x1680 + (0x2000..0x200A) + 0x2028 + 0x2029 + 0x202F + 0x205F + 0x3000).map { it.toChar() }

    @Test
    fun `every character of the fixed whitespace set is rejected, others are accepted`() = runBlocking {
        val sdk = newSdk()
        for (c in whitespaceSet) {
            val hex = "U+%04X".format(c.code)
            assertEquals(hex, InvalidReason.EMPTY_NAME, sdk.invalid("$c"))
            assertEquals(hex, InvalidReason.EMPTY_NAME, sdk.invalid("ok") { property("$c$c", 1) })
            assertEquals(hex, InvalidReason.LEADING_OR_TRAILING_WHITESPACE, sdk.invalid("${c}a"))
            assertEquals(hex, InvalidReason.LEADING_OR_TRAILING_WHITESPACE, sdk.invalid("a$c"))
            assertEquals(hex, InvalidReason.LEADING_OR_TRAILING_WHITESPACE, sdk.invalid("ok") { property("${c}k", 1) })
            assertEquals(hex, InvalidReason.LEADING_OR_TRAILING_WHITESPACE, sdk.invalid("ok") { property("k$c", 1) })
        }
        // not in the set: accepted even at the edges (Kotlin isWhitespace() would reject U+001C)
        for (c in listOf('\u001C', '\u200B', '\uFEFF', '\u180E')) {
            assertTrue("U+%04X".format(c.code), sdk.track("${c}a$c") { property("${c}k", 1) } is TrackResult.Success)
        }
    }

    @Test
    fun `internal whitespace, Korean text and string values with edge whitespace are preserved`() = runBlocking {
        val sdk = newSdk()
        assertTrue(sdk.track("장바구니 담기") { property("상품 이름", "  가나다 🎉 ") } is TrackResult.Success)
        val o = Json.parseToJsonElement(sdk.dao.oldest(1).single().payloadJson).jsonObject
        assertEquals("장바구니 담기", o["name"]!!.jsonPrimitive.content)
        assertEquals("  가나다 🎉 ", (o["properties"] as JsonObject)["상품 이름"]!!.jsonPrimitive.content)
        // visually identical, different code points: both accepted as distinct names (no normalization)
        assertTrue(sdk.track("é") is TrackResult.Success)
        assertTrue(sdk.track("é") is TrackResult.Success)
        val names = sdk.dao.oldest(10).map { Json.parseToJsonElement(it.payloadJson).jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("장바구니 담기", "é", "é"), names)
    }

    @Test
    fun `duplicate key fails the whole call even with the same value`() = runBlocking {
        val sdk = newSdk()
        assertEquals(InvalidReason.DUPLICATE_KEY, sdk.invalid("e") { property("k", 1); property("k", 1) })
    }

    @Test
    fun `numbers outside the contract are rejected, boundaries accepted`() = runBlocking {
        val sdk = newSdk()
        val max = 9_007_199_254_740_991L
        assertEquals(InvalidReason.NON_FINITE_NUMBER, sdk.invalid("e") { property("k", Double.NaN) })
        assertEquals(InvalidReason.NON_FINITE_NUMBER, sdk.invalid("e") { property("k", Double.POSITIVE_INFINITY) })
        assertEquals(InvalidReason.NON_FINITE_NUMBER, sdk.invalid("e") { property("k", Double.NEGATIVE_INFINITY) })
        assertEquals(InvalidReason.NUMBER_OUT_OF_RANGE, sdk.invalid("e") { property("k", max + 1) })
        assertEquals(InvalidReason.NUMBER_OUT_OF_RANGE, sdk.invalid("e") { property("k", -max - 1) })
        assertEquals(InvalidReason.NUMBER_OUT_OF_RANGE, sdk.invalid("e") { property("k", Long.MIN_VALUE) })
        assertEquals(InvalidReason.NUMBER_OUT_OF_RANGE, sdk.invalid("e") { property("k", Long.MAX_VALUE) })
        assertEquals(InvalidReason.NUMBER_OUT_OF_RANGE, sdk.invalid("e") { property("k", (max + 1).toDouble()) })
        assertEquals(InvalidReason.NUMBER_OUT_OF_RANGE, sdk.invalid("e") { property("k", 1e20) })
        assertTrue(sdk.track("e") { property("a", max); property("b", -max); property("c", max.toDouble() - 1.0); property("d", 1e-7) } is TrackResult.Success)
        val p = Json.parseToJsonElement(sdk.dao.oldest(1).single().payloadJson).jsonObject["properties"] as JsonObject
        assertEquals("9007199254740991", p["a"]!!.jsonPrimitive.content)
        assertEquals("-9007199254740991", p["b"]!!.jsonPrimitive.content)
    }

    @Test
    fun `event size limit is 16 KiB of transport JSON`() = runBlocking {
        val sdk = newSdk()
        sdk.track("e") { property("k", "") }
        val base = sdk.dao.oldest(1).single().sizeBytes
        val limit = 16 * 1024
        assertTrue(sdk.track("e") { property("k", "x".repeat(limit - base)) } is TrackResult.Success)
        assertEquals(limit, sdk.dao.oldest(2)[1].sizeBytes)
        val r = sdk.track("e") { property("k", "x".repeat(limit - base + 1)) }
        assertEquals(TrackResult.Invalid(InvalidReason.EVENT_TOO_LARGE, null), r)
        assertEquals(2, sdk.dao.usage().count)
    }

    @Test
    fun `size is measured in UTF-8 bytes of escaped JSON`() = runBlocking {
        val sdk = newSdk()
        sdk.track("e") { property("k", "") }
        val base = sdk.dao.oldest(1).single().sizeBytes
        // 3-byte characters: 5462 * 3 = 16386 bytes alone is over the limit although 5462 chars is small.
        val r = sdk.track("e") { property("k", "가".repeat(5462)) }
        assertEquals(InvalidReason.EVENT_TOO_LARGE, (r as TrackResult.Invalid).reason)
        // escaped quotes count two bytes each
        val q = sdk.track("e") { property("k", "\"".repeat((16 * 1024 - base) / 2 + 1)) }
        assertEquals(InvalidReason.EVENT_TOO_LARGE, (q as TrackResult.Invalid).reason)
    }

    @Test
    fun `queue limited by event count keeps existing events`() = runBlocking {
        val sdk = newSdk(limits = QueueLimits(maxEvents = 3))
        repeat(3) { assertTrue(sdk.track("e$it") is TrackResult.Success) }
        assertEquals(TrackResult.QueueFull, sdk.track("overflow"))
        assertEquals(listOf("e0", "e1", "e2"), sdk.dao.oldest(10).map {
            Json.parseToJsonElement(it.payloadJson).jsonObject["name"]!!.jsonPrimitive.content
        })
    }

    @Test
    fun `queue limited by total transport bytes`() = runBlocking {
        val probe = newSdk(ids = local.signaldock.internal.RandomUuidV4)
        probe.track("e")
        val size = probe.dao.oldest(1).single().sizeBytes
        // fresh directory per test via Robolectric, but reuse is fine: use a new limit on a new queue
        val sdk = newSdk(limits = QueueLimits(maxQueueBytes = size * 3L + 1), ids = local.signaldock.internal.RandomUuidV4)
        // the probe event already occupies the shared queue (same DB file): 1 + 2 more fit, the 4th does not
        assertTrue(sdk.track("e") is TrackResult.Success)
        assertTrue(sdk.track("e") is TrackResult.Success)
        assertEquals(TrackResult.QueueFull, sdk.track("e"))
        assertEquals(3, sdk.dao.usage().count)
    }

    @Test
    fun `default limits match the agreed values`() {
        val l = QueueLimits()
        assertEquals(10_000, l.maxEvents)
        assertEquals(16L * 1024 * 1024, l.maxQueueBytes)
        assertEquals(16 * 1024, l.maxEventBytes)
    }

    @Test
    fun `10000 events fill the real default queue, then QueueFull`() = runBlocking {
        val sdk = newSdk()
        repeat(10_000) { assertTrue(sdk.track("e") is TrackResult.Success) }
        assertEquals(TrackResult.QueueFull, sdk.track("e"))
        assertEquals(10_000, sdk.dao.usage().count)
    }

    @Test
    fun `concurrent tracks never exceed the limit`() = runBlocking {
        val sdk = newSdk(limits = QueueLimits(maxEvents = 25))
        val results = (1..100).map { async(kotlinx.coroutines.Dispatchers.Default) { sdk.track("e") } }.awaitAll()
        assertEquals(25, results.count { it is TrackResult.Success })
        assertEquals(75, results.count { it == TrackResult.QueueFull })
        assertEquals(25, sdk.dao.usage().count)
        assertNull(results.filterIsInstance<TrackResult.StorageError>().firstOrNull())
    }
}
