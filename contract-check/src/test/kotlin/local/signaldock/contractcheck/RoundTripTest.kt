package local.signaldock.contractcheck

import kotlinx.serialization.json.Json
import org.openapitools.client.models.BatchAckResponse
import org.openapitools.client.models.BatchIngestRequest
import org.openapitools.client.models.ErrorResponse
import org.openapitools.client.models.EventIngestPropertiesValue
import org.openapitools.client.models.ItemAck
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #1: JSON round-trip fixtures for contract v1.
 * Verifies the generated Kotlin DTOs (openapi-generator 7.26.0,
 * kotlinx_serialization) preserve the contract's scalar semantics:
 * flat string/number/boolean properties, no nulls (Q33-35), numeric
 * value equality (Q62), and per-item ACK results (Q59).
 */
class RoundTripTest {

    private val json = Json { explicitNulls = true }

    private fun fixture(name: String): String =
        File("../fixtures/roundtrip/$name").readText()

    @Test
    fun `batch request round trip preserves scalars`() {
        val raw = fixture("batch-request.json")
        val req = json.decodeFromString<BatchIngestRequest>(raw)

        assertEquals("install-9f2c-4b7a-8e1d-3f6a5b4c7d8e", req.installationId)
        assertEquals(2, req.events.size)

        val first = req.events[0]
        assertEquals("product_viewed", first.name)
        // occurredAt stays a String; RFC3339 UTC/ms validation is an adapter concern (Q81-83)
        assertEquals("2026-10-09T21:30:00.123Z", first.occurredAt)
        assertIs<EventIngestPropertiesValue.StringValue>(first.properties["product_id"])
        assertEquals("sku-42", (first.properties["product_id"] as EventIngestPropertiesValue.StringValue).value)
        // unicode survives the round trip
        assertEquals(
            "가나다 unicode 테스트 🎉",
            (first.properties["note"] as EventIngestPropertiesValue.StringValue).value
        )
        assertIs<EventIngestPropertiesValue.BooleanValue>(first.properties["in_stock"])
        assertTrue((first.properties["in_stock"] as EventIngestPropertiesValue.BooleanValue).value)

        val second = req.events[1]
        // numeric offset form is preserved verbatim as a String (Q82)
        assertEquals("2026-10-10T06:30:00.123000+09:00", second.occurredAt)
        // numbers keep full precision via BigDecimal (Q62: compare without precision loss)
        val amount = second.properties["amount"] as EventIngestPropertiesValue.BigDecimalValue
        assertEquals(0, amount.value.compareTo(java.math.BigDecimal("1.0")))
        val discount = second.properties["discount"] as EventIngestPropertiesValue.BigDecimalValue
        assertEquals(0, discount.value.compareTo(java.math.BigDecimal.ZERO))

        // serialize back and compare semantically with the original JSON
        val reserialized = json.parseToJsonElement(json.encodeToString(BatchIngestRequest.serializer(), req))
        assertEquals(json.parseToJsonElement(raw), reserialized)
    }

    @Test
    fun `ack round trip keeps per-item results`() {
        val raw = fixture("batch-ack.json")
        val ack = json.decodeFromString<BatchAckResponse>(raw)

        assertEquals(3, ack.results.size)
        assertEquals(0, ack.results[0].index)
        assertEquals(ItemAck.Status.ACCEPTED, ack.results[0].status)
        assertEquals(ItemAck.Status.DUPLICATE, ack.results[1].status)
        assertEquals(ItemAck.Status.REJECTED, ack.results[2].status)
        assertEquals("UNKNOWN_FIELD", ack.results[2].code)

        val reserialized = json.parseToJsonElement(json.encodeToString(BatchAckResponse.serializer(), ack))
        assertEquals(json.parseToJsonElement(raw), reserialized)
    }

    @Test
    fun `error response round trip`() {
        val raw = fixture("error-400.json")
        val err = json.decodeFromString<ErrorResponse>(raw)
        assertEquals("CONTRACT_HEADER_MISSING", err.code)

        val reserialized = json.parseToJsonElement(json.encodeToString(ErrorResponse.serializer(), err))
        assertEquals(json.parseToJsonElement(raw), reserialized)
    }

    @Test
    fun `null property value is rejected`() {
        val bad = """{"eventId":"e1","name":"n","occurredAt":"2026-10-09T21:30:00.123Z",
            "properties":{"x":null},"sdkVersion":"0.1.0","contractVersion":"1"}"""
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(
                org.openapitools.client.models.EventIngest.serializer(),
                bad
            )
        }
    }

    @Test
    fun `nested property value is rejected`() {
        val bad = """{"eventId":"e1","name":"n","occurredAt":"2026-10-09T21:30:00.123Z",
            "properties":{"x":{"nested":1}},"sdkVersion":"0.1.0","contractVersion":"1"}"""
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(
                org.openapitools.client.models.EventIngest.serializer(),
                bad
            )
        }
    }
}
