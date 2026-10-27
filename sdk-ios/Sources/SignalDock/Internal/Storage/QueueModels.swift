/// Queue item. `payloadJson` is the transport JSON of one event (contract `contractVersion`).
/// `sizeBytes` is the canonical size of that event as sent (ADR-0003 item 3); the sender computes it.
/// Mirrors the Android `EventEntity`.
internal struct EventRow: Equatable, Sendable {
  internal var seq: Int64 = 0
  internal var eventId: String
  internal var installationId: String
  internal var contractVersion: String
  internal var payloadJson: String
  internal var sizeBytes: Int
  internal var createdAtMillis: Int64
}

/// Permanent rejection, stored without event payload or name. The last `maxRejections` are kept.
internal struct RejectionRow: Equatable, Sendable {
  internal var id: Int64 = 0
  internal var eventId: String
  internal var code: String
  internal var rejectedAtMillis: Int64
}

/// Events the SDK dropped, counted per reason (the `DiscardReason` wire name, ADR-0036).
internal struct DroppedCountRow: Equatable, Sendable {
  internal var reason: String
  internal var count: Int
}

/// The single retry-state row (id 1). Mirrors the Android `RetryStateEntity` (ADR-0006, ADR-0025).
internal struct RetryStateRow: Equatable, Sendable {
  internal var failureCount: Int = 0
  internal var nextAttemptAtMillis: Int64?
  /// The wait that was meant when `nextAttemptAtMillis` was stored. A clock moved back cannot extend it (ADR-0025).
  internal var waitMillis: Int64?
  internal var lastAckAtMillis: Int64?
  internal var lastAckAccepted: Int = 0
  internal var lastAckDuplicate: Int = 0
  internal var lastAckRejected: Int = 0
  /// A delivery error kind name; content-free.
  internal var lastErrorKind: String?
  internal var lastErrorHttpStatus: Int?
  internal var lastErrorAtMillis: Int64?
  /// Normal sending is stopped (auth, configuration, or contract error). One probe batch per recheck.
  internal var hold: Bool = false
  /// Batch event cap after a 413 split; nil means the default (ADR-0006).
  internal var maxBatchEvents: Int?
}

internal struct QueueUsage: Equatable, Sendable {
  internal var count: Int
  internal var bytes: Int64
  internal var oldestCreatedAtMillis: Int64?
}

internal struct DiagnosticsSnapshot: Equatable, Sendable {
  internal var usage: QueueUsage
  internal var retry: RetryStateRow?
  internal var rejections: [RejectionRow]
}

/// The last 100 rejections are kept.
internal let maxRejections = 100

/// A drop counter stops here, like the contract limit of one report entry (ADR-0036).
internal let maxDroppedCount = 1_000_000

/// The `DiscardReason` wire name for an event that a valid ACK rejected (contract `DiscardReason`).
internal let serverRejectedReason = "server_rejected"

internal struct QueueLimits: Equatable, Sendable {
  internal var maxEvents = 10_000
  internal var maxQueueBytes: Int64 = 16 * 1024 * 1024
  internal var maxEventBytes = 16 * 1024
}

internal enum AddOutcome: Equatable, Sendable {
  case added
  case queueFull
}
