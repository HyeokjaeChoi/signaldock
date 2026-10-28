import Foundation

internal let maxBatchEvents = 50
internal let maxBatchBytes = 256 * 1024

internal struct Batch: Sendable {
  internal let events: [EventRow]
  internal let contractVersion: String
  internal let body: Data
}

/// Request body of a batch, built from the stored per-event transport JSON, so that a resend carries the exact bytes
/// that were stored (ADR-0006 decision 3). It equals what `transportEncoder()` writes for a `BatchIngestRequest` with
/// the same events: the encoder sorts keys, so `events` comes before `installationId`. A test checks this.
internal enum BatchBody {
  internal static let prefix = #"{"events":["#

  /// `],"installationId":<JSON string>}`.
  internal static func suffix(installationId: String) throws -> String {
    #"],"installationId":"# + String(decoding: try transportEncoder().encode(installationId), as: UTF8.self) + "}"
  }

  internal static func make(installationId: String, payloads: [String]) throws -> Data {
    Data((prefix + payloads.joined(separator: ",") + (try suffix(installationId: installationId))).utf8)
  }
}

/// The finished body is not the size that the arithmetic gave. A bug, never data.
internal struct BatchSizeMismatch: Error {}

/// `buildBatch` got no rows: never trap the host app for it.
internal struct EmptyBatch: Error {}

/// Takes events from the front of `rows` while the request stays within `maxEvents` and `maxBytes` (uncompressed
/// UTF-8 JSON including the wrapper, spec section 4). `rows` must be one batch group: same contract version and
/// installation ID, oldest first. At least one event is always taken; a stored event is at most 16 KiB, so it fits.
internal func buildBatch(rows: [EventRow], maxEvents: Int = maxBatchEvents, maxBytes: Int = maxBatchBytes) throws
  -> Batch
{
  guard let first = rows.first else { throw EmptyBatch() }
  let suffix = try BatchBody.suffix(installationId: first.installationId)
  var size = BatchBody.prefix.utf8.count + suffix.utf8.count
  var taken: [EventRow] = []
  for row in rows {
    if taken.count >= maxEvents { break }
    let add = row.payloadJson.utf8.count + (taken.isEmpty ? 0 : 1)  // comma
    if !taken.isEmpty && size + add > maxBytes { break }
    size += add
    taken.append(row)
  }
  let body = try BatchBody.make(installationId: first.installationId, payloads: taken.map(\.payloadJson))
  guard body.count == size else { throw BatchSizeMismatch() }
  return Batch(events: taken, contractVersion: first.contractVersion, body: body)
}
