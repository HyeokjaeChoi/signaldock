import Foundation

// SDK health report (ADR-0036, ADR-0047 D1): the SDK counts the events it dropped, by reason, and sends the counts to
// `POST /v1/client-reports`. The counters are in the queue database (`dropped_counts`, V1), so a restart does not lose
// them. Mirrors the Android `internal/delivery/ClientReport.kt`.

extension TrackResult {
  /// The reason that `track()` dropped the event, or `nil` when it did not drop it (a stored event, or a result that
  /// is not a drop). Opt-out and queue clearing by the host are not drops (Q156), so any result that is not one of
  /// the three below is not counted.
  internal var dropReason: DiscardReason? {
    switch self {
    case .invalid(let reason, _): reason == .eventTooLarge ? .eventTooLarge : .invalidEvent
    case .queueFull: .queueFull
    case .storageError: .storageError
    case .success, .optedOut: nil
    }
  }
}

/// A report body and the counters it carries. Counters of an unknown reason are not sent and stay.
internal struct ClientReport: Sendable, Equatable {
  internal let body: Data
  internal let rows: [DroppedCountRow]
}

/// Builds the body from the stored counters. `nil` when nothing can be sent: no counter above zero, or only reasons
/// that this SDK version does not know.
internal func buildClientReport(_ counters: [DroppedCountRow]) throws -> ClientReport? {
  var sent: [DroppedCountRow] = []
  var entries: [DiscardedEventCount] = []
  for row in counters {
    guard let reason = DiscardReason(rawValue: row.reason), row.count > 0 else { continue }
    let count = min(row.count, maxDroppedCount)
    entries.append(DiscardedEventCount(reason: reason, count: count))
    sent.append(DroppedCountRow(reason: row.reason, count: count))
  }
  if entries.isEmpty { return nil }
  let request = ClientReportRequest(sdkVersion: SignalDockSDK.version, sdkPlatform: .ios, discardedEvents: entries)
  return ClientReport(body: try transportEncoder().encode(request), rows: sent)
}

internal enum ReportOutcome: Sendable, Equatable {
  /// The server stored the report: subtract the counts.
  case sent
  /// The server cannot take this report (404 from an old server, another permanent 4xx): subtract the counts, no retry.
  case discarded
  /// A transient failure: keep the counters for the next send cycle.
  case kept
}

internal func classifyReport(_ result: TransportResult) -> ReportOutcome {
  switch result {
  case .failed: .kept
  case .http(let http):
    switch http.status {
    case 200...299: .sent
    case 408, 429: .kept
    case 501, 505: .discarded
    case 500...599: .kept
    default: .discarded  // 404, 400, 401, 403, 413, 415, redirects, 1xx
    }
  }
}
