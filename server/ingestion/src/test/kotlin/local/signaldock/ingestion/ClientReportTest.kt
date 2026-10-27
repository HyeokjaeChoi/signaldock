package local.signaldock.ingestion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClientReportTest {
    private fun entry(reason: String = "queue_full", count: String = "3") = RawDiscardedCount(reason, count)
    private fun invalid(sdkVersion: String = "0.1.0", vararg entries: RawDiscardedCount) {
        val error = assertFailsWith<RequestFailure> { validateClientReport(sdkVersion, entries.toList()) }
        assertEquals("INVALID_REPORT", error.code)
    }

    @Test
    fun `accepts every reason by its wire name`() {
        val report = validateClientReport("0.1.0", DiscardReason.entries.map { entry(it.wire, "1") })
        assertEquals(DiscardReason.entries, report.entries.map { it.reason })
        assertEquals(listOf("queue_full", "storage_error", "invalid_event", "event_too_large", "server_rejected"), DiscardReason.entries.map { it.wire })
    }

    @Test
    fun `a missing platform is android, both values are kept, and another value fails like an invalid sdkVersion`() {
        val entries = listOf(entry())
        assertEquals(SdkPlatform.ANDROID, validateClientReport("0.1.0", entries).sdkPlatform)
        assertEquals(SdkPlatform.ANDROID, validateClientReport("0.1.0", entries, "android").sdkPlatform)
        assertEquals(SdkPlatform.IOS, validateClientReport("0.1.0", entries, "ios").sdkPlatform)
        for (bad in listOf("iOS", "Android", "", "ios ", "unknown")) {
            val error = assertFailsWith<RequestFailure>(bad) { validateClientReport("0.1.0", entries, bad) }
            assertEquals("INVALID_REPORT", error.code, bad)
        }
    }

    @Test
    fun `count boundaries`() {
        assertEquals(1, validateClientReport("v", listOf(entry(count = "1"))).entries.single().count)
        assertEquals(1_000_000, validateClientReport("v", listOf(entry(count = "1000000"))).entries.single().count)
        for (count in listOf("0", "-0", "1000001", "-1", "3.0", "3.5", "1e3", "99999999999999999999", "007", "")) {
            invalid("v", entry(count = count))
        }
    }

    @Test
    fun `entry rules`() {
        invalid("v")
        invalid("v", *Array(6) { entry(DiscardReason.entries.getOrElse(it) { DiscardReason.QUEUE_FULL }.wire) })
        invalid("v", entry("queue_full"), entry("queue_full"))
        invalid("v", entry("Queue_Full"))
        invalid("v", entry("unknown"))
        assertEquals(5, validateClientReport("v", DiscardReason.entries.map { entry(it.wire) }).entries.size)
    }

    @Test
    fun `sdkVersion length counts code points`() {
        invalid("", entry())
        validateClientReport("a", listOf(entry()))
        validateClientReport("a".repeat(64), listOf(entry()))
        invalid("a".repeat(65), entry())
        validateClientReport("🎉".repeat(64), listOf(entry())) // 64 code points, 128 chars
        invalid("🎉".repeat(65), entry())
    }

    @Test
    fun `sdkVersion rejects NUL and a lone surrogate`() {
        invalid("0.1\u0000", entry())
        invalid("0.1\uD800", entry())
        invalid("\uDC00x", entry())
    }
}
