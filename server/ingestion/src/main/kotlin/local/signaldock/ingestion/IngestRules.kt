package local.signaldock.ingestion

import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val MAX_BATCH_BYTES = 256 * 1024
private const val MAX_EVENT_BYTES = 16 * 1024
private const val MAX_EVENTS = 50
private const val MAX_NUMERIC_INTEGER_DIGITS = 131_072
private const val MAX_NUMERIC_FRACTIONAL_DIGITS = 16_383
private val MAX_SAFE_INTEGER = BigDecimal("9007199254740991")
private val SUPPORTED_CONTRACT_VERSIONS = setOf("1")
private val RFC3339_OFFSET = Regex(".*(?:Z|[+-]\\d{2}:\\d{2})$")

/** Parsed event fields as the transport layer found them. Rules decide if they are valid. */
public class EventFields(
    public val name: String,
    public val occurredAt: String,
    public val sdkVersion: String,
    /** The `sdkPlatform` string, or null when the field is missing (ADR-0046: stored as `android`). */
    public val sdkPlatform: String?,
    /** Null when `properties` is not a JSON object. */
    public val properties: Map<String, PropertyValue>?,
)

/** One event as the transport layer found it. Nothing here is validated yet. */
public class EventCandidate(
    /** The `eventId` string, or null when it is missing or not a string. */
    public val rawEventId: String?,
    public val hasDuplicatePropertyKeys: Boolean,
    /** Null when the event does not fit the transport shape. */
    public val fields: EventFields?,
)

/** Request-level rule: raw body size. */
public fun checkBatchBytes(bytes: Int) {
    if (bytes > MAX_BATCH_BYTES) throw RequestFailure("BATCH_TOO_LARGE")
}

/** Request-level rule: event count of a batch. */
public fun checkEventCount(count: Int) {
    if (count == 0) throw RequestFailure("MALFORMED_REQUEST")
    if (count > MAX_EVENTS) throw RequestFailure("BATCH_TOO_LARGE")
}

/** Request-level rule: one event's source size. */
public fun checkEventBytes(bytes: Int) {
    if (bytes > MAX_EVENT_BYTES) throw RequestFailure("EVENT_TOO_LARGE")
}

/** Request-level rule: contract version of one event. A null value means missing. */
public fun checkItemContractVersion(itemVersion: String?, requestVersion: String) {
    if (itemVersion == null) throw RequestFailure("CONTRACT_VERSION_MISSING")
    if (itemVersion !in SUPPORTED_CONTRACT_VERSIONS) throw RequestFailure("CONTRACT_VERSION_UNSUPPORTED")
    if (itemVersion != requestVersion) throw RequestFailure("CONTRACT_VERSION_MISMATCH")
}

/** Returns the installation ID when it is a UUIDv4. Items get a rejection when this is null. */
public fun parseInstallationId(text: String?): UUID? = text?.let(::uuidV4OrNull)

/** Per-item rules. A null candidate means the event was not a JSON object. */
public fun validateEvent(index: Int, candidate: EventCandidate?, installationId: UUID?): ValidatedItem {
    if (candidate == null) return ValidatedItem(index, null, null, "INVALID_EVENT")
    val rawEventId = candidate.rawEventId
    val eventId = rawEventId?.let(::uuidV4OrNull)
    if (rawEventId == null || eventId == null) return ValidatedItem(index, null, null, "INVALID_EVENT_ID")

    if (candidate.hasDuplicatePropertyKeys) return invalid(index, rawEventId, "DUPLICATE_PROPERTY_KEY")
    if (installationId == null) return invalid(index, rawEventId, "INVALID_INSTALLATION_ID")

    val fields = candidate.fields ?: return invalid(index, rawEventId, "INVALID_EVENT")
    if (!validText(fields.name, keyOrName = true) || !validText(fields.sdkVersion, keyOrName = false)) {
        return invalid(index, rawEventId, "INVALID_TEXT")
    }
    // A value that is not `android` or `ios` is a per-item rejection like other invalid event fields (ADR-0046, ADR-0027).
    val sdkPlatform = if (fields.sdkPlatform == null) SdkPlatform.ANDROID
    else SdkPlatform.fromWire(fields.sdkPlatform) ?: return invalid(index, rawEventId, "INVALID_EVENT")
    val occurredAt = parseMillisInstant(fields.occurredAt) ?: return invalid(index, rawEventId, "INVALID_OCCURRED_AT")
    val properties = fields.properties ?: return invalid(index, rawEventId, "INVALID_PROPERTIES")
    if (!validProperties(properties)) return invalid(index, rawEventId, "INVALID_PROPERTIES")

    return ValidatedItem(
        index = index,
        eventId = rawEventId,
        event = StoredEvent(eventId, installationId, fields.name, occurredAt, fields.sdkVersion, sdkPlatform, properties),
        rejectionCode = null,
    )
}

/**
 * A malformed member can otherwise leave a valid same-ID sibling eligible for storage.
 * Treat every parsed UUIDv4 identity as one atomic conflict group at this boundary.
 */
public fun resolveEventIdConflicts(items: List<ValidatedItem>): ValidatedBatch {
    val result = items.toMutableList()
    items.filter { it.eventId != null }
        .groupBy { UUID.fromString(it.eventId) }
        .values
        .filter { group -> group.size > 1 && group.any { it.rejectionCode != null } }
        .flatten()
        .forEach { item ->
            val position = result.indexOfFirst { it.index == item.index }
            result[position] = item.copy(event = null, rejectionCode = "EVENT_ID_CONFLICT")
        }
    return ValidatedBatch(result)
}

private fun invalid(index: Int, eventId: String, code: String) = ValidatedItem(index, eventId, null, code)

private fun validProperties(properties: Map<String, PropertyValue>): Boolean = properties.all { (key, value) ->
    validText(key, keyOrName = true) && when (value) {
        is PropertyValue.Text -> validText(value.value, keyOrName = false)
        is PropertyValue.Flag -> true
        is PropertyValue.Number -> validNumber(value.literal)
        PropertyValue.Unsupported -> false
    }
}

private fun validNumber(raw: String): Boolean = try {
    val number = BigDecimal(raw)
    val normalized = number.stripTrailingZeros()
    if (normalized.scale() <= 0) number.abs() <= MAX_SAFE_INTEGER
    else {
        val double = number.toDouble()
        val integerDigits = normalized.precision() - normalized.scale()
        double.isFinite() &&
            BigDecimal.valueOf(double).compareTo(number) == 0 &&
            normalized.scale() <= MAX_NUMERIC_FRACTIONAL_DIGITS &&
            integerDigits <= MAX_NUMERIC_INTEGER_DIGITS
    }
} catch (_: NumberFormatException) {
    false
}

private fun parseMillisInstant(value: String): Instant? = try {
    if (!RFC3339_OFFSET.matches(value)) return null
    val instant = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    if (instant.nano % 1_000_000 != 0 || instant.atOffset(ZoneOffset.UTC).year !in 1..9999) null else instant
} catch (_: Exception) {
    null
}

private fun uuidV4OrNull(value: String): UUID? = try {
    UUID.fromString(value).takeIf { it.version() == 4 }
} catch (_: IllegalArgumentException) {
    null
}

/**
 * The contract whitespace set for names and property keys is the fixed Unicode White_Space
 * set: U+0009..U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000..U+200A, U+2028,
 * U+2029, U+202F, U+205F, and U+3000. Values are never trimmed or normalized.
 */
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
    c in '\u0009'..'\u000d' || c == ' ' || c == '\u0085' || c == ' ' || c == ' ' ||
        c in ' '..' ' || c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　'
