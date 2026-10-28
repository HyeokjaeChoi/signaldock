package local.signaldock.ingestion

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngestRulesTest {
    private val installation = UUID.fromString("47c4d89f-352a-41da-9b31-529ea756515a")
    private val eventId = "0f96a970-a1c4-4b78-9934-fca05faeab20"

    @Test
    fun `numbers compare by value and text never equals a number`() {
        val a = event(mapOf("n" to PropertyValue.Number("1")))
        val b = event(mapOf("n" to PropertyValue.Number("1e0")))
        val c = event(mapOf("n" to PropertyValue.Text("1")))

        assertTrue(sameContent(a, b))
        assertFalse(sameContent(a, c))
    }

    @Test
    fun `rejects names with edge whitespace and decimals outside the double range`() {
        assertEquals("INVALID_TEXT", validate(name = " name").rejectionCode)
        assertEquals("INVALID_PROPERTIES", validate(properties = mapOf("n" to PropertyValue.Number("1e20000"))).rejectionCode)
        assertEquals("INVALID_PROPERTIES", validate(properties = mapOf("n" to PropertyValue.Unsupported)).rejectionCode)
        assertNotNull(validate().event)
    }

    @Test
    fun `a missing platform is android, both values are kept, and any other value is a per-item rejection`() {
        assertEquals(SdkPlatform.ANDROID, validate().event?.sdkPlatform)
        assertEquals(SdkPlatform.ANDROID, validate(sdkPlatform = "android").event?.sdkPlatform)
        assertEquals(SdkPlatform.IOS, validate(sdkPlatform = "ios").event?.sdkPlatform)
        for (bad in listOf("iOS", "IOS", "Android", "ios ", "", "unknown")) {
            val item = validate(sdkPlatform = bad)
            assertNull(item.event, bad)
            assertEquals("INVALID_EVENT", item.rejectionCode, bad)
            assertEquals(eventId, item.eventId, bad) // the SDK still gets the event ID back to delete the event
        }
    }

    @Test
    fun `the same event ID with another platform is not the same content`() {
        val android = event(emptyMap())
        assertTrue(sameContent(android, android.copy()))
        assertFalse(sameContent(android, android.copy(sdkPlatform = SdkPlatform.IOS)))
    }

    @Test
    fun `contract version and size rules fail the whole request`() {
        assertEquals("CONTRACT_VERSION_MISSING", assertFailsWith<RequestFailure> { checkItemContractVersion(null, "1") }.code)
        assertEquals("CONTRACT_VERSION_UNSUPPORTED", assertFailsWith<RequestFailure> { checkItemContractVersion("2", "1") }.code)
        assertEquals("EVENT_TOO_LARGE", assertFailsWith<RequestFailure> { checkEventBytes(16 * 1024 + 1) }.code)
        assertEquals("MALFORMED_REQUEST", assertFailsWith<RequestFailure> { checkEventCount(0) }.code)
    }

    @Test
    fun `plan stores one group per event ID and conflicts store nothing`() {
        val valid = validate()
        val same = valid.copy(index = 1)
        val otherId = "0f96a970-a1c4-4b78-9934-fca05faeab21"
        val first = validate(id = otherId, properties = mapOf("n" to PropertyValue.Number("1")))
        val second = validate(id = otherId, properties = mapOf("n" to PropertyValue.Number("2"))).copy(index = 3)

        val plan = planBatch(ValidatedBatch(listOf(valid, same, first.copy(index = 2), second)))

        assertEquals(1, plan.groups.size)
        val receipts = plan.complete(plan.groups.single().receipts(inserted = true, equal = true))
        assertEquals(
            listOf(ReceiptStatus.ACCEPTED, ReceiptStatus.DUPLICATE, ReceiptStatus.REJECTED, ReceiptStatus.REJECTED),
            receipts.map { it.status },
        )
        assertNull(receipts[0].code)
        assertEquals("CONFLICT", receipts[3].code)
    }

    @Test
    fun `whitespace fixture members are rejected in names and keys, near misses accepted`() {
        val text = java.io.File("../../fixtures/validation/whitespace-set.json").readText()
        val (members, nearMisses) = text.substringAfter("\"members\"").split("\"nearMisses\"").map { part ->
            Regex("U\\+([0-9A-F]{4})").findAll(part).map { it.groupValues[1].toInt(16).toChar() }.toList()
        }
        assertEquals(25, members.size)
        for (c in members) {
            val hex = "U+%04X".format(c.code)
            for (value in listOf("$c", "${c}a", "a$c")) {
                assertEquals("INVALID_TEXT", validate(name = value).rejectionCode, hex)
                assertEquals("INVALID_PROPERTIES", validate(properties = mapOf(value to PropertyValue.Flag(true))).rejectionCode, hex)
            }
        }
        assertEquals(4, nearMisses.size)
        for (c in nearMisses) {
            val value = "${c}a$c"
            assertNotNull(validate(name = value, properties = mapOf(value to PropertyValue.Flag(true))).event, "U+%04X".format(c.code))
        }
    }

    @Test
    fun `accepts shortest round-trip text and Java text of one Double, including subnormals`() {
        // Swift spelling first, then the Java Double.toString spelling of the same Double (ADR-0027 amendment 2026-10-28).
        val pairs = listOf(
            "5e-324" to "4.9E-324",
            "1e-323" to "9.9E-324",
            "1.5e-323" to "1.5E-323",
            "1e-322" to "9.9E-323",
            "2.5e-323" to "2.5E-323",
            "0.1" to "0.1",
            "2.2250738585072014e-308" to "2.2250738585072014E-308",
        )
        for ((swift, java) in pairs) {
            assertEquals(swift.toDouble(), java.toDouble(), swift)
            for (text in listOf(swift, java, "-$swift", "-$java")) {
                assertNotNull(validate(properties = mapOf("n" to PropertyValue.Number(text))).event, text)
            }
        }
    }

    @Test
    fun `still rejects decimals that are not the shortest text of their Double`() {
        for (text in listOf("3e-324", "6e-324", "4.95e-324", "0.10000000000000001", "1e-400", "1e309", "0.1e400")) {
            assertEquals("INVALID_PROPERTIES", validate(properties = mapOf("n" to PropertyValue.Number(text))).rejectionCode, text)
        }
    }

    private fun validate(
        id: String = eventId,
        name: String = "product_viewed",
        properties: Map<String, PropertyValue> = mapOf("n" to PropertyValue.Number("1")),
        sdkPlatform: String? = null,
    ) = validateEvent(
        0,
        EventCandidate(id, false, EventFields(name, "2026-10-09T21:30:00.123Z", "0.1.0", sdkPlatform, properties)),
        installation,
    )

    private fun event(properties: Map<String, PropertyValue>) =
        StoredEvent(UUID.fromString(eventId), installation, "n", java.time.Instant.EPOCH, "0.1.0", SdkPlatform.ANDROID, properties)
}
