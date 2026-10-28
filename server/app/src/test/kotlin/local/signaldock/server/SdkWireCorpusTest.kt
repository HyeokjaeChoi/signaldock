package local.signaldock.server

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drift guard for the SDK/server wire contract (ADR-0027, ADR-0047 D10). The files in
 * `fixtures/sdk-wire/<platform>/` are request bodies that the real SDK of that platform builds (the
 * SDK test keeps them equal to its output). The real request parser and the per-item rules must
 * accept all of them: no request-level failure and no rejected item. Every event must carry the
 * `sdkPlatform` of its folder (ADR-0046 decision 3): the server default is for old Android SDKs only.
 */
class SdkWireCorpusTest {
    private val root = File("../../fixtures/sdk-wire")
    private val files = root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        .flatMap { dir -> dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.map { dir.name to it } }

    @Test
    fun `corpus is present`() {
        assertTrue(files.count { it.first == "android" } >= 9, "expected the Android SDK corpus in fixtures/sdk-wire/android")
        assertEquals(
            files.filter { it.first == "android" }.map { it.second.name },
            files.filter { it.first == "ios" }.map { it.second.name },
            "the iOS corpus has the case names of the Android corpus (ADR-0047 D10)",
        )
        assertTrue(root.listFiles { f -> f.isFile }.orEmpty().isEmpty(), "fixtures/sdk-wire has files outside a platform folder")
    }

    @Test
    fun `server accepts every request body the SDK builds`() {
        files.forEach { (platform, file) ->
            val name = "$platform/${file.name}"
            val batch = try {
                parseBatch(file.readBytes(), "1")
            } catch (e: Exception) {
                throw AssertionError("$name: request-level failure ${e}", e)
            }
            assertTrue(batch.items.isNotEmpty(), name)
            batch.items.forEach { item ->
                assertEquals(null, item.rejectionCode, "$name item ${item.index}")
                assertNotNull(item.event, "$name item ${item.index}")
            }
            // The stored event has the server default, so check the raw body: the SDK itself must send the field.
            val events = Json.parseToJsonElement(file.readText()).jsonObject.getValue("events").jsonArray
            events.forEachIndexed { i, e ->
                assertEquals(platform, (e as JsonObject)["sdkPlatform"]?.jsonPrimitive?.content, "$name event $i: sdkPlatform missing or not the folder platform")
            }
        }
    }

    /**
     * ADR-0047 D10: cases with the same name have equal JSON values on both platforms. Numbers compare by value (Kotlin
     * writes `1.0E-7`, Swift `1e-7`), and `sdkPlatform` and `sdkVersion` of each event are left out. Byte equality is not a goal.
     */
    @Test
    fun `the same case has equal JSON values on both platforms`() {
        fun comparable(file: File): JsonObject {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val events = root.getValue("events").jsonArray.map { e ->
                val fields = e.jsonObject.filterKeys { it != "sdkPlatform" && it != "sdkVersion" }.toMutableMap()
                fields["properties"] = JsonObject(fields.getValue("properties").jsonObject.mapValues { (_, v) -> normalize(v) })
                JsonObject(fields)
            }
            return JsonObject(root + ("events" to JsonArray(events)))
        }
        val android = files.filter { it.first == "android" }.associate { it.second.name to it.second }
        files.filter { it.first == "ios" }.forEach { (_, file) ->
            assertEquals(comparable(android.getValue(file.name)), comparable(file), "${file.name}: iOS and Android differ")
        }
    }

    /** A number as its `Double` value (as the Swift comparison does): `1`, `1.0` and `1.0E0` are one number, and so are `4.9E-324` and `5e-324`. Strings and booleans stay as they are. */
    private fun normalize(v: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (v) {
        is JsonObject -> JsonObject(v.mapValues { (_, x) -> normalize(x) })
        is JsonArray -> JsonArray(v.map { normalize(it) })
        is JsonPrimitive -> if (!v.isString && v.content != "true" && v.content != "false" && v.content != "null") {
            JsonPrimitive(v.content.toDouble() + 0.0) // + 0.0: -0 equals 0
        } else {
            v
        }
    }
}
