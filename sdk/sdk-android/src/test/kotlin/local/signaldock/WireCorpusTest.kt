package local.signaldock

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.signaldock.internal.delivery.buildBatch
import local.signaldock.internal.transport.batchRequestJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Drift guard for the SDK/server wire contract (ADR-0027). It builds request bodies with the real
 * `track()` path and the real batch assembly, then compares them byte for byte with the committed
 * corpus in `fixtures/sdk-wire/android/` (ADR-0047 D10). The server test `SdkWireCorpusTest` parses the same files with
 * the real request parser and expects no request-level failure and no per-item rejection.
 * To refresh the corpus after a deliberate change: `UPDATE_SDK_WIRE=1 sdk/gradlew -p sdk
 * :sdk-android:testDebugUnitTest --tests '*WireCorpusTest'`, then review the diff.
 */
@RunWith(RobolectricTestRunner::class)
class WireCorpusTest {
    private val installationId = "47c4d89f-352a-41da-9b31-529ea756515a"
    private val dir = File("../../fixtures/sdk-wire/android")
    private val max = 9_007_199_254_740_991L

    private suspend fun SignalDock.ok(name: String, block: EventBuilder.() -> Unit = {}) {
        val r = track(name, block)
        assertTrue("track($name) gave $r", r is TrackResult.Success)
    }

    /** The body the sender would build from the queued events (stored payloads, fixed installation id). */
    private suspend fun SignalDock.clear() { dao.deleteByIds(dao.oldest(10_000).map { it.eventId }) }

    private suspend fun SignalDock.body(): String =
        batchRequestJson(installationId, dao.oldest(50).map { it.payloadJson }).also { clear() }

    private fun cases(): Map<String, String> = runBlocking {
        val out = linkedMapOf<String, String>()
        val sdk = newSdk() // one SDK: the test database keeps event ids, so each case clears the queue

        sdk.apply { ok("app_opened") }.let { out["minimal"] = it.body() }

        sdk.apply {
            ok("all_types") {
                property("text", "hello")
                property("empty", "")
                property("yes", true)
                property("no", false)
                property("int", 2)
                property("int_min", Int.MIN_VALUE)
                property("long_max", max)
                property("long_min", -max)
                property("zero", 0)
                property("double", 12.5)
                property("double_small", 1e-7)
                property("double_neg", -0.1)
                property("double_zero", 0.0)
                property("double_neg_zero", -0.0)
                property("double_big", max.toDouble() - 1.0)
                property("double_frac_big", 4503599627370496.5)
                property("double_tiny", java.lang.Double.MIN_VALUE)
                property("double_large_frac", 1.7976931348623157E308 / 1e300)
            }
        }.let { out["all-property-types"] = it.body() }

        sdk.apply {
            ok("unicode_가_🎉") {
                property("한글", "가나다")
                property("emoji", "🎉👨‍👩‍👧‍👦🇰🇷")
                property("astral", "😀𝄞")
                property("bmp_edge", "￿�")
                property("escapes", "quote\" back\\ slash/ nl\n cr\r tab\t bs\b ff\u000c ctl\u0001\u001f del\u007f")
                property("separators", "a b c")
                property("edge_whitespace", "  value ")
                property("emoji_key_🎉", "v")
                property("nfd", "é")
            }
            ok("a") // one-character name
            ok("name with spaces inside")
        }.let { out["unicode"] = it.body() }

        sdk.apply {
            ok("e") { property("k", "") }
            ok("e") { property("k", "x") }
        }.let { out["empty-and-short-strings"] = it.body() }

        // Largest event: transport size exactly 16 KiB (wrapper included).
        sdk.apply {
            ok("e") { property("k", "") }
            val base = dao.oldest(1).single().sizeBytes
            ok("e") { property("k", "x".repeat(16 * 1024 - base)) }
            assertEquals(16 * 1024, dao.oldest(2)[1].sizeBytes)
        }.let { out["max-event-size"] = it.body() }

        // Largest event made of 3-byte and 4-byte characters and escapes.
        sdk.apply {
            ok("e") { property("k", "") }
            val base = dao.oldest(1).single().sizeBytes
            ok("e") { property("k", "가".repeat((16 * 1024 - base) / 3)) }
            ok("e") { property("k", "🎉".repeat((16 * 1024 - base) / 4)) }
            ok("e") { property("k", "\"".repeat((16 * 1024 - base) / 2)) }
        }.let { out["max-event-size-multibyte"] = it.body() }

        // Most properties that fit in one event.
        fun EventBuilder.props(n: Int) = repeat(n) { property("k%05d".format(it), it) }
        var n = 100
        sdk.apply { // probe only
            while (track("e") { props(n + 100) } is TrackResult.Success) n += 100
            while (track("e") { props(n + 1) } is TrackResult.Success) n++
        }
        sdk.clear()
        check(n > 1000) { "expected more than 1000 properties, got $n" }
        sdk.apply { ok("e") { props(n) } }.let { out["max-property-count"] = it.body() }

        // A batch of 50 small events (the event count limit).
        sdk.apply { repeat(50) { i -> ok("e") { property("i", i) } } }
            .let { out["max-batch-events"] = it.body() }

        // A batch cut by buildBatch at the 256 KiB byte limit, from 16 KiB events.
        sdk.apply {
            ok("e") { property("k", "") }
            val base = dao.oldest(1).single().sizeBytes
            repeat(20) { ok("e") { property("k", "x".repeat(16 * 1024 - base)) } }
            val rows = dao.oldest(50).drop(1).map { it.copy(installationId = installationId) }
            val batch = buildBatch(rows)
            assertTrue(batch.body.size <= 256 * 1024 && batch.events.size < rows.size)
            out["max-batch-bytes"] = String(batch.body, Charsets.UTF_8)
            clear()
        }

        out
    }

    @Test
    fun `an event queued by 0_3_0 is sent with its stored bytes and no sdkPlatform (ADR-0006 decision 3)`() {
        val legacy = """{"eventId":"00000000-0000-4000-8000-000000000001","name":"app_opened","occurredAt":"2025-10-09T08:53:20.123Z","properties":{},"sdkVersion":"0.3.0","contractVersion":"1"}"""
        assertEquals("""{"installationId":"$installationId","events":[$legacy]}""", batchRequestJson(installationId, listOf(legacy)))
    }

    @Test
    fun `corpus matches what the SDK builds`() {
        val built = cases()
        // ADR-0046 decision 3: every SDK must send sdkPlatform; the server default is for 0.1.0 to 0.3.0 only.
        built.forEach { (name, body) ->
            val events = Json.parseToJsonElement(body).jsonObject.getValue("events").jsonArray
            assertTrue("$name has no event", events.isNotEmpty())
            events.forEachIndexed { i, e ->
                assertEquals("$name event $i", "android", e.jsonObject["sdkPlatform"]?.jsonPrimitive?.content)
            }
        }
        if (System.getenv("UPDATE_SDK_WIRE") == "1") {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            built.forEach { (name, body) -> File(dir, "$name.json").writeBytes(body.toByteArray(Charsets.UTF_8)) }
        }
        val committed = dir.listFiles { f -> f.extension == "json" }.orEmpty().map { it.name }.sorted()
        assertEquals("corpus files differ from the cases (run with UPDATE_SDK_WIRE=1)", built.keys.map { "$it.json" }.sorted(), committed)
        built.forEach { (name, body) ->
            assertEquals("fixtures/sdk-wire/android/$name.json drifted from the SDK output", File(dir, "$name.json").readText(Charsets.UTF_8), body)
        }
    }
}
