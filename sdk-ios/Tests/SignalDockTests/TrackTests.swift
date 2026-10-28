import Foundation
import Testing

@testable import SignalDock

/// `track` against the Android `TrackTest`: stored fields, validation, limits, and the shared whitespace fixture.
@Suite struct TrackTests {
  @Test func storesOneEventWithCommonFieldsAndTypedProperties() async throws {
    let h = try await Harness.make()
    let result = try await h.sdk.track("product_viewed") {
      $0.property("product_id", "P-001")
      $0.property("quantity", 2)
      $0.property("is_test", true)
      $0.property("price", 12.5)
    }
    guard case .success(let id) = result else { Issue.record("expected success, got \(result)"); return }
    let row = try #require(try await h.sdk.queue.oldest(limit: 10).first)
    #expect(row.eventId == id)
    #expect(row.contractVersion == "1")
    // size = the event as sent: a single-event batch request, wrapper included
    let request = #"{"installationId":"\#(row.installationId)","events":[\#(row.payloadJson)]}"#
    #expect(request.utf8.count == row.sizeBytes)
    let event = try #require(try await h.storedEvents().first)
    #expect(event["eventId"] as? String == id)
    #expect(event["name"] as? String == "product_viewed")
    #expect(event["occurredAt"] as? String == "2025-10-09T08:53:20.123Z")
    #expect(event["sdkVersion"] as? String == SignalDockSDK.version)
    #expect(event["sdkPlatform"] as? String == "ios")
    #expect(event["contractVersion"] as? String == "1")
    let props = try #require(event["properties"] as? [String: Any])
    #expect(props["product_id"] as? String == "P-001")
    #expect(props["quantity"] as? Int == 2)
    #expect(props["is_test"] as? Bool == true)
    #expect(props["price"] as? Double == 12.5)
    // a JSON number, not a string, and `2` is not `true`
    #expect(row.payloadJson.contains(#""quantity":2"#))
    #expect(row.payloadJson.contains(#""is_test":true"#))
  }

  @Test func sameEventGivesTheSameBytes() async throws {
    let a = try await Harness.make()
    let b = try await Harness.make()
    for h in [a, b] {
      _ = try await h.sdk.track("e") {
        $0.property("b", 1)
        $0.property("a", "x")
        $0.property("c", true)
      }
    }
    let rowA = try #require(try await a.sdk.queue.oldest(limit: 1).first)
    let rowB = try #require(try await b.sdk.queue.oldest(limit: 1).first)
    #expect(
      rowA.payloadJson == rowB.payloadJson.replacingOccurrences(of: rowB.installationId, with: rowA.installationId))
    #expect(rowA.payloadJson.contains(#""properties":{"a":"x","b":1,"c":true}"#))  // sorted keys
  }

  @Test func eventIdsAreRandomUuidV4() async throws {
    let h = try await Harness.make(ids: RandomUuidV4())
    var ids: [String] = []
    for _ in 0..<20 {
      guard case .success(let id) = try await h.sdk.track("e") else { Issue.record("not stored"); return }
      ids.append(id)
    }
    #expect(Set(ids).count == 20)
    #expect(ids.allSatisfy(isUuidV4))
  }

  private func invalid(_ h: Harness, _ name: String, build: (inout EventBuilder) -> Void = { _ in }) async throws
    -> InvalidReason?
  {
    let result = try await h.sdk.track(name, build)
    guard case .invalid(let reason, _) = result else {
      Issue.record("expected invalid, got \(result)")
      return nil
    }
    #expect(try await h.sdk.queue.usage().count == 0, "nothing stored on invalid input")
    return reason
  }

  @Test func namesAndKeysAreRejectedNeverTrimmedOrNormalized() async throws {
    let h = try await Harness.make()
    #expect(try await invalid(h, "") == .emptyName)
    #expect(try await invalid(h, "   ") == .emptyName)
    #expect(try await invalid(h, " a") == .leadingOrTrailingWhitespace)
    #expect(try await invalid(h, "a\n") == .leadingOrTrailingWhitespace)
    #expect(try await invalid(h, "a\r\n") == .leadingOrTrailingWhitespace)  // CR LF is one Character, two scalars
    #expect(try await invalid(h, "a\u{0}b") == .forbiddenCharacter)
    #expect(try await invalid(h, "ok") { $0.property("", 1) } == .emptyName)
    #expect(try await invalid(h, "ok") { $0.property("k ", 1) } == .leadingOrTrailingWhitespace)
    #expect(try await invalid(h, "ok") { $0.property("k\u{0}", 1) } == .forbiddenCharacter)
    #expect(try await invalid(h, "ok") { $0.property("k", "v\u{0}") } == .forbiddenCharacter)
  }

  @Test func invalidResultNamesTheFieldAndTheFirstProblemWins() async throws {
    let h = try await Harness.make()
    #expect(try await h.sdk.track(" a") == .invalid(reason: .leadingOrTrailingWhitespace, field: "name"))
    #expect(
      try await h.sdk.track("ok") {
        $0.property("k1", Double.nan)
        $0.property("k2", "")
        $0.property("k3 ", 1)
      } == .invalid(reason: .nonFiniteNumber, field: "k1"))
    // a bad name wins over a bad property: the name is checked first, as on Android
    #expect(try await h.sdk.track("") { $0.property("", 1) } == .invalid(reason: .emptyName, field: "name"))
  }

  /// `fixtures/validation/whitespace-set.json`, shared with the Android SDK and the server.
  private func whitespaceFixture() throws -> (members: [Unicode.Scalar], nearMisses: [Unicode.Scalar]) {
    let url = WireModelTests.root.appendingPathComponent("fixtures/validation/whitespace-set.json")
    let json = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    func scalars(_ key: String) throws -> [Unicode.Scalar] {
      try #require(json[key] as? [String]).map {
        Unicode.Scalar(UInt32($0.dropFirst(2), radix: 16)!)!
      }
    }
    return (try scalars("members"), try scalars("nearMisses"))
  }

  @Test func everyCharacterOfTheSharedWhitespaceSetIsRejectedOthersAreAccepted() async throws {
    let h = try await Harness.make()
    let (members, nearMisses) = try whitespaceFixture()
    #expect(members.count == 25)
    #expect(nearMisses.count == 4)
    for scalar in members {
      let c = String(Character(scalar))
      let hex = String(format: "U+%04X", scalar.value)
      #expect(try await invalid(h, c) == .emptyName, Comment(rawValue: hex))
      #expect(try await invalid(h, "ok") { $0.property(c + c, 1) } == .emptyName, Comment(rawValue: hex))
      #expect(try await invalid(h, c + "a") == .leadingOrTrailingWhitespace, Comment(rawValue: hex))
      #expect(try await invalid(h, "a" + c) == .leadingOrTrailingWhitespace, Comment(rawValue: hex))
      #expect(
        try await invalid(h, "ok") { $0.property(c + "k", 1) } == .leadingOrTrailingWhitespace, Comment(rawValue: hex))
      #expect(
        try await invalid(h, "ok") { $0.property("k" + c, 1) } == .leadingOrTrailingWhitespace, Comment(rawValue: hex))
    }
    // not in the set: accepted even at the edges
    for scalar in nearMisses {
      let c = String(Character(scalar))
      let result = try await h.sdk.track(c + "a" + c) { $0.property(c + "k", 1) }
      guard case .success = result else { Issue.record("U+\(String(scalar.value, radix: 16)): \(result)"); return }
    }
  }

  @Test func internalWhitespaceKoreanTextAndStringValuesWithEdgeWhitespaceArePreserved() async throws {
    let h = try await Harness.make()
    let result = try await h.sdk.track("장바구니 담기") { $0.property("상품 이름", "  가나다 🎉 ") }
    guard case .success = result else { Issue.record("\(result)"); return }
    let event = try #require(try await h.storedEvents().first)
    #expect(event["name"] as? String == "장바구니 담기")
    #expect((event["properties"] as? [String: Any])?["상품 이름"] as? String == "  가나다 🎉 ")
  }

  @Test func namesWithTheSameLookButDifferentCodePointsAreKeptAsGiven() async throws {
    let h = try await Harness.make()
    let composed = "\u{00E9}"
    let decomposed = "e\u{0301}"
    guard case .success = try await h.sdk.track(composed) else { Issue.record("composed"); return }
    guard case .success = try await h.sdk.track(decomposed) else { Issue.record("decomposed"); return }
    let names = try await h.storedEvents().compactMap { $0["name"] as? String }
    // no normalization: the stored scalars are the given scalars
    #expect(names.map { Array($0.unicodeScalars.map(\.value)) } == [[0xE9], [0x65, 0x301]])
  }

  @Test func aRepeatedKeyFailsTheWholeCallEvenWithTheSameValue() async throws {
    let h = try await Harness.make()
    #expect(
      try await invalid(h, "e") {
        $0.property("k", 1); $0.property("k", 1)
      } == .duplicateKey)
    // Swift treats canonically equal strings as one key; the call is rejected, not merged
    #expect(
      try await invalid(h, "e") {
        $0.property("\u{00E9}", 1); $0.property("e\u{0301}", 2)
      } == .duplicateKey)
  }

  @Test func numbersOutsideTheContractAreRejectedBoundariesAccepted() async throws {
    let h = try await Harness.make()
    let max: Int64 = 9_007_199_254_740_991
    #expect(try await invalid(h, "e") { $0.property("k", Double.nan) } == .nonFiniteNumber)
    #expect(try await invalid(h, "e") { $0.property("k", Double.infinity) } == .nonFiniteNumber)
    #expect(try await invalid(h, "e") { $0.property("k", -Double.infinity) } == .nonFiniteNumber)
    #expect(try await invalid(h, "e") { $0.property("k", max + 1) } == .numberOutOfRange)
    #expect(try await invalid(h, "e") { $0.property("k", -max - 1) } == .numberOutOfRange)
    #expect(try await invalid(h, "e") { $0.property("k", Int64.min) } == .numberOutOfRange)
    #expect(try await invalid(h, "e") { $0.property("k", Int64.max) } == .numberOutOfRange)
    #expect(try await invalid(h, "e") { $0.property("k", Int.max) } == .numberOutOfRange)  // Int is 64 bit
    #expect(try await invalid(h, "e") { $0.property("k", Int.min) } == .numberOutOfRange)
    #expect(try await invalid(h, "e") { $0.property("k", Double(max + 1)) } == .numberOutOfRange)
    #expect(try await invalid(h, "e") { $0.property("k", 1e20) } == .numberOutOfRange)
    #expect(try await invalid(h, "e") { $0.property("k", -1e20) } == .numberOutOfRange)
    let result = try await h.sdk.track("e") {
      $0.property("a", max)
      $0.property("b", -max)
      $0.property("c", Double(max) - 1)
      $0.property("d", 1e-7)
      $0.property("e", Int(max))
    }
    guard case .success = result else { Issue.record("\(result)"); return }
    let row = try #require(try await h.sdk.queue.oldest(limit: 1).first)
    #expect(row.payloadJson.contains(#""a":9007199254740991"#))
    #expect(row.payloadJson.contains(#""b":-9007199254740991"#))
    #expect(row.payloadJson.contains(#""e":9007199254740991"#))
    let props = try #require(try await h.storedEvents().first?["properties"] as? [String: Any])
    #expect(props["d"] as? Double == 1e-7)
  }

  @Test func eventSizeLimitIs16KiBOfTransportJSON() async throws {
    let h = try await Harness.make()
    _ = try await h.sdk.track("e") { $0.property("k", "") }
    let base = try #require(try await h.sdk.queue.oldest(limit: 1).first).sizeBytes
    let limit = 16 * 1024
    guard case .success = try await h.sdk.track("e", { $0.property("k", String(repeating: "x", count: limit - base)) })
    else { Issue.record("exactly the limit must be accepted"); return }
    #expect(try await h.sdk.queue.oldest(limit: 2)[1].sizeBytes == limit)
    let result = try await h.sdk.track("e") { $0.property("k", String(repeating: "x", count: limit - base + 1)) }
    #expect(result == .invalid(reason: .eventTooLarge, field: nil))
    #expect(try await h.sdk.queue.usage().count == 2)
  }

  @Test func sizeIsMeasuredInUTF8BytesOfEscapedJSON() async throws {
    let h = try await Harness.make()
    _ = try await h.sdk.track("e") { $0.property("k", "") }
    let base = try #require(try await h.sdk.queue.oldest(limit: 1).first).sizeBytes
    // 3-byte characters: 5462 * 3 = 16386 bytes alone is over the limit although 5462 characters is small.
    let wide = try await h.sdk.track("e") { $0.property("k", String(repeating: "가", count: 5462)) }
    #expect(wide == .invalid(reason: .eventTooLarge, field: nil))
    // escaped quotes count two bytes each
    let quotes = try await h.sdk.track("e") {
      $0.property("k", String(repeating: "\"", count: (16 * 1024 - base) / 2 + 1))
    }
    #expect(quotes == .invalid(reason: .eventTooLarge, field: nil))
    // a slash is not escaped, like the Android output
    _ = try await h.sdk.track("e") { $0.property("k", "a/b") }
    #expect(try await h.sdk.queue.oldest(limit: 3)[1].payloadJson.contains(#""k":"a/b""#))
  }

  @Test func queueLimitedByEventCountKeepsExistingEvents() async throws {
    let h = try await Harness.make(limits: QueueLimits(maxEvents: 3))
    for n in 0..<3 {
      guard case .success = try await h.sdk.track("e\(n)") else { Issue.record("e\(n)"); return }
    }
    #expect(try await h.sdk.track("overflow") == .queueFull)
    #expect(try await h.storedEvents().compactMap { $0["name"] as? String } == ["e0", "e1", "e2"])
  }

  @Test func queueLimitedByTotalTransportBytes() async throws {
    let probe = try await Harness.make()
    _ = try await probe.sdk.track("e")
    let size = try #require(try await probe.sdk.queue.oldest(limit: 1).first).sizeBytes
    let h = try await Harness.make(limits: QueueLimits(maxQueueBytes: Int64(size * 3 + 1)))
    for _ in 0..<3 {
      guard case .success = try await h.sdk.track("e") else { Issue.record("fits"); return }
    }
    #expect(try await h.sdk.track("e") == .queueFull)
    #expect(try await h.sdk.queue.usage().count == 3)
  }

  @Test func defaultLimitsMatchTheAgreedValues() {
    let limits = QueueLimits()
    #expect(limits.maxEvents == 10_000)
    #expect(limits.maxQueueBytes == 16 * 1024 * 1024)
    #expect(limits.maxEventBytes == 16 * 1024)
  }

  @Test func concurrentTracksNeverExceedTheLimit() async throws {
    let h = try await Harness.make(limits: QueueLimits(maxEvents: 25))
    let sdk = h.sdk
    let results = try await withThrowingTaskGroup(of: TrackResult.self) { group in
      for _ in 0..<100 { group.addTask { try await sdk.track("e") } }
      return try await group.reduce(into: []) { $0.append($1) }
    }
    #expect(results.filter { if case .success = $0 { true } else { false } }.count == 25)
    #expect(results.filter { $0 == .queueFull }.count == 75)
    #expect(try await sdk.queue.usage().count == 25)
  }

  @MainActor private func closureRanOnMain(_ sdk: SignalDockSDK) async throws -> Bool {
    // The closure is not Sendable and writes a local variable: it compiles because `track` takes the caller's actor.
    var ran = false
    _ = try await sdk.track("e") { _ in ran = Thread.isMainThread }
    return ran
  }

  @Test func theClosureRunsOnTheCallersActor() async throws {
    let h = try await Harness.make()
    #expect(try await closureRanOnMain(h.sdk))
  }
}
