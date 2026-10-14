package local.signaldock

import local.signaldock.internal.validation.MAX_SAFE_INTEGER
import local.signaldock.internal.validation.checkText
import org.openapitools.client.models.EventIngestPropertiesValue
import java.math.BigDecimal
import kotlin.math.abs

/**
 * DSL receiver for [SignalDock.track]. Properties are flat string / number / boolean
 * values. A repeated key fails the whole call, even with the same value (Q63).
 * Invalid input is reported through [TrackResult.Invalid]; nothing is trimmed or converted.
 */
public class EventBuilder internal constructor() {
    internal val properties = LinkedHashMap<String, EventIngestPropertiesValue>()
    internal var error: TrackResult.Invalid? = null
        private set

    /**
     * Adds a string property. The key follows the name rules; the value is kept as given
     * (no trimming), but it must not contain a NUL character or a lone surrogate.
     * A failure is reported by [SignalDock.track] as [TrackResult.Invalid].
     *
     * @param key the property key.
     * @param value the string value.
     */
    public fun property(key: String, value: String) {
        put(key, EventIngestPropertiesValue.StringValue(value), value)
    }

    /**
     * Adds a boolean property. A failure is reported by [SignalDock.track] as [TrackResult.Invalid].
     *
     * @param key the property key.
     * @param value the boolean value.
     */
    public fun property(key: String, value: Boolean) {
        put(key, EventIngestPropertiesValue.BooleanValue(value))
    }

    /**
     * Adds an integer property. Every [Int] is inside the allowed integer range.
     * A failure is reported by [SignalDock.track] as [TrackResult.Invalid].
     *
     * @param key the property key.
     * @param value the integer value.
     */
    public fun property(key: String, value: Int) {
        put(key, EventIngestPropertiesValue.BigDecimalValue(BigDecimal.valueOf(value.toLong())))
    }

    /**
     * Adds an integer property. The value must be within plus or minus (2^53 - 1); otherwise
     * [SignalDock.track] returns [TrackResult.Invalid] with [InvalidReason.NUMBER_OUT_OF_RANGE].
     * Send larger IDs as strings.
     *
     * @param key the property key.
     * @param value the integer value.
     */
    public fun property(key: String, value: Long) {
        // Range check, not abs(): abs(Long.MIN_VALUE) overflows.
        if (value !in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) return fail(InvalidReason.NUMBER_OUT_OF_RANGE, key)
        put(key, EventIngestPropertiesValue.BigDecimalValue(BigDecimal.valueOf(value)))
    }

    /**
     * Adds a decimal property. NaN and infinite values give [InvalidReason.NON_FINITE_NUMBER]. A
     * whole number outside plus or minus (2^53 - 1) gives [InvalidReason.NUMBER_OUT_OF_RANGE].
     * There is no `Float` overload, because widening a `Float` changes its value (ADR-0003).
     *
     * @param key the property key.
     * @param value the decimal value.
     */
    public fun property(key: String, value: Double) {
        if (!value.isFinite()) return fail(InvalidReason.NON_FINITE_NUMBER, key)
        // The integer limit also applies to integer values passed as Double (spec section 3).
        if (abs(value) > MAX_SAFE_INTEGER.toDouble() && value == Math.floor(value)) {
            return fail(InvalidReason.NUMBER_OUT_OF_RANGE, key)
        }
        put(key, EventIngestPropertiesValue.BigDecimalValue(BigDecimal.valueOf(value)))
    }

    private fun put(key: String, value: EventIngestPropertiesValue, stringValue: String? = null) {
        if (error != null) return
        // Key rules are the same as name rules (Q64, Q84, Q85); string values only get Q85.
        checkText(key, isName = true)?.let { return fail(it, key) }
        if (stringValue != null) {
            checkText(stringValue, isName = false)?.let { return fail(it, key) }
        }
        if (properties.containsKey(key)) return fail(InvalidReason.DUPLICATE_KEY, key)
        properties[key] = value
    }

    private fun fail(reason: InvalidReason, key: String) {
        if (error == null) error = TrackResult.Invalid(reason, key)
    }
}
