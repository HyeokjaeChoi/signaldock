package local.signaldock.server

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import org.openapitools.client.models.EventIngest

/** A request-level failure. The route maps this to one ErrorResponse and stores nothing. */
class RequestFailure(val code: String, val httpStatus: Int = 400) : RuntimeException(code)

data class ValidatedBatch(val items: List<ValidatedItem>)

data class ValidatedItem(
    val index: Int,
    val eventId: String?,
    val event: StoredEvent?,
    val rejectionCode: String?,
)

data class StoredEvent(
    val eventId: UUID,
    val installationId: UUID,
    val name: String,
    val occurredAt: Instant,
    val sdkVersion: String,
    val properties: JsonObject,
)

private const val MAX_BATCH_BYTES = 256 * 1024
private const val MAX_EVENT_BYTES = 16 * 1024
private const val MAX_EVENTS = 50
private val MAX_SAFE_INTEGER = BigDecimal("9007199254740991")

private val json = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = true
}

/**
 * Parses one raw JSON body without normalizing it before size and duplicate-key checks.
 *
 * The contract whitespace set for names and property keys is the fixed Unicode White_Space
 * set: U+0009..U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000..U+200A, U+2028,
 * U+2029, U+202F, U+205F, and U+3000. Values are never trimmed or normalized.
 */
fun parseBatch(body: ByteArray): ValidatedBatch {
    if (body.size > MAX_BATCH_BYTES) throw RequestFailure("BATCH_TOO_LARGE", 413)
    val source = decodeUtf8(body)
    val root = try {
        StrictJsonScanner(source).parseDocument()
    } catch (_: JsonSyntaxFailure) {
        throw RequestFailure("MALFORMED_REQUEST")
    }

    val rootObject = root.asObjectOrNull() ?: throw RequestFailure("MALFORMED_REQUEST")
    val rootFields = requiredUniqueFields(rootObject, setOf("installationId", "events"))
    val eventsNode = rootFields.getValue("events")
    val eventNodes = eventsNode.arrayValues ?: throw RequestFailure("MALFORMED_REQUEST")
    if (eventNodes.isEmpty()) throw RequestFailure("MALFORMED_REQUEST")
    if (eventNodes.size > MAX_EVENTS) throw RequestFailure("BATCH_TOO_LARGE", 413)

    val installationText = rootFields.getValue("installationId").stringValue
    val installationId = installationText?.let(::uuidV4OrNull)

    // Do this only after the scanner has verified every field name. kotlinx.serialization
    // does not retain duplicate keys, so it must never be the preflight authority.
    eventNodes.forEach { node ->
        val eventObject = node.asObjectOrNull() ?: return@forEach
        val fields = eventObject.fields
        if (fields.groupingBy { it.name }.eachCount().any { it.value != 1 }) {
            throw RequestFailure("MALFORMED_REQUEST")
        }
        val unknown = fields.firstOrNull { it.name !in EVENT_FIELDS }
        if (unknown != null) throw RequestFailure("UNKNOWN_FIELD")
    }

    val document = try {
        json.parseToJsonElement(source)
    } catch (_: SerializationException) {
        throw RequestFailure("MALFORMED_REQUEST")
    }
    val documentEvents = (document as? JsonObject)?.get("events") as? JsonArray
        ?: throw RequestFailure("MALFORMED_REQUEST")

    val items = eventNodes.indices.map { index ->
        val node = eventNodes[index]
        if (utf8Size(source, node.start, node.end) > MAX_EVENT_BYTES) {
            throw RequestFailure("EVENT_TOO_LARGE", 413)
        }
        validateItem(index, node, documentEvents[index], installationId)
    }.toMutableList()

    // A malformed member can otherwise leave a valid same-ID sibling eligible for storage.
    // Treat every parsed UUIDv4 identity as one atomic conflict group at this boundary.
    items.filter { it.eventId != null }
        .groupBy { UUID.fromString(it.eventId) }
        .values
        .filter { group -> group.size > 1 && group.any { it.rejectionCode != null } }
        .flatten()
        .forEach { item ->
            val position = items.indexOfFirst { it.index == item.index }
            items[position] = item.copy(event = null, rejectionCode = "EVENT_ID_CONFLICT")
        }

    return ValidatedBatch(items)
}

/** Content comparison for duplicate handling. It intentionally ignores JSON object key order. */
fun sameContent(a: StoredEvent, b: StoredEvent): Boolean =
    a.eventId == b.eventId &&
        a.installationId == b.installationId &&
        a.name == b.name &&
        a.occurredAt == b.occurredAt &&
        a.sdkVersion == b.sdkVersion &&
        sameProperties(a.properties, b.properties)

private fun validateItem(
    index: Int,
    node: JsonNode,
    element: JsonElement,
    installationId: UUID?,
): ValidatedItem {
    val objectNode = node.asObjectOrNull()
        ?: return ValidatedItem(index, null, null, "INVALID_EVENT")
    val rawEventId = objectNode.fields.singleOrNull { it.name == "eventId" }?.value?.stringValue
    val eventId = rawEventId?.let(::uuidV4OrNull)
    if (eventId == null) return ValidatedItem(index, null, null, "INVALID_EVENT_ID")

    val duplicateProperties = objectNode.fields.singleOrNull { it.name == "properties" }
        ?.value
        ?.asObjectOrNull()
        ?.fields
        ?.groupingBy { it.name }
        ?.eachCount()
        ?.any { it.value != 1 } == true
    if (duplicateProperties) return invalid(index, rawEventId, "DUPLICATE_PROPERTY_KEY")
    if (installationId == null) return invalid(index, rawEventId, "INVALID_INSTALLATION_ID")

    val dto = try {
        json.decodeFromJsonElement<EventIngest>(element)
    } catch (_: SerializationException) {
        return invalid(index, rawEventId, "INVALID_EVENT")
    }
    if (dto.contractVersion != EventIngest.ContractVersion._1) return invalid(index, rawEventId, "CONTRACT_VERSION_UNSUPPORTED")
    if (!validText(dto.name, keyOrName = true) || !validText(dto.sdkVersion, keyOrName = false)) {
        return invalid(index, rawEventId, "INVALID_TEXT")
    }
    val occurredAt = parseMillisInstant(dto.occurredAt) ?: return invalid(index, rawEventId, "INVALID_OCCURRED_AT")
    val properties = (element as JsonObject)["properties"] as? JsonObject
        ?: return invalid(index, rawEventId, "INVALID_PROPERTIES")
    if (!validProperties(properties)) return invalid(index, rawEventId, "INVALID_PROPERTIES")

    return ValidatedItem(
        index = index,
        eventId = rawEventId,
        event = StoredEvent(eventId, installationId, dto.name, occurredAt, dto.sdkVersion, properties),
        rejectionCode = null,
    )
}

private fun invalid(index: Int, eventId: String, code: String) =
    ValidatedItem(index, eventId, null, code)

private fun requiredUniqueFields(objectNode: JsonObjectNode, allowed: Set<String>): Map<String, JsonNode> {
    if (objectNode.fields.any { it.name !in allowed }) throw RequestFailure("UNKNOWN_FIELD")
    val grouped = objectNode.fields.groupBy { it.name }
    if (grouped.any { it.value.size != 1 } || allowed.any { it !in grouped }) {
        throw RequestFailure("MALFORMED_REQUEST")
    }
    return grouped.mapValues { it.value.single().value }
}

private fun validProperties(properties: JsonObject): Boolean = properties.all { (key, value) ->
    validText(key, keyOrName = true) && when (value) {
        is JsonPrimitive -> when {
            value.isString -> validText(value.content, keyOrName = false)
            value.booleanOrNull != null -> true
            else -> validNumber(value.content)
        }
        else -> false
    }
}

private fun validNumber(raw: String): Boolean = try {
    val number = BigDecimal(raw)
    val normalized = number.stripTrailingZeros()
    if (normalized.scale() <= 0) number.abs() <= MAX_SAFE_INTEGER
    else number.toDouble().isFinite()
} catch (_: NumberFormatException) {
    false
}

private fun parseMillisInstant(value: String): Instant? = try {
    if (!RFC3339_OFFSET.matches(value)) return null
    val instant = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    if (instant.nano % 1_000_000 != 0) null else instant
} catch (_: Exception) {
    null
}

private fun uuidV4OrNull(value: String): UUID? = try {
    UUID.fromString(value).takeIf { it.version() == 4 }
} catch (_: IllegalArgumentException) {
    null
}

private fun validText(value: String, keyOrName: Boolean): Boolean =
    validUnicode(value) &&
        (!keyOrName || (value.isNotEmpty() && !isContractWhitespace(value.first()) && !isContractWhitespace(value.last())))

private fun validUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val current = value[index]
        if (current == '\u0000' || current.isLowSurrogate()) return false
        if (current.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
            index += 2
        } else index++
    }
    return true
}

private fun isContractWhitespace(c: Char): Boolean =
    c in '\u0009'..'\u000d' || c == '\u0020' || c == '\u0085' || c == '\u00a0' || c == '\u1680' ||
        c in '\u2000'..'\u200a' || c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000'

private fun sameProperties(a: JsonObject, b: JsonObject): Boolean =
    a.size == b.size && a.all { (key, left) -> b[key]?.let { sameScalar(left, it) } == true }

private fun sameScalar(left: JsonElement, right: JsonElement): Boolean {
    if (left !is JsonPrimitive || right !is JsonPrimitive) return false
    if (left.isString || right.isString) return left.isString && right.isString && left.content == right.content
    val leftBoolean = left.booleanOrNull
    val rightBoolean = right.booleanOrNull
    if (leftBoolean != null || rightBoolean != null) return leftBoolean != null && leftBoolean == rightBoolean
    return try {
        BigDecimal(left.content).compareTo(BigDecimal(right.content)) == 0
    } catch (_: NumberFormatException) {
        false
    }
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

private val EVENT_FIELDS = setOf("eventId", "name", "occurredAt", "properties", "sdkVersion", "contractVersion")
private val RFC3339_OFFSET = Regex(".*(?:Z|[+-]\\d{2}:\\d{2})$")

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

    fun parseDocument(): JsonNode {
        skipWhitespace()
        val result = value()
        skipWhitespace()
        if (position != input.length) fail()
        return result
    }

    private fun value(): JsonNode {
        skipWhitespace()
        val start = position
        return when (peek()) {
            '{' -> objectNode(start)
            '[' -> arrayNode(start)
            '"' -> stringNode(start)
            't' -> literal("true", start)
            'f' -> literal("false", start)
            'n' -> literal("null", start)
            '-', in '0'..'9' -> number(start)
            else -> fail()
        }
    }

    private fun objectNode(start: Int): JsonNode {
        take('{')
        skipWhitespace()
        val fields = mutableListOf<JsonField>()
        if (peek() != '}') while (true) {
            skipWhitespace()
            val key = stringNode(position)
            skipWhitespace(); take(':')
            fields += JsonField(key.value, value())
            skipWhitespace()
            if (peek() == '}') break
            take(',')
        }
        take('}')
        return JsonObjectNode(start, position, fields)
    }

    private fun arrayNode(start: Int): JsonNode {
        take('['); skipWhitespace()
        val values = mutableListOf<JsonNode>()
        if (peek() != ']') while (true) {
            values += value(); skipWhitespace()
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
        val unit = code.toChar()
        if (!unit.isHighSurrogate()) {
            if (unit.isLowSurrogate()) fail()
            return unit.toString()
        }
        if (position + 6 > input.length || input[position] != '\\' || input[position + 1] != 'u') fail()
        position += 2
        val lowCode = input.substring(position, position + 4).toIntOrNull(16) ?: fail()
        position += 4
        val low = lowCode.toChar()
        if (!low.isLowSurrogate()) fail()
        return charArrayOf(unit, low).concatToString()
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
