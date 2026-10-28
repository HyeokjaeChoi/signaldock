import Foundation
import Testing

@testable import SignalDock

/// The contract deprecation notice (ADR-0030): the sender reads `Deprecation` and `Sunset` from every response, keeps
/// the latest valid value of each field, writes one WARN per process, and never changes delivery.
@Suite struct DeprecationTests {
  static let sunset = "Wed, 11 Nov 2026 23:59:59 GMT"
  static let sunsetMillis: Int64 = 1_794_441_599_000
  static let deprecation = "@1788220800"

  private func warnings(_ rig: SenderRig) -> Int { rig.logger.messages.filter { $0 == .contractDeprecated }.count }

  @Test func theSunsetFixtureIsTheDateItSays() {
    #expect(parseImfFixdateMillis(Self.sunset) == Self.sunsetMillis)
  }

  @Test func validHeadersGiveOneWarnAcrossManyRequestsAndShowInTheNotice() async throws {
    let rig = try await SenderRig.make(
      replies: [
        http(503, deprecation: Self.deprecation, sunset: Self.sunset),
        http(503, deprecation: Self.deprecation, sunset: Self.sunset),
      ])
    _ = try await rig.sender.run()
    rig.clock.advance(by: 600_000)
    _ = try await rig.sender.run()
    #expect(
      await rig.notice.current()
        == ContractDeprecation(deprecatedAtEpochMillis: 1_788_220_800_000, sunsetAtEpochMillis: Self.sunsetMillis))
    #expect(warnings(rig) == 1)
    #expect(rig.logger.warnings.contains(.contractDeprecated))
  }

  @Test func absentHeadersLeaveNothing() async throws {
    let rig = try await SenderRig.make(replies: [.ack()])
    _ = try await rig.sender.run()
    #expect(await rig.notice.current() == nil)
    #expect(warnings(rig) == 0)
  }

  @Test func malformedHeadersAreIgnoredAndDeliveryIsUnchanged() async throws {
    let rig = try await SenderRig.make(
      replies: [
        .result(
          .http(
            HttpResult(status: 200, body: Data(ackJson(ids: ["event-1"]).utf8), deprecation: "true", sunset: "soon")))
      ],
      events: 1)
    _ = try await rig.sender.run()
    #expect(await rig.notice.current() == nil)
    #expect(warnings(rig) == 0)
    #expect(try await rig.queuedIds.isEmpty)
  }

  @Test func oneValidHeaderIsEnoughAndTheOtherFieldStaysNil() async throws {
    let onlySunset = try await SenderRig.make(replies: [http(503, deprecation: "garbage", sunset: Self.sunset)])
    _ = try await onlySunset.sender.run()
    #expect(
      await onlySunset.notice.current()
        == ContractDeprecation(deprecatedAtEpochMillis: nil, sunsetAtEpochMillis: Self.sunsetMillis))
    let onlyDeprecation = try await SenderRig.make(replies: [http(503, deprecation: Self.deprecation)])
    _ = try await onlyDeprecation.sender.run()
    #expect(
      await onlyDeprecation.notice.current()
        == ContractDeprecation(deprecatedAtEpochMillis: 1_788_220_800_000, sunsetAtEpochMillis: nil))
  }

  @Test func aLaterResponseWithOneHeaderKeepsTheOtherEarlierValue() async throws {
    let rig = try await SenderRig.make(
      replies: [http(503, deprecation: Self.deprecation), http(503, sunset: Self.sunset), http(503, sunset: "bad")])
    _ = try await rig.sender.run()
    rig.clock.advance(by: 600_000)
    _ = try await rig.sender.run()
    rig.clock.advance(by: 600_000)
    _ = try await rig.sender.run()
    #expect(
      await rig.notice.current()
        == ContractDeprecation(deprecatedAtEpochMillis: 1_788_220_800_000, sunsetAtEpochMillis: Self.sunsetMillis))
    #expect(warnings(rig) == 1, "one WARN per process, not per date")
  }

  @Test func theNewestValidValueReplacesTheOlderOne() async throws {
    let rig = try await SenderRig.make(
      replies: [http(503, sunset: Self.sunset), http(503, sunset: "Thu, 12 Nov 2026 00:00:00 GMT")])
    _ = try await rig.sender.run()
    rig.clock.advance(by: 600_000)
    _ = try await rig.sender.run()
    #expect(await rig.notice.current()?.sunsetAtEpochMillis == Self.sunsetMillis + 1000)
  }

  @Test func anErrorResponseWithTheHeadersIsReadAndStillHandledAsBefore() async throws {
    for status in [400, 401, 429, 503] {
      let plain = try await SenderRig.make(replies: [http(status)], jitter: [3000])
      let withHeaders = try await SenderRig.make(
        replies: [http(status, deprecation: Self.deprecation, sunset: Self.sunset)], jitter: [3000])
      _ = try await plain.sender.run()
      _ = try await withHeaders.sender.run()
      #expect(try await plain.retry == withHeaders.retry, "status \(status)")
      #expect(await plain.notice.current() == nil)
      #expect(await withHeaders.notice.current() != nil)
    }
  }

  @Test func theNoticeIsInMemoryOnlySoARelaunchStartsEmpty() async throws {
    let rig = try await SenderRig.make(replies: [http(503, sunset: Self.sunset)])
    _ = try await rig.sender.run()
    let after = try await rig.relaunched()
    #expect(await after.notice.current() == nil)
  }
}
