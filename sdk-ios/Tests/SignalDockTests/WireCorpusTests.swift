import Foundation
import Testing

@testable import SignalDock

/// Drift guard for the SDK/server wire contract (ADR-0027, ADR-0047 D10), the iOS side of the Android `WireCorpusTest`.
/// It builds request bodies with the real `track()` path and the real batch assembly, then compares them byte for byte
/// with the committed corpus in `fixtures/sdk-wire/ios/`. The server test `SdkWireCorpusTest` parses the same files with
/// the real request parser and expects no request-level failure and no per-item rejection.
///
/// To refresh the corpus after a deliberate change, set the regeneration switch for the test process, then review the
/// diff: `TEST_RUNNER_UPDATE_SDK_WIRE=1 scripts/verify-ios.sh tsan` (`xcodebuild` passes `TEST_RUNNER_`-prefixed
/// variables to the test process without the prefix).
///
/// The cases have the names and the content of `fixtures/sdk-wire/android/`, so that the comparison test can check equal
/// JSON values (ADR-0047 D10). The size cases pad to the limit as the Android event sees it: the iOS event is
/// `platformSizeDifference` bytes smaller for the same content (`"ios"` against `"android"`), so these iOS events are that
/// many bytes below 16 KiB. The exact limit has its own test (`TrackTests`).
@Suite struct WireCorpusTests {
  static let installationId = "47c4d89f-352a-41da-9b31-529ea756515a"
  static let root = WireModelTests.root.appendingPathComponent("fixtures/sdk-wire")
  static let iosDirectory = root.appendingPathComponent("ios")
  static let androidDirectory = root.appendingPathComponent("android")

  /// `"android".utf8.count - "ios".utf8.count`: the `sdkPlatform` value is longer on Android.
  static let platformSizeDifference = 4
  static let eventLimit = 16 * 1024 - platformSizeDifference
  static let maxSafe: Int64 = 9_007_199_254_740_991

  /// The cases in the order of the Android test, so that event IDs (a sequence) and padding agree.
  static func cases() async throws -> [(name: String, body: String)] {
    let dir = try TempDirectory()
    let sdk = try await SDKFactory.create(
      config: Harness.config, base: dir.url, clock: FixedClock(), ids: SequenceIds(),
      installationIds: FixedId(id: installationId), limits: QueueLimits(maxEventBytes: eventLimit),
      logger: RecordingLogger(), delivery: .inert)
    var out: [(String, String)] = []

    func ok(_ name: String, _ build: (inout EventBuilder) -> Void = { _ in }) async throws {
      let result = try await sdk.track(name, build)
      guard case .success = result else { throw CorpusError.notStored("track(\(name)) gave \(result)") }
    }
    func clear() async throws {
      _ = try await sdk.queue.deleteByIds(try await sdk.queue.oldest(limit: 10_000).map(\.eventId))
    }
    func body() async throws -> String {
      let payloads = try await sdk.queue.oldest(limit: 50).map(\.payloadJson)
      let text = String(decoding: try BatchBody.make(installationId: installationId, payloads: payloads), as: UTF8.self)
      try await clear()
      return text
    }

    try await ok("app_opened")
    out.append(("minimal", try await body()))

    try await ok("all_types") {
      $0.property("text", "hello")
      $0.property("empty", "")
      $0.property("yes", true)
      $0.property("no", false)
      $0.property("int", 2)
      $0.property("int_min", Int(Int32.min))
      $0.property("long_max", maxSafe)
      $0.property("long_min", -maxSafe)
      $0.property("zero", 0)
      $0.property("double", 12.5)
      $0.property("double_small", 1e-7)
      $0.property("double_neg", -0.1)
      $0.property("double_zero", 0.0)
      $0.property("double_neg_zero", -0.0)
      $0.property("double_big", Double(maxSafe) - 1.0)
      $0.property("double_frac_big", 4_503_599_627_370_496.5)
      // `double_tiny` (the smallest subnormal) is not in the iOS case; see `knownDifferences`.
      $0.property("double_large_frac", 1.7976931348623157E308 / 1e300)
    }
    out.append(("all-property-types", try await body()))

    try await ok("unicode_\u{ac00}_\u{1f389}") {
      $0.property("\u{d55c}\u{ae00}", "\u{ac00}\u{b098}\u{b2e4}")
      $0.property("emoji", "\u{1f389}\u{1f468}\u{200d}\u{1f469}\u{200d}\u{1f467}\u{200d}\u{1f466}\u{1f1f0}\u{1f1f7}")
      $0.property("astral", "\u{1f600}\u{1d11e}")
      $0.property("bmp_edge", "\u{ffff}\u{fffd}\u{e000}")
      $0.property(
        "escapes", "quote\" back\\ slash/ nl\n cr\r tab\t bs\u{8} ff\u{c} ctl\u{1}\u{1f} del\u{7f}")
      $0.property("separators", "a\u{2028}b\u{2029}c")
      $0.property("edge_whitespace", "  value\u{a0}")
      $0.property("emoji_key_\u{1f389}", "v")
      $0.property("nfd", "e\u{301}")
    }
    try await ok("a")  // one-character name
    try await ok("name with spaces inside")
    out.append(("unicode", try await body()))

    try await ok("e") { $0.property("k", "") }
    try await ok("e") { $0.property("k", "x") }
    out.append(("empty-and-short-strings", try await body()))

    // Largest event: transport size exactly the limit (wrapper included).
    try await ok("e") { $0.property("k", "") }
    let base = try await sdk.queue.oldest(limit: 1)[0].sizeBytes
    try await ok("e") { $0.property("k", String(repeating: "x", count: eventLimit - base)) }
    guard try await sdk.queue.oldest(limit: 2)[1].sizeBytes == eventLimit else {
      throw CorpusError.notStored("the largest event is not exactly the limit")
    }
    out.append(("max-event-size", try await body()))

    // Largest event made of 3-byte and 4-byte characters and escapes.
    try await ok("e") { $0.property("k", "") }
    try await ok("e") { $0.property("k", String(repeating: "\u{ac00}", count: (eventLimit - base) / 3)) }
    try await ok("e") { $0.property("k", String(repeating: "\u{1f389}", count: (eventLimit - base) / 4)) }
    try await ok("e") { $0.property("k", String(repeating: "\"", count: (eventLimit - base) / 2)) }
    out.append(("max-event-size-multibyte", try await body()))

    // Most properties that fit in one event.
    func props(_ n: Int) -> (inout EventBuilder) -> Void {
      { builder in for i in 0..<n { builder.property(String(format: "k%05d", i), i) } }
    }
    var n = 100
    while case .success = try await sdk.track("e", props(n + 100)) { n += 100 }  // probe only
    while case .success = try await sdk.track("e", props(n + 1)) { n += 1 }
    try await clear()
    guard n > 1000 else { throw CorpusError.notStored("expected more than 1000 properties, got \(n)") }
    try await ok("e", props(n))
    out.append(("max-property-count", try await body()))

    // A batch of 50 small events (the event count limit).
    for i in 0..<50 { try await ok("e") { $0.property("i", i) } }
    out.append(("max-batch-events", try await body()))

    // A batch cut by buildBatch at the 256 KiB byte limit, from events of the size limit.
    try await ok("e") { $0.property("k", "") }
    for _ in 0..<20 { try await ok("e") { $0.property("k", String(repeating: "x", count: eventLimit - base)) } }
    let rows = try await sdk.queue.oldest(limit: 50).dropFirst()
    let batch = try buildBatch(rows: Array(rows))
    guard batch.body.count <= 256 * 1024 && batch.events.count < rows.count else {
      throw CorpusError.notStored("the batch is not cut by the byte limit")
    }
    out.append(("max-batch-bytes", String(decoding: batch.body, as: UTF8.self)))
    try await clear()

    await sdk.queue.close()
    return out
  }

  enum CorpusError: Error { case notStored(String) }

  static func names(in directory: URL) -> [String] {
    ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []).filter { $0.hasSuffix(".json") }
      .sorted()
  }

  @Test func corpusMatchesWhatTheSDKBuilds() async throws {
    let built = try await Self.cases()
    // ADR-0046 decision 3: every SDK must send sdkPlatform.
    for (name, body) in built {
      let events =
        try #require(
          try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any])["events"] as? [[String: Any]]
      #expect(events?.isEmpty == false, "\(name) has no event")
      for (i, event) in (events ?? []).enumerated() {
        #expect(event["sdkPlatform"] as? String == "ios", "\(name) event \(i)")
      }
    }
    if ProcessInfo.processInfo.environment["UPDATE_SDK_WIRE"] == "1" {
      let fm = FileManager.default
      try fm.createDirectory(at: Self.iosDirectory, withIntermediateDirectories: true)
      for name in Self.names(in: Self.iosDirectory) {
        try fm.removeItem(at: Self.iosDirectory.appendingPathComponent(name))
      }
      for (name, body) in built {
        try Data(body.utf8).write(to: Self.iosDirectory.appendingPathComponent("\(name).json"))
      }
    }
    #expect(
      Self.names(in: Self.iosDirectory) == built.map { "\($0.name).json" }.sorted(),
      "corpus files differ from the cases (run with TEST_RUNNER_UPDATE_SDK_WIRE=1)")
    for (name, body) in built {
      let committed = try? Data(contentsOf: Self.iosDirectory.appendingPathComponent("\(name).json"))
      #expect(committed == Data(body.utf8), "fixtures/sdk-wire/ios/\(name).json drifted from the SDK output")
    }
  }

  @Test func theIosCasesHaveTheNamesOfTheAndroidCases() {
    #expect(Self.names(in: Self.iosDirectory) == Self.names(in: Self.androidDirectory))
    #expect(Self.names(in: Self.iosDirectory).count >= 9)
  }

  @Test(arguments: WireModelTests.files(in: "sdk-wire/ios"))
  func iosWireCaseKeepsItsJSONValue(file: String) throws {
    let (original, encoded, request) = try WireModelTests.roundTrip(
      BatchIngestRequest.self, WireModelTests.fixture("sdk-wire/ios/\(file)"))
    #expect(encoded == original, "\(file)")
    #expect(!request.events.isEmpty)
    #expect(request.installationId == Self.installationId)
  }

  // MARK: Comparison with the Android corpus (ADR-0047 D10)

  /// Properties that the iOS case does not have, per case file. Swift writes the smallest subnormal `Double` as `5e-324`;
  /// Java writes the same Double as `4.9E-324`, and the server accepts a decimal only if it equals
  /// `BigDecimal.valueOf(double)`, so it answers `INVALID_PROPERTIES` to the Swift text. This is an open question of
  /// iOS SDK ticket 09 (relax the server rule, or reject subnormals in the SDK). The same list is in `SdkWireCorpusTest`.
  static let knownDifferences: [String: Set<String>] = ["all-property-types.json": ["double_tiny"]]

  /// The request as a JSON value without the fields that differ by platform: `sdkPlatform` and `sdkVersion` of each event.
  static func comparable(_ data: Data, without left: Set<String> = []) throws -> WireModelTests.JSONNode {
    guard case .object(var root) = try JSONDecoder().decode(WireModelTests.JSONNode.self, from: data),
      case .array(let events)? = root["events"]
    else { throw CorpusError.notStored("not a batch request") }
    root["events"] = .array(
      events.map { event in
        guard case .object(var fields) = event else { return event }
        fields["sdkPlatform"] = nil
        fields["sdkVersion"] = nil
        if case .object(var properties)? = fields["properties"] {
          for key in left { properties[key] = nil }
          fields["properties"] = .object(properties)
        }
        return .object(fields)
      })
    return .object(root)
  }

  @Test(arguments: WireModelTests.files(in: "sdk-wire/ios"))
  func theSameCaseHasEqualJSONValuesOnBothPlatforms(file: String) throws {
    let left = Self.knownDifferences[file] ?? []
    let ios = try Self.comparable(Data(contentsOf: Self.iosDirectory.appendingPathComponent(file)), without: left)
    let android = try Self.comparable(
      Data(contentsOf: Self.androidDirectory.appendingPathComponent(file)), without: left)
    #expect(ios == android, "\(file): numbers compare by value; sdkPlatform and sdkVersion are excluded")
  }

  @Test func theSwiftTextOfTheSmallestSubnormalIsNotTheTextJavaWrites() throws {
    let text = String(decoding: try JSONEncoder().encode([Double.leastNonzeroMagnitude]), as: UTF8.self)
    #expect(text == "[5e-324]", "Java writes 4.9E-324, the only text that the server accepts for this Double")
  }

  @Test func theComparisonSeesADifference() throws {
    let a = Data(#"{"installationId":"i","events":[{"name":"x","sdkPlatform":"ios","sdkVersion":"1","n":1}]}"#.utf8)
    let b = Data(
      #"{"installationId":"i","events":[{"name":"x","sdkPlatform":"android","sdkVersion":"2","n":1.0}]}"#.utf8)
    let c = Data(#"{"installationId":"i","events":[{"name":"y","sdkPlatform":"ios","sdkVersion":"1","n":1}]}"#.utf8)
    #expect(try Self.comparable(a) == Self.comparable(b), "1 and 1.0 are equal by value")
    #expect(try Self.comparable(a) != Self.comparable(c))
  }
}
