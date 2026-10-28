import Foundation

// Backoff, hold, and header parsing of the sender. Mirrors the Android `RetryPolicy.kt` (ADR-0006 decisions 5, 8, 9;
// ADR-0030). Pure functions: the clock and the randomness come in as arguments.

internal let backoffBaseMillis: Int64 = 10_000
internal let backoffCapMillis: Int64 = 5 * 60_000
internal let holdRecheckMillis: Int64 = 30 * 60_000

/// Randomness boundary (Q88). Returns a value in `0...boundInclusive`.
internal protocol Jitter: Sendable {
  func nextMillis(boundInclusive: Int64) -> Int64
}

internal struct RandomJitter: Jitter {
  internal func nextMillis(boundInclusive: Int64) -> Int64 { Int64.random(in: 0...boundInclusive) }
}

/// Backoff base B before jitter (Q73): 10 s for the first failure, doubling, capped at 5 minutes. `failureCount` is
/// the count including the failure being scheduled (at least 1).
internal func backoffBase(failureCount: Int) -> Int64 {
  let doublings = min(max(failureCount - 1, 0), 10)  // 10 s * 2^5 already exceeds the cap
  return min(backoffCapMillis, backoffBaseMillis << Int64(doublings))
}

/// Saturating addition, so that a huge Retry-After cannot overflow into the past.
internal func satAdd(_ a: Int64, _ b: Int64) -> Int64 { b > Int64.max - a ? Int64.max : a + b }

/// Parses Retry-After as delta-seconds or an HTTP date. Returns the delay in milliseconds, or `nil` if the value is
/// absent, malformed, zero, or in the past (not a valid, positive wait). Never capped.
internal func parseRetryAfterMillis(_ value: String?, nowMillis: Int64) -> Int64? {
  guard let v = value?.trimmingCharacters(in: .whitespacesAndNewlines), !v.isEmpty else { return nil }
  if v.utf8.allSatisfy({ $0 >= 48 && $0 <= 57 }) {
    let seconds = Int64(v) ?? Int64.max  // all digits but too long: saturate
    if seconds == 0 { return nil }
    return seconds > Int64.max / 1000 ? Int64.max / 2 : seconds * 1000
  }
  guard let date = parseHttpDateMillis(v) else { return nil }
  let delay = date - nowMillis
  return delay > 0 ? delay : nil
}

/// An HTTP date for Retry-After: `Sun, 06 Nov 1994 08:49:37 GMT`, with `GMT` or `UTC`, a one- or two-digit day, and
/// the whole value consumed. Trailing text makes it invalid.
internal func parseHttpDateMillis(_ value: String) -> Int64? {
  parseDate(value, zones: ["GMT", "UTC"], strictDay: false)
}

/// Strict RFC 9110 IMF-fixdate with the literal `GMT` suffix and a two-digit day, for Sunset (ADR-0030).
internal func parseImfFixdateMillis(_ value: String) -> Int64? { parseDate(value, zones: ["GMT"], strictDay: true) }

private let weekdays: Set<String> = ["Mon,", "Tue,", "Wed,", "Thu,", "Fri,", "Sat,", "Sun,"]
private let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

/// `EEE, dd MMM yyyy HH:mm:ss zzz` read by hand: no formatter, so the result does not depend on the locale or the OS
/// version. Fields are checked as Android's non-lenient `SimpleDateFormat` does (ranges, no day 31 in April); the
/// weekday name is not compared with the date.
private func parseDate(_ value: String, zones: Set<String>, strictDay: Bool) -> Int64? {
  let parts = value.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
  guard parts.count == 6, weekdays.contains(parts[0]), zones.contains(parts[5]),
    let month = months.firstIndex(of: parts[2]), let year = digits(parts[3], count: 4...4),
    let day = digits(parts[1], count: strictDay ? 2...2 : 1...2)
  else { return nil }
  let clock = parts[4].split(separator: ":", omittingEmptySubsequences: false).map(String.init)
  guard clock.count == 3, let hour = digits(clock[0], count: 2...2), let minute = digits(clock[1], count: 2...2),
    let second = digits(clock[2], count: 2...2), hour < 24, minute < 60, second < 60,
    day >= 1, day <= daysInMonth(year: year, month: month + 1)
  else { return nil }
  let days = daysFromCivil(year: Int64(year), month: Int64(month + 1), day: Int64(day))
  return ((days * 24 + Int64(hour)) * 60 + Int64(minute)) * 60_000 + Int64(second) * 1000
}

/// The number in `text` when it is only ASCII digits and has a length in `count`.
private func digits(_ text: String, count: ClosedRange<Int>) -> Int? {
  guard count.contains(text.utf8.count), text.utf8.allSatisfy({ $0 >= 48 && $0 <= 57 }) else { return nil }
  return Int(text)
}

private func daysInMonth(year: Int, month: Int) -> Int {
  let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
  return [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
}

/// Days since 1970-01-01 of a civil date (H. Hinnant, "chrono-compatible low-level date algorithms").
private func daysFromCivil(year: Int64, month: Int64, day: Int64) -> Int64 {
  let y = month <= 2 ? year - 1 : year
  let era = (y >= 0 ? y : y - 399) / 400
  let yearOfEra = y - era * 400
  let dayOfYear = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1
  let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
  return era * 146_097 + dayOfEra - 719_468
}

/// RFC 9745: a structured field date, `@<epoch-seconds>`. Anything else is `nil`.
internal func parseDeprecationMillis(_ value: String?) -> Int64? {
  guard let v = value?.trimmingCharacters(in: .whitespacesAndNewlines), v.utf8.first == UInt8(ascii: "@") else {
    return nil
  }
  var body = v.dropFirst()
  if body.first == "-" { body = body.dropFirst() }
  guard (1...15).contains(body.utf8.count), body.utf8.allSatisfy({ $0 >= 48 && $0 <= 57 }),
    let seconds = Int64(v.dropFirst()), abs(seconds) <= maxSunsetSeconds
  else { return nil }
  return seconds * 1000
}

private let maxSunsetSeconds: Int64 = 253_402_300_799  // year 9999
