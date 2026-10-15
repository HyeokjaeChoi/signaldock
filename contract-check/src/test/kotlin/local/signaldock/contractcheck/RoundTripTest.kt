package local.signaldock.contractcheck

import kotlinx.serialization.json.Json
import org.openapitools.client.models.BatchAckResponse
import org.openapitools.client.models.BatchIngestRequest
import org.openapitools.client.models.ErrorResponse
import org.openapitools.client.models.EventIngestPropertiesValue
import org.openapitools.client.models.EventNameCounts
import org.openapitools.client.models.EventPlanStatusCounts
import org.openapitools.client.models.EventReceiptDetail
import org.openapitools.client.models.EventReceiptPage
import org.openapitools.client.models.EventTimeCounts
import org.openapitools.client.models.ItemAck
import org.openapitools.client.models.PlanStatus
import org.openapitools.client.models.PropertyValue
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

        assertEquals(4, ack.results.size)
        assertEquals(0, ack.results[0].index)
        assertEquals(ItemAck.Status.ACCEPTED, ack.results[0].status)
        assertEquals(ItemAck.Status.DUPLICATE, ack.results[1].status)
        assertEquals(ItemAck.Status.REJECTED, ack.results[2].status)
        assertEquals("UNKNOWN_FIELD", ack.results[2].code)
        // rejected invalid-ID item has no eventId
        assertEquals(ItemAck.Status.REJECTED, ack.results[3].status)
        assertEquals(null, ack.results[3].eventId)

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
    fun `query responses round trip`() {
        fun <T> roundTrip(name: String, serializer: kotlinx.serialization.KSerializer<T>): T {
            val raw = fixture(name)
            val value = json.decodeFromString(serializer, raw)
            assertEquals(json.parseToJsonElement(raw), json.parseToJsonElement(json.encodeToString(serializer, value)), name)
            return value
        }

        val page = roundTrip("event-receipt-page.json", EventReceiptPage.serializer())
        assertEquals(2, page.items.size)
        assertEquals("2026-10-12T01:00:00.500Z", page.items[0].receivedAt)
        assertEquals("2026-10-12T00:00:00.250Z", page.items[0].occurredAt)
        assertTrue(page.nextCursor!!.isNotEmpty())

        val detail = roundTrip("event-receipt-detail.json", EventReceiptDetail.serializer())
        assertEquals(PropertyValue.StringValue("sku-42"), detail.properties["product_id"])
        assertEquals(PropertyValue.BooleanValue(true), detail.properties["in_stock"])
        assertEquals(0, (detail.properties["price"] as PropertyValue.BigDecimalValue).value.compareTo(java.math.BigDecimal("12.5")))
        assertEquals(0, (detail.properties["quantity"] as PropertyValue.BigDecimalValue).value.compareTo(java.math.BigDecimal(3)))
        assertEquals("가나다 🎉", (detail.properties["note"] as PropertyValue.StringValue).value)

        val names = roundTrip("event-name-counts.json", EventNameCounts.serializer())
        assertEquals(listOf("product_view", "cart_add", "purchase"), names.items.map { it.name })
        assertEquals(false, names.truncated)

        val times = roundTrip("event-time-counts.json", EventTimeCounts.serializer())
        assertEquals(EventTimeCounts.Bucket.DAY, times.bucket)
        assertEquals(listOf(0L, 3L, 1L), times.items.map { it.count })
    }

    // ADR-0022: planStatus is an optional addition. The older fixtures above have no planStatus and still
    // decode (absent means "not checked", null in the model); these fixtures carry every status.
    @Test
    fun `plan status is optional on events and counted by status`() {
        fun <T> roundTrip(name: String, serializer: kotlinx.serialization.KSerializer<T>): T {
            val raw = fixture(name)
            val value = json.decodeFromString(serializer, raw)
            assertEquals(json.parseToJsonElement(raw), json.parseToJsonElement(json.encodeToString(serializer, value)), name)
            return value
        }
        assertEquals(listOf(null, null), roundTrip("event-receipt-page.json", EventReceiptPage.serializer()).items.map { it.planStatus })
        assertEquals(null, roundTrip("event-receipt-detail.json", EventReceiptDetail.serializer()).planStatus)

        val page = roundTrip("event-receipt-page-plan-status.json", EventReceiptPage.serializer())
        assertEquals(listOf(PlanStatus.UNPLANNED_EVENT, PlanStatus.INVALID_PROPERTIES, PlanStatus.PLANNED), page.items.map { it.planStatus })
        assertEquals(PlanStatus.INVALID_PROPERTIES, roundTrip("event-receipt-detail-plan-status.json", EventReceiptDetail.serializer()).planStatus)
        val counts = roundTrip("event-plan-status-counts.json", EventPlanStatusCounts.serializer())
        assertEquals(listOf(70L, 20L, 10L, 5L), listOf(counts.planned, counts.unplannedEvent, counts.invalidProperties, counts.notChecked))
        assertEquals(listOf("planned", "unplanned_event", "invalid_properties"), PlanStatus.entries.map { it.value })
        // An unknown status is an error, like an unknown ACK status (Q68): a new value is a contract change.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(EventReceiptPage.serializer(), fixture("event-receipt-page-plan-status.json").replace("unplanned_event", "blocked"))
        }
    }

    @Test
    fun `query responses tolerate added fields but not missing required fields`() {
        val tolerant = Json { ignoreUnknownKeys = true; explicitNulls = true }
        val extended = fixture("event-receipt-page.json").trimEnd().dropLast(1) + ""","added":{"later":1}}"""
        assertEquals(2, tolerant.decodeFromString<EventReceiptPage>(extended).items.size)
        assertFailsWith<kotlinx.serialization.SerializationException> {
            tolerant.decodeFromString<EventReceiptPage>("""{"nextCursor":"x"}""")
        }
        assertFailsWith<kotlinx.serialization.SerializationException> {
            tolerant.decodeFromString<EventTimeCounts>("""{"timeZone":"Asia/Seoul","bucket":"week","items":[]}""")
        }
    }

    // Q68 / spec section 7 "new-response fixture compatibility": the generated models are the ones
    // the SDK (AckValidator) uses, with the same Json settings. A newer server adds fields; the prior
    // models read the ACK unchanged. An unknown status or a missing field is still an error.
    @Test
    fun `new ACK with added fields is read by the prior models, bad ACKs are not`() {
        val prior = Json { ignoreUnknownKeys = true }
        val raw = File("../fixtures/compat/new-ack-added-fields.json").readText()
        val ack = prior.decodeFromString<BatchAckResponse>(raw)
        assertEquals(listOf(ItemAck.Status.ACCEPTED, ItemAck.Status.DUPLICATE), ack.results.map { it.status })
        assertEquals("0f1e2d3c-4b5a-4968-8778-695a4b3c2d1e", ack.results[0].eventId)
        assertFailsWith<kotlinx.serialization.SerializationException> {
            prior.decodeFromString<BatchAckResponse>("""{"results":[{"index":0,"status":"queued","eventId":"x"}]}""")
        }
        assertFailsWith<kotlinx.serialization.SerializationException> {
            prior.decodeFromString<BatchAckResponse>("""{"results":[{"index":0,"eventId":"x"}]}""")
        }
        // the prior request fixture is itself valid for the request models (the server replays it in
        // PriorRequestFixtureTest, server/app)
        val request = json.decodeFromString<BatchIngestRequest>(File("../fixtures/compat/prior-request-v1.json").readText())
        assertEquals(2, request.events.size)
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
