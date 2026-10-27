package local.signaldock.ingestion

/** Why the SDK dropped events (ADR-0036). A new reason is a contract change. */
public enum class DiscardReason(public val wire: String) {
    QUEUE_FULL("queue_full"),
    STORAGE_ERROR("storage_error"),
    INVALID_EVENT("invalid_event"),
    EVENT_TOO_LARGE("event_too_large"),
    SERVER_REJECTED("server_rejected");

    public companion object {
        /** Wire names are case-sensitive. */
        public fun fromWire(wire: String): DiscardReason? = entries.firstOrNull { it.wire == wire }
    }
}

/** Events dropped for one reason, 1 to [MAX_REPORT_COUNT]. */
public data class DiscardedCount(val reason: DiscardReason, val count: Int)

/** A valid SDK health report: one entry per reason. */
public data class ClientReport(val sdkVersion: String, val sdkPlatform: SdkPlatform, val entries: List<DiscardedCount>)

/** One entry as it came from the wire. `countLiteral` is the JSON number text, not yet checked. */
public data class RawDiscardedCount(val reason: String, val countLiteral: String)

public const val MAX_REPORT_COUNT: Int = 1_000_000
private const val MAX_SDK_VERSION_CODE_POINTS = 64
private const val MAX_REPORT_ENTRIES = 5
private val INTEGER_LITERAL = Regex("-?(0|[1-9][0-9]*)")

/**
 * Checks a client report as a whole. Any failure is `INVALID_REPORT` and nothing is stored. This is
 * a request-level rejection: there is no queue behind it that a bad report could block (ADR-0027).
 */
public fun validateClientReport(sdkVersion: String, raw: List<RawDiscardedCount>, sdkPlatform: String? = null): ClientReport {
    if (!validText(sdkVersion)) throw RequestFailure("INVALID_REPORT")
    // A missing platform is `android`; an unknown value fails like an invalid `sdkVersion` (ADR-0046).
    val platform = if (sdkPlatform == null) SdkPlatform.ANDROID else SdkPlatform.fromWire(sdkPlatform) ?: throw RequestFailure("INVALID_REPORT")
    if (raw.size !in 1..MAX_REPORT_ENTRIES) throw RequestFailure("INVALID_REPORT")
    val entries = raw.map { entry ->
        val reason = DiscardReason.fromWire(entry.reason) ?: throw RequestFailure("INVALID_REPORT")
        DiscardedCount(reason, parseCount(entry.countLiteral))
    }
    if (entries.map { it.reason }.toSet().size != entries.size) throw RequestFailure("INVALID_REPORT")
    return ClientReport(sdkVersion, platform, entries)
}

/** Only an integer literal counts: `3.0`, `1e3`, and `-1` are rejected. The length check avoids overflow. */
private fun parseCount(literal: String): Int {
    if (!INTEGER_LITERAL.matches(literal) || literal.length > 7) throw RequestFailure("INVALID_REPORT")
    return literal.toInt().takeIf { it in 1..MAX_REPORT_COUNT } ?: throw RequestFailure("INVALID_REPORT")
}

/** 1 to 64 code points, no NUL, no lone surrogate (storage cannot hold them). */
private fun validText(text: String): Boolean {
    var index = 0
    var codePoints = 0
    while (index < text.length) {
        val c = text[index]
        val pair = c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()
        if (c == '\u0000' || (c.isSurrogate() && !pair)) return false
        index += if (pair) 2 else 1
        codePoints++
    }
    return codePoints in 1..MAX_SDK_VERSION_CODE_POINTS
}
