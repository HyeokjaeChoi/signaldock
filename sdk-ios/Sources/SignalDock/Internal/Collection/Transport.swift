import Foundation

internal let contractVersion = "1"

/// The encoder for everything the SDK sends and stores. Sorted keys give the same bytes for the same event, and
/// unescaped slashes match the Android (kotlinx.serialization) output.
internal func transportEncoder() -> JSONEncoder {
  let encoder = JSONEncoder()
  encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
  return encoder
}

/// Canonical size of one event as sent (spec section 4): the uncompressed UTF-8 bytes of a `BatchIngestRequest` that
/// holds this single event, with the wrapper, `installationId`, common fields, and JSON escaping. The 16 KiB event
/// limit and the 16 MiB queue total use it. The sender must reuse this function, not measure again.
internal func singleEventRequestBytes(installationId: String, event: EventIngest) throws -> Int {
  try batchRequestBytes(installationId: installationId, events: [event])
}

/// Canonical size of a whole batch request: the UTF-8 bytes of the encoded `BatchIngestRequest`.
internal func batchRequestBytes(installationId: String, events: [EventIngest]) throws -> Int {
  try transportEncoder().encode(BatchIngestRequest(installationId: installationId, events: events)).count
}

/// UTC RFC 3339 with exactly 3 fraction digits (Q81), from epoch milliseconds. Written by hand from the civil-date
/// algorithm, so it needs no formatter and gives the same text on every OS version.
internal func formatUtcMillis(_ millis: Int64) -> String {
  let seconds = millis.floorDiv(1000)
  let milli = millis - seconds * 1000
  let days = seconds.floorDiv(86_400)
  let secondOfDay = seconds - days * 86_400
  // Days since 1970-01-01 to a civil date (H. Hinnant, "chrono-compatible low-level date algorithms").
  let z = days + 719_468
  let era = z.floorDiv(146_097)
  let dayOfEra = z - era * 146_097
  let yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
  let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
  let monthPrime = (5 * dayOfYear + 2) / 153
  let day = dayOfYear - (153 * monthPrime + 2) / 5 + 1
  let month = monthPrime < 10 ? monthPrime + 3 : monthPrime - 9
  let year = yearOfEra + era * 400 + (month <= 2 ? 1 : 0)
  return String(
    format: "%04lld-%02lld-%02lldT%02lld:%02lld:%02lld.%03lldZ", year, month, day, secondOfDay / 3_600,
    secondOfDay % 3_600 / 60, secondOfDay % 60, milli)
}

extension Int64 {
  /// Division that rounds toward negative infinity, so times before 1970 stay correct.
  fileprivate func floorDiv(_ divisor: Int64) -> Int64 {
    let quotient = self / divisor
    return (self % divisor != 0 && (self < 0) != (divisor < 0)) ? quotient - 1 : quotient
  }
}
