package local.signaldock.server

import local.signaldock.ingestion.ItemReceipt
import local.signaldock.ingestion.ReceiptStatus
import local.signaldock.ingestion.TrackingPlan
import local.signaldock.ingestion.ValidatedBatch
import local.signaldock.ingestion.planBatch
import local.signaldock.persistence.Database
import local.signaldock.persistence.EventStore
import local.signaldock.persistence.RequestBudget
import org.openapitools.client.models.BatchAckResponse
import org.openapitools.client.models.ItemAck

/** Glue: ingestion decides, persistence stores, and this layer shapes the transport response. */
internal class Ingestion(private val database: Database, trackingPlan: TrackingPlan? = null, private val control: CollectionControl = CollectionControl()) {
    private val store = EventStore(database, trackingPlan)

    suspend fun ingest(batch: ValidatedBatch, budget: RequestBudget = database.budget()): BatchAckResponse =
        BatchAckResponse(store.store(planBatch(control.apply(batch)), budget).map(::toAck))

    private fun toAck(receipt: ItemReceipt) = ItemAck(
        receipt.index,
        when (receipt.status) {
            ReceiptStatus.ACCEPTED -> ItemAck.Status.ACCEPTED
            ReceiptStatus.DUPLICATE -> ItemAck.Status.DUPLICATE
            ReceiptStatus.REJECTED -> ItemAck.Status.REJECTED
        },
        receipt.eventId,
        receipt.code,
    )
}
