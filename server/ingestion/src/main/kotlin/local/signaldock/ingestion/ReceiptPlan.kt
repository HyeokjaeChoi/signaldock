package local.signaldock.ingestion

/** Items that share one event ID and carry the same content, in request order. */
public class EventGroup internal constructor(public val event: StoredEvent, internal val items: List<ValidatedItem>) {
    /**
     * Receipts once storage has answered. `inserted` is true when this request stored the row.
     * `equal` is true when the stored row has the same content as this group.
     */
    public fun receipts(inserted: Boolean, equal: Boolean): List<ItemReceipt> =
        items.mapIndexed { offset, item ->
            when {
                !equal -> ItemReceipt(item.index, ReceiptStatus.REJECTED, item.eventId, "CONFLICT")
                inserted && offset == 0 -> ItemReceipt(item.index, ReceiptStatus.ACCEPTED, item.eventId)
                else -> ItemReceipt(item.index, ReceiptStatus.DUPLICATE, item.eventId)
            }
        }
}

/**
 * What a batch needs from storage. `decided` receipts need no storage. `groups` are in a stable
 * order, which also prevents opposite-order batches from deadlocking on unique keys.
 */
public class IngestPlan internal constructor(
    private val size: Int,
    private val decided: List<ItemReceipt>,
    public val groups: List<EventGroup>,
) {
    /** All receipts in request order, once storage has answered for every group. */
    public fun complete(stored: List<ItemReceipt>): List<ItemReceipt> {
        val results = arrayOfNulls<ItemReceipt>(size)
        (decided + stored).forEach { results[it.index] = it }
        return results.map { requireNotNull(it) }
    }
}

public fun planBatch(batch: ValidatedBatch): IngestPlan {
    val decided = mutableListOf<ItemReceipt>()
    batch.items.filter { it.event == null }.forEach { item ->
        decided += ItemReceipt(item.index, ReceiptStatus.REJECTED, item.eventId, item.rejectionCode ?: "INVALID_EVENT")
    }
    val groups = mutableListOf<EventGroup>()
    batch.items.filter { it.event != null }.groupBy { it.event!!.eventId }
        .toSortedMap().forEach { (_, group) ->
            val event = group.first().event!!
            if (group.any { !sameContent(event, it.event!!) }) {
                group.forEach { item ->
                    decided += ItemReceipt(item.index, ReceiptStatus.REJECTED, item.eventId, "CONFLICT")
                }
            } else {
                groups += EventGroup(event, group)
            }
        }
    return IngestPlan(batch.items.size, decided, groups)
}
