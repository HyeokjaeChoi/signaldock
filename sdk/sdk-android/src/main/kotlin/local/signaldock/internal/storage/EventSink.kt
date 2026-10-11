package local.signaldock.internal.storage

/** Storage boundary for the queue (Q88). Room implementation below; fakes in tests. */
internal interface EventSink {
    suspend fun add(event: EventEntity, limits: QueueLimits): AddOutcome
}

internal class RoomEventSink(private val dao: QueueDao) : EventSink {
    override suspend fun add(event: EventEntity, limits: QueueLimits) = dao.addWithinLimits(event, limits)
}
