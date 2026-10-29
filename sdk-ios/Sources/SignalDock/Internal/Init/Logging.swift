import os

/// The fixed messages the SDK can write. A message is a case, so it can never hold event content, event names,
/// installation IDs, credentials, HTTP bodies, or system error text (Q115, ADR-0047 D13).
internal enum LogMessage: Sendable, Equatable {
  case initialized
  case optOutSet
  case optOutNotStored
  case installationIdInvalid
  case trackStorageFailed
  case trackCancelledDuringStorage
  case diagnosticsStorageFailed
  case clearQueueStorageFailed
  case deliveryAttemptStarted
  case deliveryAttemptFinished
  case deliveryFailed
  case deliveryPaused
  case deliveryResumed
  case deliveryRunFailed
  case dropCounterFailed
  case clientReportFailed
  case contractDeprecated

  internal var level: LogLevel {
    switch self {
    case .initialized, .optOutSet, .deliveryAttemptStarted, .deliveryAttemptFinished, .deliveryFailed: .debug
    case .optOutNotStored, .installationIdInvalid, .trackStorageFailed, .trackCancelledDuringStorage,
      .diagnosticsStorageFailed, .clearQueueStorageFailed, .deliveryPaused, .deliveryResumed, .deliveryRunFailed,
      .contractDeprecated, .dropCounterFailed, .clientReportFailed:
      .warn
    }
  }
}

/// The SDK log boundary.
internal protocol SDKLogger: Sendable {
  func log(_ message: LogMessage)
}

/// Writes to `os.Logger` (subsystem `local.signaldock`). A message is written when its level is at or above the
/// configured level. The texts are literals, because no message holds a value; a value would be interpolated with
/// the private privacy level (`scripts/check-repo-rules.sh` rejects the public one).
internal struct OSLogSink: SDKLogger {
  internal let level: LogLevel
  private let logger = Logger(subsystem: "local.signaldock", category: "SignalDock")

  internal init(level: LogLevel) { self.level = level }

  internal func log(_ message: LogMessage) {
    guard level.writes(message.level) else { return }
    switch message {
    case .initialized: logger.debug("initialized")
    case .optOutSet: logger.debug("opt-out set")
    case .optOutNotStored: logger.warning("opt-out state could not be stored")
    case .installationIdInvalid: logger.warning("installation id file is invalid; generating a new id")
    case .trackStorageFailed: logger.warning("track storage failed")
    case .trackCancelledDuringStorage:
      logger.warning("track cancelled during storage; the event may already be stored")
    case .diagnosticsStorageFailed: logger.warning("diagnostics storage failed")
    case .clearQueueStorageFailed: logger.warning("clearQueue storage failed")
    case .deliveryAttemptStarted: logger.debug("delivery attempt started")
    case .deliveryAttemptFinished: logger.debug("delivery attempt finished")
    case .deliveryFailed: logger.debug("delivery failed; see diagnostics for the kind")
    case .deliveryPaused:
      logger.warning(
        "delivery paused by a configuration, authentication or contract error; rechecking every 30 minutes")
    case .deliveryResumed: logger.warning("delivery resumed after a successful ACK")
    case .deliveryRunFailed: logger.warning("delivery run failed in storage; the queue is unchanged")
    case .dropCounterFailed: logger.warning("drop counter could not be written; the drop is not counted")
    case .clientReportFailed: logger.warning("client report failed in storage; the counters are unchanged")
    case .contractDeprecated:
      logger.warning("server contract version is deprecated; see diagnostics for the dates")
    }
  }
}
