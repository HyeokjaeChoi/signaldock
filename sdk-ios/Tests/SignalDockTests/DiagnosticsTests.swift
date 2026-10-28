import Foundation
import Testing

@testable import SignalDock

/// `diagnostics()` and the pieces behind the public calls: time format, log levels, opt-out store.
@Suite struct DiagnosticsTests {
  @Test func anEmptyQueueIsAnAvailableEmptySnapshot() async throws {
    let h = try await Harness.make()
    let d = try await h.sdk.diagnostics()
    #expect(
      d
        == Diagnostics(
          sdkVersion: "0.1.0", queuedEvents: 0, queuedBytes: 0, oldestPendingEpochMillis: nil, recentRejections: [],
          lastAck: nil, lastDeliveryError: nil, retryFailureCount: 0, nextAttemptEpochMillis: nil,
          storageAvailable: true, contractDeprecation: nil))
  }

  @Test func queuedEventsCountBytesAndOldest() async throws {
    let h = try await Harness.make(clock: FixedClock(millis: 5_000))
    _ = try await h.sdk.track("a")
    _ = try await h.sdk.track("b")
    let rows = try await h.sdk.queue.oldest(limit: 10)
    let d = try await h.sdk.diagnostics()
    #expect(d.queuedEvents == 2)
    #expect(d.queuedBytes == Int64(rows.map(\.sizeBytes).reduce(0, +)))
    #expect(d.oldestPendingEpochMillis == 5_000)
  }

  @Test func retryRejectionAndAckStateMapToTheDiagnosticsFields() async throws {
    let h = try await Harness.make()
    _ = try await h.sdk.track("e")
    let id = try #require(try await h.sdk.queue.oldest(limit: 1).first).eventId
    try await h.sdk.queue.applyAck(
      removeIds: [id], rejections: [RejectionRow(eventId: id, code: "BAD", rejectedAtMillis: 77)],
      state: RetryStateRow(
        failureCount: 3, nextAttemptAtMillis: 9_999, lastAckAtMillis: 50, lastAckAccepted: 4, lastAckDuplicate: 2,
        lastAckRejected: 1, lastErrorKind: "RATE_LIMITED", lastErrorHttpStatus: 429, lastErrorAtMillis: 40))
    let d = try await h.sdk.diagnostics()
    #expect(d.queuedEvents == 0)
    #expect(d.recentRejections == [RejectionDiagnostic(eventId: id, code: "BAD", rejectedAtEpochMillis: 77)])
    #expect(d.lastAck == AckDiagnostic(atEpochMillis: 50, accepted: 4, duplicate: 2, rejected: 1))
    #expect(d.lastDeliveryError == DeliveryErrorDiagnostic(kind: .rateLimited, httpStatus: 429, atEpochMillis: 40))
    #expect(d.retryFailureCount == 3)
    #expect(d.nextAttemptEpochMillis == 9_999)
    #expect(d.storageAvailable)
  }

  @Test func anUnknownStoredErrorKindGivesNoDeliveryError() async throws {
    let h = try await Harness.make()
    try await h.sdk.queue.saveRetryState(
      RetryStateRow(lastErrorKind: "FROM_THE_FUTURE", lastErrorHttpStatus: 500, lastErrorAtMillis: 1))
    #expect(try await h.sdk.diagnostics().lastDeliveryError == nil)
  }

  @Test func theDeliveryErrorKindsKeepTheNamesOfTheQueueSchema() {
    #expect(
      DeliveryErrorKind.allCases.map(\.rawValue) == [
        "NETWORK", "TIMEOUT", "RATE_LIMITED", "SERVER_ERROR", "AUTH_OR_CONFIGURATION", "CONTRACT",
        "PAYLOAD_TOO_LARGE", "INVALID_ACK",
      ])
  }

  @Test func theContractDeprecationNoticeAppearsInTheSnapshot() async throws {
    let h = try await Harness.make()
    #expect(try await h.sdk.diagnostics().contractDeprecation == nil)
    await h.sdk.notice.record(ContractDeprecation(deprecatedAtEpochMillis: 10, sunsetAtEpochMillis: nil))
    #expect(
      try await h.sdk.diagnostics().contractDeprecation
        == ContractDeprecation(deprecatedAtEpochMillis: 10, sunsetAtEpochMillis: nil))
    await h.sdk.queue.close()
    #expect(try await h.sdk.diagnostics().contractDeprecation != nil)  // also when the queue is unavailable
  }

  // MARK: Time format

  @Test func occurredAtIsUtcRfc3339WithThreeFractionDigits() {
    #expect(formatUtcMillis(0) == "1970-01-01T00:00:00.000Z")
    #expect(formatUtcMillis(1_760_000_000_123) == "2025-10-09T08:53:20.123Z")
    #expect(formatUtcMillis(-1) == "1969-12-31T23:59:59.999Z")
    #expect(formatUtcMillis(951_782_400_000) == "2000-02-29T00:00:00.000Z")  // a leap day of a 400-year leap year
    #expect(formatUtcMillis(4_107_542_400_000) == "2100-03-01T00:00:00.000Z")  // 2100 is not a leap year
  }

  @Test func occurredAtAgreesWithTheSystemFormatterOverManyTimes() {
    let reference = ISO8601DateFormatter()
    reference.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    var generator = SystemRandomNumberGenerator()
    for _ in 0..<2_000 {
      let millis = Int64.random(in: -2_000_000_000_000...8_000_000_000_000, using: &generator)
      #expect(formatUtcMillis(millis) == reference.string(from: Date(timeIntervalSince1970: Double(millis) / 1000)))
    }
  }

  // MARK: Logging

  @Test func logLevelsWriteLikeAndroid() {
    // LogLevel.debug writes debug, warn, and error; info and warn write warn and error; error writes error; none nothing.
    func written(_ level: LogLevel) -> [LogLevel] { [.debug, .warn, .error].filter { level.writes($0) } }
    #expect(written(.debug) == [.debug, .warn, .error])
    #expect(written(.info) == [.warn, .error])
    #expect(written(.warn) == [.warn, .error])
    #expect(written(.error) == [.error])
    #expect(written(.none) == [])
    #expect(SignalDockConfig(endpoint: "https://e.com", collectionKey: "k").logLevel == .warn)
  }

  @Test func everyMessageHasALevelAndTheSinkWritesWithoutCrashing() {
    let cases: [LogMessage] = [
      .initialized, .optOutSet, .optOutNotStored, .installationIdInvalid, .trackStorageFailed,
      .trackCancelledDuringStorage, .diagnosticsStorageFailed, .clearQueueStorageFailed,
    ]
    for level in LogLevel.allCases {
      let sink = OSLogSink(level: level)
      for message in cases { sink.log(message) }
    }
    #expect(cases.filter { $0.level == .debug } == [.initialized, .optOutSet])
  }

  // MARK: The opt-out store

  @Test func theOptOutStoreReadsOnlyTheMarkersExistence() async throws {
    let dir = try TempDirectory()
    #expect(await OptOutStore(directory: dir.url).isOptedOut() == false)
    let store = OptOutStore(directory: dir.url)
    #expect(await store.set(true))
    #expect(await OptOutStore(directory: dir.url).isOptedOut())
    #expect(await store.set(true))  // idempotent
    #expect(await store.set(false))
    #expect(await store.set(false))
    #expect(await OptOutStore(directory: dir.url).isOptedOut() == false)
  }
}
