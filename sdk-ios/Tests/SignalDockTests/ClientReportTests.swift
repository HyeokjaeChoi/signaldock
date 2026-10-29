import Foundation
import Testing

@testable import SignalDock

/// SDK client reports (ADR-0036, ADR-0047 D1), the iOS side of the Android `ClientReportTest`: counting, saturation,
/// sending, and what each answer does to the counters. The transport, the clock, and the jitter are fakes: no test
/// waits for time.
@Suite struct ClientReportTests {
  // MARK: Helpers

  private func counts(_ queue: QueueStore) async throws -> [String: Int] {
    Dictionary(uniqueKeysWithValues: try await queue.droppedCounts().map { ($0.reason, $0.count) })
  }

  private func object(_ request: BatchHttpRequest) throws -> [String: Any] {
    try #require(try JSONSerialization.jsonObject(with: request.body) as? [String: Any])
  }

  private func entries(_ request: BatchHttpRequest) throws -> [String: Int] {
    let list = try #require(try object(request)["discardedEvents"] as? [[String: Any]])
    return Dictionary(
      uniqueKeysWithValues: try list.map { (try #require($0["reason"] as? String), try #require($0["count"] as? Int)) })
  }

  /// A sink that always answers `queueFull`, so that a closed queue still gives a drop.
  private struct FullSink: EventSink {
    func add(_ event: EventRow, limits: QueueLimits) async throws -> AddOutcome { .queueFull }
  }

  // MARK: Counting

  @Test func trackResultsMapToDropReasonsAndAStoredEventOrANonDropMapsToNone() {
    #expect(TrackResult.success(eventId: "e").dropReason == nil)
    #expect(TrackResult.optedOut.dropReason == nil)
    #expect(TrackResult.queueFull.dropReason == .queueFull)
    #expect(TrackResult.storageError(message: "m").dropReason == .storageError)
    #expect(TrackResult.invalid(reason: .eventTooLarge, field: nil).dropReason == .eventTooLarge)
    for reason in InvalidReason.allCases where reason != .eventTooLarge {
      #expect(TrackResult.invalid(reason: reason, field: "name").dropReason == .invalidEvent, "\(reason)")
    }
  }

  @Test func trackCountsQueueFullAndInvalidEventsAndDoesNotCountAStoredEvent() async throws {
    let h = try await Harness.make(limits: QueueLimits(maxEvents: 1))
    guard case .success = try await h.sdk.track("ok") else { Issue.record("expected success"); return }
    #expect(try await h.sdk.track("full") == .queueFull)
    #expect(try await h.sdk.track("full") == .queueFull)
    guard case .invalid = try await h.sdk.track(" bad") else { Issue.record("expected invalid"); return }
    guard case .invalid = try await h.sdk.track("") else { Issue.record("expected invalid"); return }
    #expect(try await counts(h.sdk.queue) == ["queue_full": 2, "invalid_event": 2])
  }

  @Test func anEventOverTheSizeLimitIsCountedAsTooLargeNotAsInvalid() async throws {
    let h = try await Harness.make()
    let result = try await h.sdk.track("big") { $0.property("k", String(repeating: "x", count: 20_000)) }
    #expect(result == .invalid(reason: .eventTooLarge, field: nil))
    #expect(try await counts(h.sdk.queue) == ["event_too_large": 1])
  }

  @Test func aStorageErrorIsCounted() async throws {
    let h = try await Harness.make()
    let sdk = h.replacingSink(ThrowingSink(error: OtherError()))
    guard case .storageError = try await sdk.track("e") else { Issue.record("expected a storage error"); return }
    #expect(try await counts(h.sdk.queue) == ["storage_error": 1])
  }

  @Test func optOutIsNotADrop() async throws {
    let h = try await Harness.make()
    _ = await h.sdk.setOptOut(true)
    #expect(try await h.sdk.track("e") == .optedOut)
    #expect(try await counts(h.sdk.queue).isEmpty)
  }

  @Test func countersStopAtTheLimitAndNeverOverflow() async throws {
    let h = try await Harness.make()
    let queue = h.sdk.queue
    try await queue.addDropped(reason: "queue_full", count: maxDroppedCount - 1)
    try await queue.addDropped(reason: "queue_full", count: 1)
    #expect(try await counts(queue)["queue_full"] == maxDroppedCount)
    try await queue.addDropped(reason: "queue_full", count: 1)
    try await queue.addDropped(reason: "queue_full", count: Int.max)
    #expect(try await counts(queue)["queue_full"] == maxDroppedCount)
  }

  @Test func countersSurviveARestartOfTheSDK() async throws {
    let first = try await Harness.make()
    _ = try await first.sdk.track("")  // invalid
    await first.sdk.queue.close()
    let second = try await Harness.make(dir: first.dir)
    #expect(try await counts(second.sdk.queue) == ["invalid_event": 1])
  }

  @Test func aFailureToWriteTheCounterIsLoggedAndNeverChangesTheResult() async throws {
    let h = try await Harness.make()
    let sdk = h.replacingSink(FullSink())
    await h.sdk.queue.close()  // the counter write now fails with a storage error
    #expect(try await sdk.track("e") == .queueFull)
    #expect(h.logger.warnings == [.dropCounterFailed])
  }

  // MARK: The server-rejected count is part of the ACK commit

  @Test func permanentRejectionsInAValidAckAreCountedInTheSameCommitAndReported() async throws {
    let body = ackJson(ids: ["event-1", "event-2", "event-3"], statuses: ["accepted", "rejected", "accepted"])
    let rig = try await SenderRig.make(replies: [.body(body)])
    await rig.transport.enqueueReports(.http(HttpResult(status: 204)))
    #expect(try await rig.sender.run().end == .queueEmpty)
    let reports = await rig.transport.reports
    #expect(reports.count == 1)
    #expect(try entries(reports[0]) == ["server_rejected": 1])
    #expect(try await counts(rig.queue).isEmpty)  // sent, so subtracted
  }

  @Test func anInvalidAckAppliesNothingSoItCountsNothingAndSendsNoReport() async throws {
    let rig = try await SenderRig.make(replies: [.body(#"{"results":[]}"#)], events: 1)
    _ = try await rig.sender.run()
    #expect(try await counts(rig.queue).isEmpty)
    #expect(await rig.transport.reports.isEmpty)
  }

  // MARK: Sending

  @Test func theReportGoesOnceAfterTheEventBatchesWithTheVersionThePlatformAndAllReasons() async throws {
    let rig = try await SenderRig.make(replies: [.ack(), .ack(), .ack()], events: 120)
    await rig.transport.enqueueReports(.http(HttpResult(status: 204)))
    try await rig.queue.addDropped(reason: "queue_full", count: 3)
    try await rig.queue.addDropped(reason: "storage_error", count: 1)
    #expect(try await rig.sender.run() == RunResult(batches: 3, end: .queueEmpty))
    #expect(await rig.transport.calls == ["batch", "batch", "batch", "report"])
    let report = try #require(await rig.transport.reports.first)
    #expect(report.contractVersion == "1")
    let root = try object(report)
    #expect(Set(root.keys) == ["sdkVersion", "sdkPlatform", "discardedEvents"])
    #expect(root["sdkVersion"] as? String == SignalDockSDK.version)
    #expect(root["sdkPlatform"] as? String == "ios")
    #expect(try entries(report) == ["queue_full": 3, "storage_error": 1])
    // Nothing is left to report: the next cycle sends no second report.
    _ = try await rig.queue.addWithinLimits(sendEvent(500), limits: QueueLimits())
    await rig.transport.enqueue(.ack())
    _ = try await rig.sender.run()
    #expect(await rig.transport.reports.count == 1)
  }

  @Test func noReportIsSentWhenNoCounterIsSetOrWhenTheQueueIsEmpty() async throws {
    let empty = try await SenderRig.make(events: 0)
    try await empty.queue.addDropped(reason: "queue_full", count: 2)
    _ = try await empty.sender.run()  // an empty queue: no HTTP at all
    #expect(await empty.transport.calls.isEmpty)
    let none = try await SenderRig.make(replies: [.ack()], events: 1)
    _ = try await none.sender.run()
    #expect(await none.transport.calls == ["batch"])  // events, but nothing dropped
  }

  @Test func noReportIsSentWhenTheCycleAppliedNoValidAck() async throws {
    let rig = try await SenderRig.make(replies: [http(503)], events: 1)
    try await rig.queue.addDropped(reason: "queue_full", count: 2)
    guard case .waiting = try await rig.sender.run().end else { Issue.record("expected a wait"); return }
    #expect(await rig.transport.reports.isEmpty)
    #expect(try await counts(rig.queue) == ["queue_full": 2])
  }

  private static let fixtureRows = [
    DroppedCountRow(reason: "queue_full", count: 3), DroppedCountRow(reason: "storage_error", count: 1),
    DroppedCountRow(reason: "invalid_event", count: 2), DroppedCountRow(reason: "event_too_large", count: 4),
    DroppedCountRow(reason: "server_rejected", count: 1_000_000),
  ]

  @Test func theReportBodyIsByteLockedAndMatchesTheSharedFixtureInEverythingButTheVersion() throws {
    let body = String(decoding: try #require(try buildClientReport(Self.fixtureRows)).body, as: UTF8.self)
    #expect(
      body == #"{"discardedEvents":[{"count":3,"reason":"queue_full"},{"count":1,"reason":"storage_error"},"#
        + #"{"count":2,"reason":"invalid_event"},{"count":4,"reason":"event_too_large"},"#
        + #"{"count":1000000,"reason":"server_rejected"}],"sdkPlatform":"ios","sdkVersion":"0.1.0"}"#)
    // Drift check with fixtures/roundtrip (the server tests post the same file).
    let built = try #require(try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any])
    let fixture = try #require(
      try JSONSerialization.jsonObject(with: WireModelTests.fixture("roundtrip/client-report-request-ios.json"))
        as? [String: Any])
    #expect(Set(built.keys) == Set(fixture.keys))
    #expect(built["sdkPlatform"] as? String == "ios" && fixture["sdkPlatform"] as? String == "ios")
    func pairs(_ o: [String: Any]) -> [String] {
      (o["discardedEvents"] as? [[String: Any]] ?? []).map { "\($0["reason"] ?? "")=\($0["count"] ?? "")" }
    }
    #expect(pairs(built) == pairs(fixture))
  }

  @Test func aCounterAboveTheLimitIsCutAnAnUnknownReasonOrAZeroCountIsNotSent() throws {
    let report = try #require(
      try buildClientReport([
        DroppedCountRow(reason: "queue_full", count: Int.max), DroppedCountRow(reason: "made_up", count: 5),
        DroppedCountRow(reason: "storage_error", count: 0),
      ]))
    #expect(report.rows == [DroppedCountRow(reason: "queue_full", count: maxDroppedCount)])
    #expect(try buildClientReport([DroppedCountRow(reason: "made_up", count: 5)]) == nil)
    #expect(try buildClientReport([]) == nil)
  }

  @Test func everyReasonIsCounted() async throws {
    let rig = try await SenderRig.make(replies: [.ack()], events: 1)
    await rig.transport.enqueueReports(.http(HttpResult(status: 204)))
    for reason in DiscardReason.allCases { try await rig.queue.addDropped(reason: reason.rawValue, count: 2) }
    _ = try await rig.sender.run()
    let sent = try entries(try #require(await rig.transport.reports.first))
    #expect(sent == Dictionary(uniqueKeysWithValues: DiscardReason.allCases.map { ($0.rawValue, 2) }))
  }

  // MARK: Answers

  private func cycle(reportStatus status: Int?) async throws -> SenderRig {
    let rig = try await SenderRig.make(replies: [.ack()], events: 1)
    try await rig.queue.addDropped(reason: "queue_full", count: 4)
    await rig.transport.enqueueReports(status.map { .http(HttpResult(status: $0)) } ?? .failed(.network))
    #expect(try await rig.sender.run().end == .queueEmpty)
    #expect(await rig.transport.reports.count == 1, "one report")
    return rig
  }

  /// The report answer never touches the retry state of the events.
  private func expectRetryStateUntouched(_ rig: SenderRig, _ label: String) async throws {
    let state = try await rig.retry
    #expect(state.failureCount == 0 && state.nextAttemptAtMillis == nil && !state.hold, "\(label)")
    #expect(state.lastErrorKind == nil && state.lastErrorHttpStatus == nil, "\(label)")
  }

  @Test(arguments: [200, 204]) func a2xxRemovesTheSentCounts(status: Int) async throws {
    let rig = try await cycle(reportStatus: status)
    #expect(try await counts(rig.queue).isEmpty)
  }

  @Test(arguments: [404, 400, 401, 403, 413, 415, 302, 501, 505])
  func a404FromAnOldServerAndOtherPermanentAnswersDiscardTheReportWithNoRetry(status: Int) async throws {
    let rig = try await cycle(reportStatus: status)
    #expect(try await counts(rig.queue).isEmpty, "\(status) discards the counts")
    // No retry: with nothing new dropped, the next cycle sends no report.
    _ = try await rig.queue.addWithinLimits(sendEvent(10), limits: QueueLimits())
    await rig.transport.enqueue(.ack())
    _ = try await rig.sender.run()
    #expect(await rig.transport.reports.count == 1, "\(status): no retry")
    try await expectRetryStateUntouched(rig, "\(status)")
  }

  @Test(arguments: [nil, 408, 429, 500, 502, 503] as [Int?])
  func aTransientFailureKeepsTheCountersAndTheNextCycleSendsThemWithTheNewOnes(status: Int?) async throws {
    let rig = try await cycle(reportStatus: status)
    #expect(try await counts(rig.queue) == ["queue_full": 4], "\(String(describing: status)) keeps the counts")
    try await expectRetryStateUntouched(rig, "\(String(describing: status))")
    // Next cycle: the server is back. The counters add up and go out in one report.
    try await rig.queue.addDropped(reason: "queue_full", count: 1)
    _ = try await rig.queue.addWithinLimits(sendEvent(10), limits: QueueLimits())
    await rig.transport.enqueue(.ack())
    await rig.transport.enqueueReports(.http(HttpResult(status: 204)))
    _ = try await rig.sender.run()
    let reports = await rig.transport.reports
    #expect(reports.count == 2)
    #expect(try entries(reports[1]) == ["queue_full": 5])
    #expect(try await counts(rig.queue).isEmpty)
  }

  @Test func dropsCountedWhileTheReportIsInFlightStay() async throws {
    let rig = try await SenderRig.make(replies: [.ack()], events: 1)
    try await rig.queue.addDropped(reason: "queue_full", count: 2)
    let queue = rig.queue
    await rig.transport.onReport {
      try? await queue.addDropped(reason: "queue_full", count: 3)  // a new drop during the HTTP call
      try? await queue.addDropped(reason: "invalid_event", count: 1)
    }
    await rig.transport.enqueueReports(.http(HttpResult(status: 204)))
    _ = try await rig.sender.run()
    #expect(try await counts(rig.queue) == ["queue_full": 3, "invalid_event": 1])
  }

  @Test func aStorageFailureOfTheReportDoesNotHideTheBatchResult() async throws {
    let rig = try await SenderRig.make(replies: [.ack()], events: 1)
    try await rig.queue.addDropped(reason: "queue_full", count: 1)
    let queue = rig.queue
    await rig.transport.onReport { await queue.close() }  // the subtract after the 204 now fails
    await rig.transport.enqueueReports(.http(HttpResult(status: 204)))
    let result = try await rig.sender.run()
    #expect(result == RunResult(batches: 1, end: .queueEmpty))
    #expect(rig.logger.messages.contains(.clientReportFailed))
  }
}
