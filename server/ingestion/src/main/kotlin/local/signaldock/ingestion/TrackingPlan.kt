package local.signaldock.ingestion

import java.math.BigDecimal

/** How a stored event relates to the tracking plan (ADR-0022). `wire` is the stored and API text. */
public enum class PlanStatus(public val wire: String) {
    PLANNED("planned"),
    UNPLANNED_EVENT("unplanned_event"),
    INVALID_PROPERTIES("invalid_properties");

    public companion object {
        public fun fromWire(text: String): PlanStatus? = entries.firstOrNull { it.wire == text }
    }
}

public enum class PlanValueType { STRING, INTEGER, NUMBER, BOOLEAN }

public data class PlanProperty(val key: String, val type: PlanValueType, val required: Boolean)

/**
 * The part of `tracking-plan/plan.json` that matters for checking: event names with their properties.
 * It only labels events. It never decides whether an event is stored.
 */
public class TrackingPlan(private val events: Map<String, List<PlanProperty>>) {
    public fun check(name: String, properties: Map<String, PropertyValue>): PlanStatus {
        val spec = events[name] ?: return PlanStatus.UNPLANNED_EVENT
        val byKey = spec.associateBy { it.key }
        val typesMatch = properties.all { (key, value) -> byKey[key]?.let { matches(it.type, value) } == true } // an unknown key is a miss
        val requiredPresent = spec.all { !it.required || it.key in properties }
        return if (typesMatch && requiredPresent) PlanStatus.PLANNED else PlanStatus.INVALID_PROPERTIES
    }
}

private fun matches(type: PlanValueType, value: PropertyValue): Boolean = when (type) {
    PlanValueType.STRING -> value is PropertyValue.Text
    PlanValueType.BOOLEAN -> value is PropertyValue.Flag
    PlanValueType.NUMBER -> value is PropertyValue.Number
    // 1, 1.0, and 1e3 are whole numbers; 1.5 is not.
    PlanValueType.INTEGER -> value is PropertyValue.Number && try {
        BigDecimal(value.literal).stripTrailingZeros().scale() <= 0
    } catch (_: NumberFormatException) {
        false
    }
}
