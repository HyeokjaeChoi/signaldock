package local.signaldock.server

import kotlinx.serialization.json.Json
import local.signaldock.ingestion.RequestFailure
import local.signaldock.ingestion.sameContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IngestValidationTest {
    @Test
    fun `accepts offsets emoji and exact numeric equality`() {
        val first = parse(batch(event(properties = """{"n":1,"emoji":"🎉"}""", occurredAt = "2026-10-10T06:30:00.123000+09:00")))
            .items.single().event
        val second = parse(batch(event(properties = """{"emoji":"🎉","n":1e0}""", occurredAt = "2026-10-09T21:30:00.123Z")))
            .items.single().event

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(sameContent(first, second))
    }

    @Test
    fun `rejects unknown request fields for the whole request`() {
        val error = assertFailsWith<RequestFailure> {
            parse(batch(event()).dropLast(1).plus(",\"unknown\":true}"))
        }
        assertEquals("UNKNOWN_FIELD", error.code)
        assertEquals(400, httpStatusOf(error.code))
    }

    @Test
    fun `keeps parseable invalid items as partial rejections`() {
        val valid = event(id = "0f96a970-a1c4-4b78-9934-fca05faeab20")
        val invalid = event(id = "0f96a970-a1c4-4b78-9934-fca05faeab21", name = " invalid")
        val parsed = parse(batch("$valid,$invalid"))

        assertNotNull(parsed.items[0].event)
        assertEquals("INVALID_TEXT", parsed.items[1].rejectionCode)
    }

    @Test
    fun `rejects duplicate property keys without collapsing them`() {
        val parsed = parse(batch(event(properties = """{"n":1,"n":1}""")))
        assertEquals("DUPLICATE_PROPERTY_KEY", parsed.items.single().rejectionCode)
    }

    @Test
    fun `rejects an empty batch and an oversized event at request level`() {
        assertEquals("MALFORMED_REQUEST", assertFailsWith<RequestFailure> { parse(batch("")) }.code)
        val large = "x".repeat(17 * 1024)
        val error = assertFailsWith<RequestFailure> {
            parse(batch(event(properties = Json.encodeToString(mapOf("value" to large)))))
        }
        assertEquals(413, httpStatusOf(error.code))
    }

    @Test
    fun `rejects excessive JSON nesting as a malformed request`() {
        val deeplyNested = "[".repeat(256 * 1024)

        val error = assertFailsWith<RequestFailure> { parse(deeplyNested) }

        assertEquals("MALFORMED_REQUEST", error.code)
    }

    @Test
    fun `rejects a lone surrogate escape per item and keeps valid siblings`() {
        val id = { n: Int -> "0f96a970-a1c4-4b78-9934-fca05faeab2$n" }
        val parsed = parse(
            batch(
                listOf(
                    event(id = id(0)),
                    event(id = id(1), name = "bad\\ud800"),
                    event(id = id(2), properties = """{"k":"\udc00"}"""),
                    event(id = id(3), properties = """{"a\ud83d":1}"""),
                    event(id = id(4), properties = """{"k":"😀"}"""),
                ).joinToString(","),
            ),
        )

        assertEquals(listOf(null, "INVALID_TEXT", "INVALID_PROPERTIES", "INVALID_PROPERTIES", null), parsed.items.map { it.rejectionCode })
    }

    @Test
    fun `rejects a nested property value per item however deep`() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        val parsed = parse(
            batch(
                listOf(
                    event(id = "0f96a970-a1c4-4b78-9934-fca05faeab20"),
                    event(id = "0f96a970-a1c4-4b78-9934-fca05faeab21", properties = """{"k":{"a":{"b":[{"c":[1,"x",true,null]}]}}}"""),
                    event(id = "0f96a970-a1c4-4b78-9934-fca05faeab22", properties = """{"k":$deep}"""),
                    event(id = "0f96a970-a1c4-4b78-9934-fca05faeab23", properties = """{"k":[]}"""),
                ).joinToString(","),
            ),
        )

        assertEquals(listOf(null, "INVALID_PROPERTIES", "INVALID_PROPERTIES", "INVALID_PROPERTIES"), parsed.items.map { it.rejectionCode })
    }

    @Test
    fun `keeps raw syntax errors inside a nested property value at request level`() {
        for (bad in listOf("""{"k":[1,}""", """{"k":{"a":1,}}""", """{"k":[1 2]}""", """{"k":{"a" 1}}""", """{"k":[[[[[[[[1]]}"""))
            assertEquals("MALFORMED_REQUEST", assertFailsWith<RequestFailure> { parse(batch(event(properties = bad))) }.code, bad)
    }

    @Test
    fun `rejects occurredAt outside the supported UTC year range per item`() {
        val parsed = parse(batch(event(occurredAt = "+300000-01-01T00:00:00.000Z")))

        assertEquals("INVALID_OCCURRED_AT", parsed.items.single().rejectionCode)
    }

    @Test
    fun `rejects decimals outside the finite IEEE double range`() {
        val underflow = parse(batch(event(properties = """{"n":1e-20000}""")))
        val overflow = parse(batch(event(properties = """{"n":1e20000}""")))

        assertEquals("INVALID_PROPERTIES", underflow.items.single().rejectionCode)
        assertEquals("INVALID_PROPERTIES", overflow.items.single().rejectionCode)
    }

    private fun batch(events: String) = """{"installationId":"47c4d89f-352a-41da-9b31-529ea756515a","events":[$events]}"""

    private fun parse(source: String) = parseBatch(source.encodeToByteArray(), "1")

    private fun event(
        id: String = "0f96a970-a1c4-4b78-9934-fca05faeab20",
        name: String = "product_viewed",
        occurredAt: String = "2026-10-09T21:30:00.123Z",
        properties: String = """{"n":1}""",
    ) = """{"eventId":"$id","name":"$name","occurredAt":"$occurredAt","properties":$properties,"sdkVersion":"0.1.0","contractVersion":"1"}"""
}
