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

  internal var level: LogLevel {
    switch self {
    case .initialized, .optOutSet: .debug
    case .optOutNotStored, .installationIdInvalid, .trackStorageFailed, .trackCancelledDuringStorage,
      .diagnosticsStorageFailed, .clearQueueStorageFailed:
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
    }
  }
}
