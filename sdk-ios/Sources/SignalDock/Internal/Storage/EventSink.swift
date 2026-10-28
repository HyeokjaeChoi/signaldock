/// Storage boundary for the queue (Q88). The SQLite queue implements it; tests supply fakes.
internal protocol EventSink: Sendable {
  func add(_ event: EventRow, limits: QueueLimits) async throws -> AddOutcome
}

extension QueueStore: EventSink {
  internal func add(_ event: EventRow, limits: QueueLimits) async throws -> AddOutcome {
    try addWithinLimits(event, limits: limits)
  }
}
