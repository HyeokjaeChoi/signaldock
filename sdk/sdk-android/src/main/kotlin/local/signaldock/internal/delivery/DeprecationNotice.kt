package local.signaldock.internal.delivery

import local.signaldock.ContractDeprecation
import local.signaldock.internal.init.Logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the server's `Deprecation` (RFC 9745) and `Sunset` (RFC 8594) headers (ADR-0030). Keeps the
 * latest valid values in memory and logs one WARN per process. It never throws and never changes
 * delivery: invalid or absent headers are ignored.
 */
internal class DeprecationNotice(private val logger: Logger) {
    @Volatile
    var current: ContractDeprecation? = null
        private set

    private val warned = AtomicBoolean(false)

    fun observe(deprecation: String?, sunset: String?) {
        val deprecatedAt = parseDeprecationMillis(deprecation)
        val sunsetAt = sunset?.trim()?.let { parseHttpDateMillis(it) }
        if (deprecatedAt == null && sunsetAt == null) return
        current = ContractDeprecation(deprecatedAt, sunsetAt)
        if (warned.compareAndSet(false, true)) {
            val date = sunsetAt?.let { isoDate(it) } ?: "unknown"
            logger.warn("server contract version is deprecated; sunset date: $date")
        }
    }
}

/** RFC 9745: a structured field date, `@<epoch-seconds>`. Anything else is null. */
internal fun parseDeprecationMillis(value: String?): Long? {
    val v = value?.trim() ?: return null
    if (!Regex("@-?\\d{1,15}").matches(v)) return null
    return v.substring(1).toLong().takeIf { it in -MAX_SECONDS..MAX_SECONDS }?.times(1000)
}

private const val MAX_SECONDS = 253_402_300_799L // year 9999

/** ISO-8601 UTC date-time. `java.time` is not used: it needs API 26 and `minSdk` is 24 (ADR-0003 decision 5). */
private fun isoDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
