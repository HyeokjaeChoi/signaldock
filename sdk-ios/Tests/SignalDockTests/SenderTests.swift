import Foundation
import Testing

@testable import SignalDock

private let t0: Int64 = 1_760_000_000_123
private let second: Int64 = 1000
private let minute: Int64 = 60_000

/// One row of the ADR-0006 final-result table (decision 5). `hold` rows stop normal sending; the others back off.
private struct Row: Sendable, CustomTestStringConvertible {
  let name: String
  let reply: TransportResult
  let kind: DeliveryErrorKind
  let status: Int?
  let hold: Bool

  var testDescription: String { name }

  static func http(_ status: Int, _ kind: DeliveryErrorKind, hold: Bool = false) -> Row {
    Row(name: "HTTP \(status)", reply: .http(HttpResult(status: status)), kind: kind, status: status, hold: hold)
  }

  static let all: [Row] =
    [
      Row(name: "network error", reply: .failed(.network), kind: .network, status: nil, hold: false),
      Row(name: "call timeout", reply: .failed(.timeout), kind: .timeout, status: nil, hold: false),
      Row(
        name: "200 with an invalid ACK", reply: .http(HttpResult(status: 200, body: Data("{}".utf8))),
        kind: .invalidAck, status: 200, hold: false),
      Row(
        name: "200 without a body", reply: .http(HttpResult(status: 200)), kind: .invalidAck, status: 200, hold: false),
    ]
    + [100, 101, 199, 201, 202, 204, 206, 299].map { http($0, .invalidAck) }
    + [408].map { http($0, .timeout) }
    + [429].map { http($0, .rateLimited) }
    + [500, 502, 503, 504, 507, 511, 599].map { http($0, .serverError) }
    + [400].map { http($0, .contract, hold: true) }
    + [300, 301, 302, 303, 307, 308, 399, 401, 403, 404, 405, 410, 415, 418, 422, 499, 501, 505].map {
      http($0, .authOrConfiguration, hold: true)
    }
}

/// The sender against a deterministic transport and an injected clock (ADR-0006, ADR-0047 D8). No test waits for time.
@Suite struct SenderTests {
  // MARK: Success and ACK application

  @Test func aValidAckDeletesTheBatchAndRecordsTheAck() async throws {
    let rig = try await SenderRig.make(replies: [.ack()])
    let result = try await rig.sender.run()
    #expect(result == RunResult(batches: 1, end: .queueEmpty))
    #expect(try await rig.queuedIds.isEmpty)
    let state = try await rig.retry
    #expect(state.lastAckAtMillis == t0)
    #expect(state.lastAckAccepted == 3 && state.lastAckDuplicate == 0 && state.lastAckRejected == 0)
    #expect(state.failureCount == 0 && state.nextAttemptAtMillis == nil && !state.hold)
    #expect(rig.logger.messages == [.deliveryAttemptStarted, .deliveryAttemptFinished])
  }

  @Test func aPartialAckAppliesPerItemAndRemovesDuplicatesAndPermanentRejections() async throws {
    let body = ackJson(ids: ["event-1", "event-2", "event-3"], statuses: ["accepted", "duplicate", "rejected"])
    let rig = try await SenderRig.make(replies: [.body(body)])
    _ = try await rig.sender.run()
    #expect(try await rig.queuedIds.isEmpty)
    let state = try await rig.retry
    #expect(state.lastAckAccepted == 1 && state.lastAckDuplicate == 1 && state.lastAckRejected == 1)
    let rejections = try await rig.queue.recentRejections()
    #expect(rejections.map(\.eventId) == ["event-3"])
    #expect(rejections.map(\.code) == ["INVALID_FIELD"])
    #expect(try await rig.queue.droppedCounts() == [DroppedCountRow(reason: "server_rejected", count: 1)])
  }

  @Test func aValidAckResetsAFailureCountAndTheWait() async throws {
    let rig = try await SenderRig.make(replies: [.result(.failed(.network)), .ack()], jitter: [5000])
    _ = try await rig.sender.run()
    #expect(try await rig.retry.failureCount == 1)
    rig.clock.advance(by: 5000)
    _ = try await rig.sender.run()
    let state = try await rig.retry
    #expect(state.failureCount == 0 && state.nextAttemptAtMillis == nil && state.waitMillis == nil)
    #expect(state.lastErrorKind == "NETWORK")  // history stays (ADR-0006 decision 4)
  }

  @Test func theRejectionCodeIsCutToSixtyFourCharactersAndDefaultsToUnspecified() async throws {
    let long = String(repeating: "X", count: 100)
    let body =
      #"{"results":[{"index":0,"status":"rejected","code":"\#(long)"},"#
      + #"{"index":1,"status":"rejected"},{"index":2,"status":"rejected","code":"  "}]}"#
    let rig = try await SenderRig.make(replies: [.body(body)])
    _ = try await rig.sender.run()
    let codes = try await rig.queue.recentRejections().sorted { $0.id < $1.id }.map(\.code)
    #expect(codes == [String(repeating: "X", count: 64), "UNSPECIFIED", "UNSPECIFIED"])
  }

  @Test func anEmptyQueueSendsNoHttpRequest() async throws {
    let rig = try await SenderRig.make(events: 0)
    #expect(try await rig.sender.run() == RunResult(batches: 0, end: .queueEmpty))
    #expect(await rig.transport.requests.isEmpty)
  }

  @Test func runSendsBatchAfterBatchUntilTheQueueIsEmpty() async throws {
    let rig = try await SenderRig.make(replies: [.ack(), .ack(), .ack()], events: 120)
    #expect(try await rig.sender.run() == RunResult(batches: 3, end: .queueEmpty))  // 50 + 50 + 20
    #expect(await rig.transport.requests.map { FakeTransport.eventIds($0).count } == [50, 50, 20])
  }

  // MARK: The ADR-0006 final-result table

  @Test(arguments: Row.all)
  fileprivate func everyRowOfTheFinalResultTable(row: Row) async throws {
    let rig = try await SenderRig.make(replies: [.result(row.reply)], jitter: [4000])
    let ids = try await rig.queuedIds
    let result = try await rig.sender.run()

    let state = try await rig.retry
    #expect(try await rig.queuedIds == ids, "the queue is kept")  // nothing is applied by any row but a valid ACK
    #expect(await rig.transport.requests.count == 1)
    #expect(state.lastErrorKind == row.kind.rawValue)
    #expect(state.lastErrorHttpStatus == row.status)
    #expect(state.lastErrorAtMillis == t0)
    #expect(state.lastAckAtMillis == nil, "an invalid result applies no ACK")
    if row.hold {
      #expect(state.hold)
      #expect(state.failureCount == 0, "a hold is not a temporary failure")
      #expect(state.nextAttemptAtMillis == t0 + 30 * minute)
      #expect(state.waitMillis == 30 * minute)
      #expect(result == RunResult(batches: 0, end: .waiting(untilMillis: t0 + 30 * minute)))
      #expect(rig.logger.warnings == [.deliveryPaused])
    } else {
      #expect(!state.hold)
      #expect(state.failureCount == 1)
      #expect(state.nextAttemptAtMillis == t0 + 4000)  // B = 10 s, the draw is 4 s
      #expect(result == RunResult(batches: 0, end: .waiting(untilMillis: t0 + 4000)))
      #expect(rig.logger.warnings.isEmpty)
    }
  }

  @Test func theRow413ForSeveralEventsSplitsAndIsNotATemporaryFailure() async throws {
    let rig = try await SenderRig.make(replies: [http(413), .ack(), .ack()], events: 7)
    _ = try await rig.sender.run()
    #expect(await rig.transport.requests.map { FakeTransport.eventIds($0).count } == [7, 3, 4])  // half, then 3 doubled
    let state = try await rig.retry
    #expect(state.failureCount == 0 && !state.hold && state.lastErrorKind == "PAYLOAD_TOO_LARGE")
    #expect(state.lastErrorHttpStatus == 413)
    #expect(rig.logger.warnings.isEmpty)
  }

  // MARK: Retry-After applies to every row

  @Test(arguments: Row.all)
  fileprivate func aValidRetryAfterAppliesToEveryRow(row: Row) async throws {
    var withHeader = row.reply
    if case .http(var h) = row.reply {
      h.retryAfter = "3600"
      withHeader = .http(h)
    }
    let rig = try await SenderRig.make(replies: [.result(withHeader)], jitter: [4000])
    _ = try await rig.sender.run()
    let state = try await rig.retry
    if case .failed = row.reply {
      #expect(state.nextAttemptAtMillis == t0 + 4000, "no response, no header")
    } else {
      #expect(state.nextAttemptAtMillis == t0 + 3600 * second)  // longer than the backoff and than the 30 minute hold
      #expect(state.waitMillis == 3600 * second)
    }
  }

  @Test func retryAfterOnA413AndOnASingleEvent413() async throws {
    let split = try await SenderRig.make(replies: [http(413, retryAfter: "60")], events: 10)
    #expect(try await split.sender.run() == RunResult(batches: 0, end: .waiting(untilMillis: t0 + 60 * second)))
    let one = try await SenderRig.make(replies: [http(413, retryAfter: "7200")], events: 1)
    #expect(try await one.sender.run() == RunResult(batches: 0, end: .waiting(untilMillis: t0 + 120 * minute)))
  }

  @Test func aShorterRetryAfterDoesNotShortenTheBackoffAndALongerOneWins() async throws {
    let short = try await SenderRig.make(replies: [http(503, retryAfter: "1")], jitter: [8000])
    _ = try await short.sender.run()
    #expect(try await short.retry.nextAttemptAtMillis == t0 + 8000)
    let long = try await SenderRig.make(replies: [http(503, retryAfter: "120")], jitter: [8000])
    _ = try await long.sender.run()
    #expect(try await long.retry.nextAttemptAtMillis == t0 + 120 * second)
  }

  @Test func retryAfterInTheHttpDateFormAndInvalidValues() async throws {
    let date = "Thu, 09 Oct 2025 08:55:20 GMT"  // t0 is 08:53:20.123: 119.877 seconds later
    let rig = try await SenderRig.make(replies: [http(429, retryAfter: date)], jitter: [1000])
    _ = try await rig.sender.run()
    #expect(try await rig.retry.nextAttemptAtMillis == parseHttpDateMillis(date))
    for bad in ["0", "-1", "soon", "Thu, 09 Oct 2025 08:50:00 GMT", ""] {
      let invalid = try await SenderRig.make(replies: [http(429, retryAfter: bad)], jitter: [1000])
      _ = try await invalid.sender.run()
      #expect(try await invalid.retry.nextAttemptAtMillis == t0 + 1000, "\(bad)")
    }
  }

  @Test func aHugeRetryAfterIsNeverCappedAndCannotOverflowTheNextAttemptTime() async throws {
    let rig = try await SenderRig.make(replies: [http(503, retryAfter: "99999999999999999999")])
    _ = try await rig.sender.run()
    let next = try #require(try await rig.retry.nextAttemptAtMillis)
    #expect(next > t0 + 1_000_000_000_000_000)
    #expect(next >= t0)
  }

  // MARK: Backoff and persistence

  @Test func backoffDoublesToAFiveMinuteCapAndTheJitterBoundIsB() async throws {
    let rig = try await SenderRig.make(replies: Array(repeating: .result(.failed(.network)), count: 8), jitter: [])
    var bounds: [Int64] = []
    for _ in 1...8 {
      _ = try await rig.sender.run()
      rig.clock.advance(by: 10 * minute)  // the wait is over
      bounds = rig.jitter.bounds
    }
    #expect(bounds.map { $0 / second } == [10, 20, 40, 80, 160, 300, 300, 300])
    #expect(try await rig.retry.failureCount == 8)
  }

  @Test func aJitterDrawAboveTheBoundIsLimitedToItAndOneBelowZeroToZero() async throws {
    let rig = try await SenderRig.make(
      replies: [.result(.failed(.network)), .result(.failed(.network))], jitter: [999_999_999, -5])
    _ = try await rig.sender.run()
    #expect(try await rig.retry.waitMillis == 10 * second)
    rig.clock.advance(by: 10 * second)
    _ = try await rig.sender.run()
    #expect(try await rig.retry.waitMillis == 0)  // a zero draw ends the run; the next trigger starts the next one
  }

  @Test func aZeroDrawDoesNotStartATightLoop() async throws {
    let rig = try await SenderRig.make(replies: [.result(.failed(.network))], jitter: [0])
    let result = try await rig.sender.run()
    #expect(result.end == .waiting(untilMillis: t0))
    #expect(await rig.transport.requests.count == 1)  // one request in this run, although the wait is over at once
  }

  @Test func theSenderSendsNothingBeforeTheStoredTimeAndOneBatchAfterIt() async throws {
    let rig = try await SenderRig.make(replies: [.result(.failed(.network)), .ack()], jitter: [30 * second])
    _ = try await rig.sender.run()
    rig.clock.advance(by: 9 * second)  // B is 10 s, the draw is clamped to it
    #expect(try await rig.sender.run() == RunResult(batches: 0, end: .waiting(untilMillis: t0 + 10 * second)))
    #expect(await rig.transport.requests.count == 1)
    rig.clock.advance(by: 1 * second)
    #expect(try await rig.sender.run().end == .queueEmpty)
    #expect(await rig.transport.requests.count == 2)
  }

  @Test func theNextAttemptTimeSurvivesARelaunch() async throws {
    let rig = try await SenderRig.make(replies: [http(503)], jitter: [6000])
    _ = try await rig.sender.run()
    let after = try await rig.relaunched(transport: FakeTransport([.ack()]))
    #expect(try await after.sender.run() == RunResult(batches: 0, end: .waiting(untilMillis: t0 + 6000)))
    #expect(await after.transport.requests.isEmpty)
    after.clock.advance(by: 6000)
    #expect(try await after.sender.run().end == .queueEmpty)
    #expect(try await after.queuedIds.isEmpty)
  }

  // MARK: Wall clock changes (ADR-0025)

  @Test func aClockMovedBackByADayResumesWithinTheBackoffNotAfterADay() async throws {
    let rig = try await SenderRig.make(replies: [http(503), .ack()], jitter: [8000])
    _ = try await rig.sender.run()
    rig.clock.set(t0 - 86_400_000)  // the user sets the clock back by a day
    let waiting = try await rig.sender.run()
    #expect(waiting.end == .waiting(untilMillis: t0 - 86_400_000 + 8000))  // cut to now + the stored wait
    #expect(try await rig.retry.nextAttemptAtMillis == t0 - 86_400_000 + 8000)  // and saved
    rig.clock.advance(by: 8000)
    #expect(try await rig.sender.run().end == .queueEmpty)
  }

  @Test func aClockMovedBackKeepsTheHoldRecheckAtThirtyMinutesAndALongerRetryAfterInFull() async throws {
    let rig = try await SenderRig.make(replies: [http(401), http(401, retryAfter: "7200")])
    _ = try await rig.sender.run()
    rig.clock.set(t0 - 86_400_000)
    #expect(try await rig.sender.run().end == .waiting(untilMillis: t0 - 86_400_000 + 30 * minute))
    rig.clock.set(t0)
    rig.clock.advance(by: 30 * minute)
    _ = try await rig.sender.run()  // the second 401, with Retry-After 2 h
    rig.clock.set(t0 - 86_400_000)
    #expect(try await rig.sender.run().end == .waiting(untilMillis: t0 - 86_400_000 + 120 * minute))
  }

  @Test func aClockMovedForwardByADaySendsOnceAndThenWaitsAgain() async throws {
    let rig = try await SenderRig.make(replies: [http(503), http(503)], jitter: [8000, 9000])
    _ = try await rig.sender.run()
    rig.clock.advance(by: 86_400_000)
    let result = try await rig.sender.run()
    #expect(await rig.transport.requests.count == 2)
    #expect(result.end == .waiting(untilMillis: t0 + 86_400_000 + 9000))  // no burst
    #expect(try await rig.sender.run().batches == 0)
    #expect(await rig.transport.requests.count == 2)
  }

  @Test func aWaitWithNoStoredLengthIsNotClamped() async throws {
    let rig = try await SenderRig.make(replies: [])
    try await rig.queue.saveRetryState(RetryStateRow(nextAttemptAtMillis: t0 + 86_400_000, waitMillis: nil))
    #expect(try await rig.sender.run().end == .waiting(untilMillis: t0 + 86_400_000))
  }

  // MARK: Hold

  @Test func a401StopsNormalSendingRechecksOneBatchEveryThirtyMinutesAndResumesOnAValidAck() async throws {
    let rig = try await SenderRig.make(replies: [http(401), http(401), .ack(), .ack(), .ack()], events: 120)
    _ = try await rig.sender.run()
    #expect(try await rig.retry.hold)
    rig.clock.advance(by: 29 * minute)
    #expect(try await rig.sender.run().batches == 0)
    #expect(await rig.transport.requests.count == 1)  // normal sending is stopped
    rig.clock.advance(by: 1 * minute)
    _ = try await rig.sender.run()  // the recheck: one batch, still 401
    #expect(await rig.transport.requests.count == 2)
    #expect(try await rig.retry.hold)
    #expect(rig.logger.warnings == [.deliveryPaused], "one WARN on entering the hold, not on each recheck")
    rig.clock.advance(by: 30 * minute)
    let result = try await rig.sender.run()  // the recheck succeeds; the hold ends and sending continues at once
    #expect(result.batches == 3)
    #expect(try await rig.queuedIds.isEmpty)
    #expect(try await rig.retry.hold == false)
    #expect(rig.logger.warnings == [.deliveryPaused, .deliveryResumed])
  }

  @Test func aLongerRetryAfterExtendsTheHold() async throws {
    let rig = try await SenderRig.make(replies: [http(403, retryAfter: "7200")])
    _ = try await rig.sender.run()
    #expect(try await rig.retry.nextAttemptAtMillis == t0 + 120 * minute)
  }

  @Test func aHoldWithAnEmptyQueueSendsNoHttpAndAHoldSurvivesARelaunch() async throws {
    let rig = try await SenderRig.make(replies: [http(401)])
    _ = try await rig.sender.run()
    let after = try await rig.relaunched(transport: FakeTransport([.ack()]))
    #expect(try await after.retry.hold)
    #expect(try await after.sender.run().end == .waiting(untilMillis: t0 + 30 * minute))
    #expect(await after.transport.requests.isEmpty)
    let empty = try await SenderRig.make(events: 0)
    try await empty.queue.saveRetryState(RetryStateRow(hold: true))
    #expect(try await empty.sender.run() == RunResult(batches: 0, end: .queueEmpty))
    #expect(await empty.transport.requests.isEmpty)
  }

  @Test func aTemporaryFailureDuringAHoldStillWaitsAtLeastThirtyMinutes() async throws {
    let rig = try await SenderRig.make(replies: [http(401), http(503)], jitter: [1000])
    _ = try await rig.sender.run()
    rig.clock.advance(by: 30 * minute)
    let result = try await rig.sender.run()
    #expect(result.end == .waiting(untilMillis: t0 + 60 * minute))
    let state = try await rig.retry
    #expect(state.hold && state.failureCount == 1 && state.lastErrorKind == "SERVER_ERROR")
  }

  @Test func a413ForOneEventHoldsWithBatchCapOneAndTheRecheckSendsThatSingleEvent() async throws {
    let rig = try await SenderRig.make(replies: [http(413), .ack(), .ack()], events: 1)
    _ = try await rig.sender.run()
    var state = try await rig.retry
    #expect(state.hold && state.maxBatchEvents == 1 && state.lastErrorKind == "PAYLOAD_TOO_LARGE")
    #expect(state.failureCount == 0)
    rig.clock.advance(by: 30 * minute)
    _ = try await rig.sender.run()
    state = try await rig.retry
    #expect(await rig.transport.requests.count == 2)
    #expect(!state.hold && state.maxBatchEvents == 2)  // the cap 1 doubles after the valid ACK
  }

  // MARK: 413 split

  @Test func a413ForAMultiEventBatchSplitsIntoSmallerBatchesWithTheSameIds() async throws {
    let rig = try await SenderRig.make(replies: [http(413), http(413), .ack(), .ack(), .ack()], events: 50)
    let result = try await rig.sender.run()
    let sizes = await rig.transport.requests.map { FakeTransport.eventIds($0).count }
    #expect(sizes == [50, 25, 12, 24, 14])  // each 413 halves the batch; each valid ACK doubles the cap
    #expect(result.end == .queueEmpty)
    #expect(try await rig.queuedIds.isEmpty)
    #expect(try await rig.retry.failureCount == 0, "a split is not a temporary failure")
    let first = FakeTransport.eventIds(await rig.transport.requests[0])
    let second = FakeTransport.eventIds(await rig.transport.requests[1])
    #expect(Array(first.prefix(25)) == second, "the smaller batch is the head of the larger one")
  }

  @Test func a413DoesNotRetryBeforeRetryAfterAndTheSmallerCapSurvivesARelaunch() async throws {
    let rig = try await SenderRig.make(replies: [http(413, retryAfter: "300")], events: 10)
    #expect(try await rig.sender.run().end == .waiting(untilMillis: t0 + 300 * second))
    #expect(try await rig.retry.maxBatchEvents == 5)
    let after = try await rig.relaunched(transport: FakeTransport([.ack()]))
    #expect(try await after.sender.run().end == .waiting(untilMillis: t0 + 300 * second))
    after.clock.advance(by: 300 * second)
    _ = try await after.sender.run()
    #expect(await after.transport.requests.map { FakeTransport.eventIds($0).count } == [5, 5])  // the cap 5, then 10
  }

  @Test func theCapDoublesAfterEachValidAckAndIsClearedWhenItReachesFifty() async throws {
    let rig = try await SenderRig.make(
      replies: [http(413), http(413), http(413), .ack(), .ack(), .ack(), .ack()], events: 40)
    _ = try await rig.sender.run()
    #expect(await rig.transport.requests.map { FakeTransport.eventIds($0).count } == [40, 20, 10, 5, 10, 20, 5])
    #expect(try await rig.retry.maxBatchEvents == nil, "the last ACK doubled 40 to 80: cleared")
    let full = try await SenderRig.make(replies: [http(413), .ack(), .ack(), .ack()], events: 100)
    _ = try await full.sender.run()
    #expect(await full.transport.requests.map { FakeTransport.eventIds($0).count } == [50, 25, 50, 25])
    #expect(try await full.retry.maxBatchEvents == nil, "25 doubled to 50: cleared")
  }

  // MARK: Batches

  @Test func aBatchHoldsAtMostFiftyEventsOldestFirst() async throws {
    let rig = try await SenderRig.make(replies: [.ack(), .ack()], events: 60)
    _ = try await rig.sender.run()
    let batches = await rig.transport.requests.map(FakeTransport.eventIds)
    #expect(batches[0] == (1...50).map { "event-\($0)" })
    #expect(batches[1] == (51...60).map { "event-\($0)" })
  }

  @Test func batchesAreGroupedByStoredContractVersionAndInstallationId() async throws {
    let rig = try await SenderRig.make(replies: [.ack(), .ack(), .ack(), .ack()], events: 0)
    let rows = [
      sendEvent(1, installationId: "i1", contractVersion: "1"),
      sendEvent(2, installationId: "i2", contractVersion: "1"),
      sendEvent(3, installationId: "i1", contractVersion: "2"),
      sendEvent(4, installationId: "i1", contractVersion: "1"),
    ]
    for row in rows { _ = try await rig.queue.addWithinLimits(row, limits: QueueLimits()) }
    _ = try await rig.sender.run()
    let requests = await rig.transport.requests
    // The oldest event decides the group; the group of the oldest remaining event goes next, so none starves.
    #expect(requests.map { FakeTransport.eventIds($0) } == [["event-1", "event-4"], ["event-2"], ["event-3"]])
    #expect(requests.map(\.contractVersion) == ["1", "1", "2"])
    func installationId(_ r: BatchHttpRequest) throws -> String {
      try #require(try JSONSerialization.jsonObject(with: r.body) as? [String: Any])["installationId"] as? String ?? ""
    }
    #expect(try requests.map(installationId) == ["i1", "i2", "i1"])
  }

  @Test func theBodyIsBuiltFromTheStoredBytesAndEqualsTheEncodedBatchRequest() async throws {
    let h = try await Harness.make()
    for i in 0..<5 {
      _ = try await h.sdk.track("e\(i)") {
        $0.property("n", i); $0.property("s", "x/y\u{1f389}")
      }
    }
    let transport = FakeTransport()
    let sender = Sender(
      queue: h.sdk.queue, transport: transport, clock: TestClock(), jitter: ScriptedJitter(),
      logger: RecordingLogger(), notice: DeprecationNotice())
    _ = try await sender.run()
    let request = try #require(await transport.requests.first)
    let stored = try await h.sdk.queue.oldest(limit: 100)
    #expect(stored.count == 5, "the fake did not ACK: the queue is kept")
    let decoded = try JSONDecoder().decode(BatchIngestRequest.self, from: request.body)
    #expect(try transportEncoder().encode(decoded) == request.body, "equals the generated serializer's bytes")
    #expect(
      request.body.count == (try batchRequestBytes(installationId: decoded.installationId, events: decoded.events)))
    let text = String(decoding: request.body, as: UTF8.self)
    #expect(text.contains(stored.map(\.payloadJson).joined(separator: ",")), "the stored bytes, in order")
    #expect(request.contractVersion == "1")
  }

  @Test func theBodyStaysWithin256KiB() async throws {
    let rig = try await SenderRig.make(replies: [.ack(), .ack(), .ack()], events: 0)
    for n in 1...20 {
      let row = sendEvent(n, payloadBytes: 16 * 1024)
      _ = try await rig.queue.addWithinLimits(row, limits: QueueLimits())
    }
    _ = try await rig.sender.run()
    let requests = await rig.transport.requests
    #expect(requests.allSatisfy { $0.body.count <= 256 * 1024 })
    #expect(requests.map { FakeTransport.eventIds($0).count } == [15, 5])  // 16 KiB events: 15 fit, a 16th does not
  }

  @Test func theBatchSizeBoundaryExactlyTheLimitFitsAndOneByteLessDropsTheLastEvent() throws {
    let rows = (1...3).map { n -> EventRow in
      var row = makeEvent(n)
      row.payloadJson = "{\"n\":\(n)}"  // 7 bytes each
      return row
    }
    let overhead = try BatchBody.make(installationId: "install-1", payloads: []).count
    let full = overhead + 7 * 3 + 2  // three payloads and two commas
    #expect(try buildBatch(rows: rows, maxBytes: full).events.count == 3)
    #expect(try buildBatch(rows: rows, maxBytes: full - 1).events.count == 2)
    #expect(try buildBatch(rows: rows, maxBytes: 1).events.count == 1, "at least one event is always taken")
    #expect(try buildBatch(rows: rows, maxEvents: 2).events.count == 2)
  }

  // MARK: Resend

  @Test func aResendKeepsTheExactBytesAndTheEventIds() async throws {
    let rig = try await SenderRig.make(replies: [http(503), http(500), .ack()], jitter: [5000, 5000])
    _ = try await rig.sender.run()
    rig.clock.advance(by: 5000)
    _ = try await rig.sender.run()
    rig.clock.advance(by: 20_000)
    _ = try await rig.sender.run()
    let requests = await rig.transport.requests
    #expect(requests.count == 3)
    #expect(requests[0].body == requests[1].body && requests[1].body == requests[2].body)
    #expect(FakeTransport.eventIds(requests[2]) == ["event-1", "event-2", "event-3"])
  }

  // MARK: Cancellation and relaunch

  @Test func aRunInACancelledTaskSendsNothing() async throws {
    let rig = try await SenderRig.make(replies: [.ack()])
    let sender = rig.sender
    let outcome = await runCancelled { try await sender.run() }
    #expect(outcome.isFailure)
    #expect(await rig.transport.requests.isEmpty)
    #expect(try await rig.queuedIds.count == 3)
  }

  @Test func cancellingARunDuringHttpLeavesTheQueueAndTheRetryStateUntouched() async throws {
    let entered = Gate()
    let rig = try await SenderRig.make(replies: [.hold(entered: entered)])
    let sender = rig.sender
    let task = Task { try await sender.run() }
    await entered.wait()
    task.cancel()
    await #expect(throws: CancellationError.self) { try await task.value }
    #expect(try await rig.queuedIds.count == 3)
    #expect(try await rig.queue.retryState() == nil, "no failure is counted")
  }

  @Test func theExpirationOfABackgroundTaskDuringARequestLosesNothingAndSendsNoDuplicateAfterARelaunch() async throws {
    let server = FakeServer()
    let entered = Gate()
    // The first request reaches the server, which stores the events, but the task is cancelled before the ACK comes back.
    let rig = try await SenderRig.make(replies: [.serverHold(server, entered: entered)])
    let ids = try await rig.queuedIds
    let sender = rig.sender
    let first = Task { try await sender.run() }
    await entered.wait()
    first.cancel()
    await #expect(throws: CancellationError.self) { try await first.value }
    #expect(try await rig.queuedIds == ids, "nothing is lost")
    let firstBody = try #require(await rig.transport.requests.first)

    let after = try await rig.relaunched(transport: FakeTransport([.server(server)]))
    #expect(try await after.sender.run().end == .queueEmpty)
    let resend = try #require(await after.transport.requests.first)
    #expect(resend.body == firstBody.body, "the same bytes and event IDs")
    #expect(await server.stored == ids, "the server stored every event once")
    #expect(try await after.retry.lastAckDuplicate == 3, "and answered duplicate to the resend")
    #expect(try await after.queuedIds.isEmpty)
  }

  // MARK: One request in flight

  @Test func twoRunsNeverHaveTwoRequestsInFlight() async throws {
    let entered = Gate()
    let release = Gate()
    let waiting = Gate()
    let rig = try await SenderRig.make(
      replies: [.gated(entered: entered, release: release), .ack()], events: 3,
      onLockWait: { Task { await waiting.open() } })
    let sender = rig.sender
    let a = Task { try await sender.run() }
    await entered.wait()
    let b = Task { try await sender.run() }
    await waiting.wait()  // b is registered for the lock; it sends nothing
    #expect(await rig.transport.requests.count == 1)
    await release.open()
    _ = try await a.value
    _ = try await b.value
    #expect(await rig.transport.requests.count == 1, "b found an empty queue after a's ACK: no resend")
    #expect(await rig.transport.maxInFlight == 1)
    #expect(try await rig.queuedIds.isEmpty)
  }

  // MARK: The queue is cleared while a batch is selected

  @Test func aClearBetweenTheHeadReadAndTheGroupReadSendsNothingAndDoesNotCrash() async throws {
    let holder = QueueHolder()
    let rig = try await SenderRig.make(
      replies: [.ack()], betweenReads: { _ = try? await holder.queue?.deleteAll() })
    await holder.set(rig.queue)
    let result = try await rig.sender.run()
    #expect(result == RunResult(batches: 1, end: .queueEmpty))
    #expect(await rig.transport.requests.isEmpty)
    #expect(throws: EmptyBatch.self) { try buildBatch(rows: []) }
  }

  // MARK: Storage failure

  @Test func aStorageFailureEndsTheRunWithAStorageErrorAndSendsNothing() async throws {
    let rig = try await SenderRig.make(replies: [.ack()])
    await rig.queue.close()
    await #expect(throws: StorageError.self) { try await rig.sender.run() }
    #expect(await rig.transport.requests.isEmpty)
  }
}

/// Gives a hook the queue of a rig that is built after the hook.
actor QueueHolder {
  private(set) var queue: QueueStore?
  func set(_ queue: QueueStore) { self.queue = queue }
}
