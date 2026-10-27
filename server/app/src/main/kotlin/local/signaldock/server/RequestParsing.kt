package local.signaldock.server

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import local.signaldock.ingestion.ClientReport
import local.signaldock.ingestion.RawDiscardedCount
import local.signaldock.ingestion.validateClientReport
import local.signaldock.ingestion.EventCandidate
import local.signaldock.ingestion.EventFields
import local.signaldock.ingestion.PropertyValue
import local.signaldock.ingestion.RequestFailure
import local.signaldock.ingestion.ValidatedBatch
import local.signaldock.ingestion.checkBatchBytes
import local.signaldock.ingestion.checkEventBytes
import local.signaldock.ingestion.checkEventCount
import local.signaldock.ingestion.checkItemContractVersion
import local.signaldock.ingestion.parseInstallationId
import local.signaldock.ingestion.resolveEventIdConflicts
import local.signaldock.ingestion.validateEvent
import org.openapitools.client.models.EventIngest

/**
 * Containers at this depth or deeper (root 0, events 1, event 2, properties 3, so property values
 * and anything inside an event field) are checked without recursion. The scanner stack stays
 * bounded for any input, and a nested property value is a per-item rejection (ADR-0027).
 */
private const val MAX_SCANNED_DEPTH = 4

private val json = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = true
}

/** HTTP status for a request-level failure code. Rules in `:ingestion` know codes, not statuses. */
internal fun httpStatusOf(code: String): Int = when (code) {
    "BATCH_TOO_LARGE", "PAYLOAD_TOO_LARGE", "EVENT_TOO_LARGE" -> 413
    "COLLECTION_KEY_INVALID", "QUERY_KEY_INVALID" -> 401
    "CONTENT_TYPE_UNSUPPORTED", "CONTENT_ENCODING_UNSUPPORTED" -> 415
    else -> 400
}

/**
 * Parses one raw JSON body without normalizing it before size and duplicate-key checks.
 * This layer owns raw JSON and DTO mapping. The rules in `:ingestion` decide what is valid.
 */
internal fun parseBatch(body: ByteArray, requestContractVersion: String): ValidatedBatch {
    checkBatchBytes(body.size)
    val source = decodeUtf8(body)
    val scanner = StrictJsonScanner(source)
    val root = try {
        scanner.parseDocument()
    } catch (_: JsonSyntaxFailure) {
        throw RequestFailure("MALFORMED_REQUEST")
    }

    val rootObject = root.asObjectOrNull() ?: throw RequestFailure("MALFORMED_REQUEST")
    val rootFields = requiredUniqueFields(rootObject, setOf("installationId", "events"))
    val eventsNode = rootFields.getValue("events")
    val eventNodes = eventsNode.arrayValues ?: throw RequestFailure("MALFORMED_REQUEST")
    checkEventCount(eventNodes.size)

    val installationId = parseInstallationId(rootFields.getValue("installationId").stringValue)

    // Do this only after the scanner has verified every field name. kotlinx.serialization
    // does not retain duplicate keys, so it must never be the preflight authority.
    eventNodes.forEach { node ->
        val eventObject = node.asObjectOrNull()
            ?: throw RequestFailure("CONTRACT_VERSION_MISSING")
        val fields = eventObject.fields
        if (fields.groupingBy { it.name }.eachCount().any { it.value != 1 }) {
            throw RequestFailure("MALFORMED_REQUEST")
        }
        val unknown = fields.firstOrNull { it.name !in EVENT_FIELDS }
        if (unknown != null) throw RequestFailure("UNKNOWN_FIELD")
        val itemContractVersion = fields.singleOrNull { it.name == "contractVersion" }
            ?.value
            ?.stringValue
        checkItemContractVersion(itemContractVersion, requestContractVersion)
    }

    val document = try {
        // kotlinx parses nested containers with recursion. Pass it the source with each skipped
        // container replaced by null, so a nested property value becomes a per-item rejection.
        json.parseToJsonElement(scanner.withoutNestedContainers())
    } catch (_: SerializationException) {
        throw RequestFailure("MALFORMED_REQUEST")
    }
    val documentEvents = (document as? JsonObject)?.get("events") as? JsonArray
        ?: throw RequestFailure("MALFORMED_REQUEST")

    val items = eventNodes.indices.map { index ->
        val node = eventNodes[index]
        checkEventBytes(utf8Size(source, node.start, node.end))
        validateEvent(index, candidateOf(node, documentEvents[index]), installationId)
    }
    return resolveEventIdConflicts(items)
}

/**
 * Parses a client report body (ADR-0036) with the same strict scanner as the batch. Wrong shape,
 * duplicate or missing keys: MALFORMED_REQUEST. A key outside the contract: UNKNOWN_FIELD. A number
 * that is not a plain integer reaches the rules, which answer INVALID_REPORT.
 */
internal fun parseClientReport(body: ByteArray): ClientReport {
    val source = decodeUtf8(body)
    val root = try {
        StrictJsonScanner(source).parseDocument()
    } catch (_: JsonSyntaxFailure) {
        throw RequestFailure("MALFORMED_REQUEST")
    }
    val rootFields = requiredUniqueFields(
        root.asObjectOrNull() ?: throw RequestFailure("MALFORMED_REQUEST"),
        setOf("sdkVersion", "discardedEvents"), optional = setOf("sdkPlatform"),
    )
    val sdkVersion = rootFields.getValue("sdkVersion").stringValue ?: throw RequestFailure("MALFORMED_REQUEST")
    // Missing means `android` (ADR-0046). A value that is not a string is malformed, like `sdkVersion`; an unknown string is INVALID_REPORT.
    val sdkPlatform = rootFields["sdkPlatform"]?.let { it.stringValue ?: throw RequestFailure("MALFORMED_REQUEST") }
    val nodes = rootFields.getValue("discardedEvents").arrayValues ?: throw RequestFailure("MALFORMED_REQUEST")
    val entries = nodes.map { node ->
        val fields = requiredUniqueFields(node.asObjectOrNull() ?: throw RequestFailure("MALFORMED_REQUEST"), setOf("reason", "count"))
        val reason = fields.getValue("reason").stringValue ?: throw RequestFailure("MALFORMED_REQUEST")
        val count = fields.getValue("count") as? JsonAtomNode ?: throw RequestFailure("MALFORMED_REQUEST")
        val literal = source.substring(count.start, count.end)
        if (literal[0] != '-' && literal[0] !in '0'..'9') throw RequestFailure("MALFORMED_REQUEST") // true, false, null
        RawDiscardedCount(reason, literal)
    }
    return validateClientReport(sdkVersion, entries, sdkPlatform)
}

private fun candidateOf(node: JsonNode, element: JsonElement): EventCandidate? {
    val objectNode = node.asObjectOrNull() ?: return null
    val duplicateProperties = objectNode.fields.singleOrNull { it.name == "properties" }
        ?.value
        ?.asObjectOrNull()
        ?.fields
        ?.groupingBy { it.name }
        ?.eachCount()
        ?.any { it.value != 1 } == true
    return EventCandidate(
        rawEventId = objectNode.fields.singleOrNull { it.name == "eventId" }?.value?.stringValue,
        hasDuplicatePropertyKeys = duplicateProperties,
        fields = fieldsOf(element),
    )
}

private fun fieldsOf(element: JsonElement): EventFields? {
    val properties = (element as JsonObject)["properties"] as? JsonObject
    // The generated DTO cannot decode `sdkPlatform` here (contextual serializer). Read the raw string; the rules judge it.
    // Present but not a string (including null): the field is invalid, so the item is INVALID_EVENT (ADR-0027).
    val platformNode = element["sdkPlatform"]
    val sdkPlatform = platformNode?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    // The DTO cannot hold a nested or null property value. Decode it without the property values
    // and judge them in `propertyValuesOf`, so such a value is INVALID_PROPERTIES, not INVALID_EVENT.
    val dto = try {
        val fields = (element as JsonObject) - "sdkPlatform"
        json.decodeFromJsonElement<EventIngest>(JsonObject(if (properties == null) fields else fields + ("properties" to JsonObject(emptyMap()))))
    } catch (_: SerializationException) {
        return null
    }
    return EventFields(dto.name, dto.occurredAt, dto.sdkVersion, sdkPlatform, properties?.let(::propertyValuesOf))
}

private fun propertyValuesOf(properties: JsonObject): Map<String, PropertyValue> = properties.mapValues { (_, value) ->
    val primitive = value as? JsonPrimitive
    val flag = primitive?.booleanOrNull
    when {
        primitive == null || primitive is JsonNull -> PropertyValue.Unsupported
        primitive.isString -> PropertyValue.Text(primitive.content)
        flag != null -> PropertyValue.Flag(flag)
        else -> PropertyValue.Number(primitive.content)
    }
}

private fun requiredUniqueFields(objectNode: JsonObjectNode, allowed: Set<String>, optional: Set<String> = emptySet()): Map<String, JsonNode> {
    if (objectNode.fields.any { it.name !in allowed && it.name !in optional }) throw RequestFailure("UNKNOWN_FIELD")
    val grouped = objectNode.fields.groupBy { it.name }
    if (grouped.any { it.value.size != 1 } || allowed.any { it !in grouped }) {
        throw RequestFailure("MALFORMED_REQUEST")
    }
    return grouped.mapValues { it.value.single().value }
}

private fun decodeUtf8(body: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(body))
        .toString()
} catch (_: CharacterCodingException) {
    throw RequestFailure("MALFORMED_REQUEST")
}

private fun utf8Size(source: String, start: Int, end: Int): Int =
    source.substring(start, end).toByteArray(StandardCharsets.UTF_8).size

private val EVENT_FIELDS = setOf("eventId", "name", "occurredAt", "properties", "sdkVersion", "sdkPlatform", "contractVersion")

private sealed interface JsonNode { val start: Int; val end: Int }
private data class JsonObjectNode(override val start: Int, override val end: Int, val fields: List<JsonField>) : JsonNode
private data class JsonArrayNode(override val start: Int, override val end: Int, val values: List<JsonNode>) : JsonNode
private data class JsonStringNode(override val start: Int, override val end: Int, val value: String) : JsonNode
private data class JsonAtomNode(override val start: Int, override val end: Int) : JsonNode
private data class JsonField(val name: String, val value: JsonNode)
private class JsonSyntaxFailure : RuntimeException()

private val JsonNode.arrayValues: List<JsonNode>? get() = (this as? JsonArrayNode)?.values
private val JsonNode.stringValue: String? get() = (this as? JsonStringNode)?.value
private fun JsonNode.asObjectOrNull(): JsonObjectNode? = this as? JsonObjectNode

/** Strict JSON scanner that keeps source spans and decoded object keys before Json parses values. */
private class StrictJsonScanner(private val input: String) {
    private var position = 0
    private val nestedSpans = mutableListOf<Pair<Int, Int>>()

    /** The input with every container that [skipContainer] checked replaced by `null`. */
    fun withoutNestedContainers(): String {
        if (nestedSpans.isEmpty()) return input
        val out = StringBuilder()
        var from = 0
        for ((start, end) in nestedSpans) {
            out.append(input, from, start).append("null")
            from = end
        }
        return out.append(input, from, input.length).toString()
    }

    fun parseDocument(): JsonNode {
        skipWhitespace()
        val result = value(depth = 0)
        skipWhitespace()
        if (position != input.length) fail()
        return result
    }

    private fun value(depth: Int): JsonNode {
        skipWhitespace()
        val start = position
        return when (peek()) {
            '{' -> if (depth >= MAX_SCANNED_DEPTH) skipContainer(start) else objectNode(start, depth)
            '[' -> if (depth >= MAX_SCANNED_DEPTH) skipContainer(start) else arrayNode(start, depth)
            '"' -> stringNode(start)
            't' -> literal("true", start)
            'f' -> literal("false", start)
            'n' -> literal("null", start)
            '-', in '0'..'9' -> number(start)
            else -> fail()
        }
    }

    /** Checks the syntax of a nested container with an explicit stack, not recursion. Keeps no tree. */
    private fun skipContainer(start: Int): JsonNode {
        val open = StringBuilder()
        do {
            skipWhitespace()
            when (peek()) {
                '{' -> {
                    position++; open.append('{'); skipWhitespace()
                    if (peek() == '}') {
                        position++; open.setLength(open.length - 1)
                    } else {
                        stringNode(position); skipWhitespace(); take(':'); continue
                    }
                }
                '[' -> {
                    position++; open.append('['); skipWhitespace()
                    if (peek() == ']') { position++; open.setLength(open.length - 1) } else continue
                }
                '"' -> stringNode(position)
                't' -> literal("true", position)
                'f' -> literal("false", position)
                'n' -> literal("null", position)
                '-', in '0'..'9' -> number(position)
                else -> fail()
            }
            // A value ended: close containers, or move to the next member.
            while (open.isNotEmpty()) {
                skipWhitespace()
                val inObject = open.last() == '{'
                val c = next()
                if (c == (if (inObject) '}' else ']')) { open.setLength(open.length - 1); continue }
                if (c != ',') fail()
                if (inObject) { skipWhitespace(); stringNode(position); skipWhitespace(); take(':') }
                break
            }
        } while (open.isNotEmpty())
        nestedSpans += start to position
        return JsonAtomNode(start, position)
    }

    private fun objectNode(start: Int, depth: Int): JsonNode {
        take('{')
        skipWhitespace()
        val fields = mutableListOf<JsonField>()
        if (peek() != '}') while (true) {
            skipWhitespace()
            val key = stringNode(position)
            skipWhitespace(); take(':')
            fields += JsonField(key.value, value(depth + 1))
            skipWhitespace()
            if (peek() == '}') break
            take(',')
        }
        take('}')
        return JsonObjectNode(start, position, fields)
    }

    private fun arrayNode(start: Int, depth: Int): JsonNode {
        take('['); skipWhitespace()
        val values = mutableListOf<JsonNode>()
        if (peek() != ']') while (true) {
            values += value(depth + 1); skipWhitespace()
            if (peek() == ']') break
            take(',')
        }
        take(']')
        return JsonArrayNode(start, position, values)
    }

    private fun stringNode(start: Int): JsonStringNode {
        take('"')
        val builder = StringBuilder()
        while (true) {
            if (position >= input.length) fail()
            when (val c = input[position++]) {
                '"' -> return JsonStringNode(start, position, builder.toString())
                '\\' -> builder.append(escape())
                in '\u0000'..'\u001f' -> fail()
                else -> builder.append(c)
            }
        }
    }

    private fun escape(): String = when (val c = next()) {
        '"', '\\', '/' -> c.toString()
        'b' -> "\b"
        'f' -> "\u000c"
        'n' -> "\n"
        'r' -> "\r"
        't' -> "\t"
        'u' -> unicodeEscape()
        else -> fail()
    }

    private fun unicodeEscape(): String {
        if (position + 4 > input.length) fail()
        val code = input.substring(position, position + 4).toIntOrNull(16) ?: fail()
        position += 4
        // A lone surrogate escape is valid JSON syntax. Keep it: the per-item text rule rejects it (ADR-0027).
        return code.toChar().toString()
    }

    private fun literal(expected: String, start: Int): JsonNode {
        if (!input.regionMatches(position, expected, 0, expected.length)) fail()
        position += expected.length
        return JsonAtomNode(start, position)
    }

    private fun number(start: Int): JsonNode {
        if (peek() == '-') position++
        if (peek() == '0') position++ else digits()
        if (peek() == '.') { position++; digits() }
        if (peek() == 'e' || peek() == 'E') { position++; if (peek() == '+' || peek() == '-') position++; digits() }
        return JsonAtomNode(start, position)
    }

    private fun digits() {
        if (peek() !in '0'..'9') fail()
        while (peek() in '0'..'9') position++
    }

    private fun skipWhitespace() { while (peek() in charArrayOf(' ', '\n', '\r', '\t')) position++ }
    private fun peek(): Char = input.getOrNull(position) ?: '\u0000'
    private fun next(): Char = input.getOrNull(position++) ?: fail()
    private fun take(expected: Char) { if (next() != expected) fail() }
    private fun fail(): Nothing = throw JsonSyntaxFailure()
}
