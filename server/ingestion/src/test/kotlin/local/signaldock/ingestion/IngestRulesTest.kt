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

    private fun validate(
        id: String = eventId,
        name: String = "product_viewed",
        properties: Map<String, PropertyValue> = mapOf("n" to PropertyValue.Number("1")),
    ) = validateEvent(
        0,
        EventCandidate(id, false, EventFields(name, "2026-10-09T21:30:00.123Z", "0.1.0", properties)),
        installation,
    )

    private fun event(properties: Map<String, PropertyValue>) =
        StoredEvent(UUID.fromString(eventId), installation, "n", java.time.Instant.EPOCH, "0.1.0", properties)
}
