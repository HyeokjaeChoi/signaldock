import Foundation
import Testing

@testable import SignalDock

/// Backoff and header parsing (ADR-0006 decisions 5, 8, 9; ADR-0030), against the Android `RetryPolicy`.
@Suite struct RetryPolicyTests {
  @Test func backoffBaseDoublesFromTenSecondsToAFiveMinuteCap() {
    let seconds = (1...8).map { backoffBase(failureCount: $0) / 1000 }
    #expect(seconds == [10, 20, 40, 80, 160, 300, 300, 300])
    #expect(backoffBase(failureCount: 0) == 10_000)
    #expect(backoffBase(failureCount: Int.max) == 300_000)
  }

  @Test func additionSaturatesInsteadOfOverflowingIntoThePast() {
    #expect(satAdd(5, 7) == 12)
    #expect(satAdd(Int64.max - 1, 5) == Int64.max)
    #expect(satAdd(100, Int64.max) == Int64.max)
  }

  // MARK: Retry-After

  @Test func retryAfterAsDeltaSecondsIsMilliseconds() {
    #expect(parseRetryAfterMillis("120", nowMillis: 0) == 120_000)
    #expect(parseRetryAfterMillis(" 7 ", nowMillis: 0) == 7000)
    #expect(parseRetryAfterMillis("1", nowMillis: 0) == 1000)
  }

  @Test(arguments: [nil, "", " ", "0", "00", "-5", "abc", "1.5", "1e3", "+5", "٣", "12 s", "Sun"])
  func retryAfterThatIsNotAPositiveWaitIsIgnored(value: String?) {
    #expect(parseRetryAfterMillis(value, nowMillis: 0) == nil)
  }

  @Test func aHugeRetryAfterIsNotCappedAndCannotOverflow() {
    #expect(parseRetryAfterMillis("99999999999999999999999999", nowMillis: 0) == Int64.max / 2)
    #expect(parseRetryAfterMillis("9223372036854775807", nowMillis: 0) == Int64.max / 2)
    #expect(parseRetryAfterMillis("86400000", nowMillis: 0) == 86_400_000_000)  // 1000 days: no cap
  }

  @Test func retryAfterAsAnHttpDateIsTheDistanceFromNow() {
    let date = "Sun, 06 Nov 1994 08:49:37 GMT"
    #expect(parseHttpDateMillis(date) == 784_111_777_000)
    #expect(parseRetryAfterMillis(date, nowMillis: 784_111_777_000 - 60_000) == 60_000)
    #expect(parseRetryAfterMillis(date, nowMillis: 784_111_777_000) == nil)  // not positive
    #expect(parseRetryAfterMillis(date, nowMillis: 784_111_777_000 + 1) == nil)  // in the past
    #expect(parseHttpDateMillis("Sun, 06 Nov 1994 08:49:37 UTC") == 784_111_777_000)
    #expect(parseHttpDateMillis("Sun, 6 Nov 1994 08:49:37 GMT") == 784_111_777_000)  // Retry-After allows one digit
  }

  @Test(arguments: [
    "Sun, 06 Nov 1994 08:49:37 GMT extra", "Sun, 06 Nov 1994 08:49:37", "Sun, 06 Nov 1994 08:49:37 PST",
    "Sun, 06 Nov 1994 08:49:37  GMT", "Sun 06 Nov 1994 08:49:37 GMT", "Fun, 06 Nov 1994 08:49:37 GMT",
    "Sun, 06 Nov 94 08:49:37 GMT", "Sun, 31 Apr 1994 08:49:37 GMT", "Sun, 29 Feb 1995 08:49:37 GMT",
    "Sun, 06 Nov 1994 24:49:37 GMT", "Sun, 06 Nov 1994 08:60:37 GMT", "Sun, 06 Nov 1994 08:49:60 GMT",
    "Sun, 06 Nov 1994 8:49:37 GMT", "Sun, 00 Nov 1994 08:49:37 GMT", "Sun, 06 Nov 1994 08:49 GMT",
    "Sun, 06 Xyz 1994 08:49:37 GMT",
  ])
  func aMalformedHttpDateIsInvalid(value: String) {
    #expect(parseHttpDateMillis(value) == nil)
    #expect(parseRetryAfterMillis(value, nowMillis: 0) == nil)
  }

  @Test func aLeapDayIsValidOnlyInALeapYear() {
    #expect(parseHttpDateMillis("Thu, 29 Feb 2024 00:00:00 GMT") == 1_709_164_800_000)
    #expect(parseHttpDateMillis("Sat, 29 Feb 1900 00:00:00 GMT") == nil)
    #expect(parseHttpDateMillis("Tue, 29 Feb 2000 00:00:00 GMT") == 951_782_400_000)
  }

  // MARK: Sunset and Deprecation

  @Test func sunsetIsAStrictImfFixdateWithTheLiteralGmt() {
    #expect(parseImfFixdateMillis("Sun, 06 Nov 1994 08:49:37 GMT") == 784_111_777_000)
    for bad in [
      "Sun, 6 Nov 1994 08:49:37 GMT", "Sun, 06 Nov 1994 08:49:37 UTC", "Sun, 06 Nov 1994 08:49:37 PST",
      "Sunday, 06-Nov-94 08:49:37 GMT", "Sun Nov  6 08:49:37 1994", "Sun, 06 Nov 1994 08:49:37 GMT.",
    ] {
      #expect(parseImfFixdateMillis(bad) == nil, "\(bad)")
    }
  }

  @Test func deprecationIsAStructuredFieldDateOnly() {
    #expect(parseDeprecationMillis("@1688169599") == 1_688_169_599_000)
    #expect(parseDeprecationMillis(" @1688169599 ") == 1_688_169_599_000)
    #expect(parseDeprecationMillis("@0") == 0)
    #expect(parseDeprecationMillis("@-86400") == -86_400_000)
    #expect(parseDeprecationMillis("@253402300799") == 253_402_300_799_000)
    for bad in [
      nil, "", "@", "@-", "true", "1688169599", "@1.5", "@+5", "@1e3", "@12 3", "@253402300800",
      "@1234567890123456", "?1", "@0x10",
    ] as [String?] {
      #expect(parseDeprecationMillis(bad) == nil, "\(bad ?? "nil")")
    }
  }
}
