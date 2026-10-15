package local.signaldock.internal.delivery

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal const val BACKOFF_BASE_MILLIS = 10_000L
internal const val BACKOFF_CAP_MILLIS = 5L * 60_000
internal const val HOLD_RECHECK_MILLIS = 30L * 60_000

/** Randomness boundary (Q88). Returns a value in 0..[boundInclusive]. */
internal fun interface Jitter {
    fun nextMillis(boundInclusive: Long): Long
}

internal val RandomJitter = Jitter { bound -> kotlin.random.Random.nextLong(bound + 1) }

/**
 * Backoff base B before jitter (Q73): 10 s for the first failure, doubling, capped at 5 minutes.
 * [failureCount] is the count including the failure being scheduled (at least 1).
 */
internal fun backoffBaseMillis(failureCount: Int): Long {
    val doublings = (failureCount - 1).coerceIn(0, 10) // 10 s * 2^5 already exceeds the cap
    return minOf(BACKOFF_CAP_MILLIS, BACKOFF_BASE_MILLIS shl doublings)
}

/** Saturating addition so that a huge Retry-After cannot overflow into the past. */
internal fun satAdd(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b

/**
 * Parses Retry-After as delta-seconds or an HTTP date. Returns the delay in milliseconds, or null if
 * the value is absent, malformed, zero or in the past (not a valid, positive wait).
 */
internal fun parseRetryAfterMillis(value: String?, nowMillis: Long): Long? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (v.all { it in '0'..'9' }) {
        val seconds = v.toLongOrNull() ?: Long.MAX_VALUE // all digits but too long: saturate
        if (seconds == 0L) return null
        return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE / 2 else seconds * 1000
    }
    val date = parseHttpDateMillis(v) ?: return null
    return (date - nowMillis).takeIf { it > 0 }
}

/** Parses an HTTP-date for Retry-After (any zone name `zzz`) to epoch milliseconds, or null if it is malformed. */
internal fun parseHttpDateMillis(value: String): Long? = parseDateMillis(value, "EEE, dd MMM yyyy HH:mm:ss zzz")

/** Strict RFC 9110 IMF-fixdate with the literal `GMT` suffix, for Sunset (ADR-0030). */
internal fun parseImfFixdateMillis(value: String): Long? =
    // SimpleDateFormat ignores field widths and case, so the shape is checked first.
    if (IMF_FIXDATE.matches(value)) parseDateMillis(value, "EEE, dd MMM yyyy HH:mm:ss 'GMT'") else null

private val IMF_FIXDATE = Regex(
    "(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4} \\d{2}:\\d{2}:\\d{2} GMT",
)

private fun parseDateMillis(value: String, pattern: String): Long? {
    val pos = ParsePosition(0)
    val date = SimpleDateFormat(pattern, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }
        .parse(value, pos)
    return if (date != null && pos.index == value.length) date.time else null // trailing text is invalid
}
