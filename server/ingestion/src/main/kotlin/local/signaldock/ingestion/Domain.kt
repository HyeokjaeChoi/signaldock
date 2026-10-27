package local.signaldock.ingestion

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** A request-level failure. The caller maps `code` to one error response and stores nothing. */
public class RequestFailure(public val code: String) : RuntimeException(code)

/** A flat event property value. Numbers keep their source literal; they are never rounded. */
public sealed interface PropertyValue {
    public data class Text(val value: String) : PropertyValue
    public data class Flag(val value: Boolean) : PropertyValue
    public data class Number(val literal: String) : PropertyValue

    /** A null, array, or object value. It is never valid, but the rules report it per item. */
    public data object Unsupported : PropertyValue
}

/** Platform of the SDK (ADR-0046). A new platform is a contract change. */
public enum class SdkPlatform(public val wire: String) {
    ANDROID("android"),
    IOS("ios");

    public companion object {
        /** Wire names are case-sensitive. */
        public fun fromWire(wire: String): SdkPlatform? = entries.firstOrNull { it.wire == wire }
    }
}

public data class StoredEvent(
    val eventId: UUID,
    val installationId: UUID,
    val name: String,
    val occurredAt: Instant,
    val sdkVersion: String,
    val sdkPlatform: SdkPlatform,
    val properties: Map<String, PropertyValue>,
)

public data class ValidatedBatch(val items: List<ValidatedItem>)

public data class ValidatedItem(
    val index: Int,
    val eventId: String?,
    val event: StoredEvent?,
    val rejectionCode: String?,
)

public enum class ReceiptStatus { ACCEPTED, DUPLICATE, REJECTED }

public data class ItemReceipt(
    val index: Int,
    val status: ReceiptStatus,
    val eventId: String?,
    val code: String? = null,
)

/** Content comparison for duplicate handling. It intentionally ignores property order. */
public fun sameContent(a: StoredEvent, b: StoredEvent): Boolean =
    a.eventId == b.eventId &&
        a.installationId == b.installationId &&
        a.name == b.name &&
        a.occurredAt == b.occurredAt &&
        a.sdkVersion == b.sdkVersion &&
        a.sdkPlatform == b.sdkPlatform &&
        sameProperties(a.properties, b.properties)

private fun sameProperties(a: Map<String, PropertyValue>, b: Map<String, PropertyValue>): Boolean =
    a.size == b.size && a.all { (key, left) -> b[key]?.let { sameValue(left, it) } == true }

private fun sameValue(left: PropertyValue, right: PropertyValue): Boolean = when {
    left is PropertyValue.Text && right is PropertyValue.Text -> left.value == right.value
    left is PropertyValue.Flag && right is PropertyValue.Flag -> left.value == right.value
    left is PropertyValue.Number && right is PropertyValue.Number -> try {
        BigDecimal(left.literal).compareTo(BigDecimal(right.literal)) == 0
    } catch (_: NumberFormatException) {
        false
    }
    else -> false
}
