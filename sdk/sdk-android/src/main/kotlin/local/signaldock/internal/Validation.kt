package local.signaldock.internal

import local.signaldock.InvalidReason
import local.signaldock.SignalDockConfig

internal const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L // 2^53 - 1

/**
 * Validates a name, property key (isName = true) or string value (isName = false).
 * Names and keys: non-empty, no leading or trailing whitespace; no trimming (Q64) and
 * no Unicode normalization (Q84). All text: no NUL and no lone surrogates (Q85).
 * Whitespace set: Kotlin Char.isWhitespace() (ADR-0003).
 */
internal fun checkText(s: String, isName: Boolean): InvalidReason? {
    if (isName) {
        if (s.isEmpty() || s.isBlank()) return InvalidReason.EMPTY_NAME
        if (s.first().isWhitespace() || s.last().isWhitespace()) return InvalidReason.LEADING_OR_TRAILING_WHITESPACE
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\u0000' -> return InvalidReason.FORBIDDEN_CHARACTER
            c.isHighSurrogate() -> {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return InvalidReason.FORBIDDEN_CHARACTER
                i++
            }
            c.isLowSurrogate() -> return InvalidReason.FORBIDDEN_CHARACTER
        }
        i++
    }
    return null
}

/** Returns a message for the first problem, or null. Messages never contain secrets (Q115). */
internal fun validateConfig(c: SignalDockConfig): String? {
    if (c.collectionKey.isBlank()) return "collectionKey is blank"
    val uri = runCatching { java.net.URI(c.endpoint) }.getOrNull()
        ?: return "endpoint is not a valid URI"
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
        return "endpoint must be an absolute http or https URL with a host"
    }
    val timeouts = listOf(
        "callTimeout" to c.callTimeout, "connectTimeout" to c.connectTimeout,
        "readTimeout" to c.readTimeout, "writeTimeout" to c.writeTimeout,
    )
    for ((name, d) in timeouts) {
        // OkHttp represents timeouts as whole milliseconds that fit in an Int.
        if (!d.isFinite() || d.inWholeMilliseconds !in 1..Int.MAX_VALUE) {
            return "$name must be positive, finite and at most ${Int.MAX_VALUE} ms"
        }
    }
    return null
}
