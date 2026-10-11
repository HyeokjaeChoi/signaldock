package local.signaldock

import local.signaldock.internal.MAX_SAFE_INTEGER
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

    public fun property(key: String, value: String) {
        put(key, EventIngestPropertiesValue.StringValue(value), value)
    }

    public fun property(key: String, value: Boolean) {
        put(key, EventIngestPropertiesValue.BooleanValue(value))
    }

    public fun property(key: String, value: Int) {
        put(key, EventIngestPropertiesValue.BigDecimalValue(BigDecimal.valueOf(value.toLong())))
    }

    public fun property(key: String, value: Long) {
        // Range check, not abs(): abs(Long.MIN_VALUE) overflows.
        if (value !in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) return fail(InvalidReason.NUMBER_OUT_OF_RANGE, key)
        put(key, EventIngestPropertiesValue.BigDecimalValue(BigDecimal.valueOf(value)))
    }

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
        local.signaldock.internal.checkText(key, isName = true)?.let { return fail(it, key) }
        if (stringValue != null) {
            local.signaldock.internal.checkText(stringValue, isName = false)?.let { return fail(it, key) }
        }
        if (properties.containsKey(key)) return fail(InvalidReason.DUPLICATE_KEY, key)
        properties[key] = value
    }

    private fun fail(reason: InvalidReason, key: String) {
        if (error == null) error = TrackResult.Invalid(reason, key)
    }
}
