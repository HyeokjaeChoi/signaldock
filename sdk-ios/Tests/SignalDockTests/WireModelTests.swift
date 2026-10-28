import Foundation
import Testing

@testable import SignalDock

/// The generated wire models (`generated/swift`, ADR-0047 D10) against the shared fixtures: every fixture decodes,
/// encodes again, and equals the original by JSON value (numbers by value, not by spelling).
@Suite struct WireModelTests {
  /// Repository root, from this file's path: `<root>/sdk-ios/Tests/SignalDockTests/<file>`.
  static let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

  /// A JSON value that tells `true` from `1` and compares numbers by value.
  enum JSONNode: Codable, Equatable {
    case null, bool(Bool), number(Double), string(String), array([JSONNode]), object([String: JSONNode])

    init(from decoder: Decoder) throws {
      let c = try decoder.singleValueContainer()
      if c.decodeNil() {
        self = .null
      } else if let v = try? c.decode(Bool.self) {
        self = .bool(v)
      } else if let v = try? c.decode(Double.self) {
        self = .number(v)
      } else if let v = try? c.decode(String.self) {
        self = .string(v)
      } else if let v = try? c.decode([JSONNode].self) {
        self = .array(v)
      } else {
        self = .object(try c.decode([String: JSONNode].self))
      }
    }

    func encode(to encoder: Encoder) throws {
      var c = encoder.singleValueContainer()
      switch self {
      case .null: try c.encodeNil()
      case .bool(let v): try c.encode(v)
      case .number(let v): try c.encode(v)
      case .string(let v): try c.encode(v)
      case .array(let v): try c.encode(v)
      case .object(let v): try c.encode(v)
      }
    }

    /// The contract has no null values (Q33-35); a null in a response means "absent", which the models omit.
    var withoutNulls: JSONNode {
      switch self {
      case .array(let v): return .array(v.map(\.withoutNulls))
      case .object(let v): return .object(v.filter { $0.value != .null }.mapValues(\.withoutNulls))
      default: return self
      }
    }
  }

  static func fixture(_ path: String) throws -> Data {
    try Data(contentsOf: root.appendingPathComponent("fixtures/\(path)"))
  }

  static func files(in dir: String) -> [String] {
    let path = root.appendingPathComponent("fixtures/\(dir)").path
    return ((try? FileManager.default.contentsOfDirectory(atPath: path)) ?? []).filter { $0.hasSuffix(".json") }
      .sorted()
  }

  /// Decodes `data` as `T`, encodes it, and returns (original, encoded) as JSON values.
  static func roundTrip<T: Codable>(_ type: T.Type, _ data: Data) throws -> (JSONNode, JSONNode, T) {
    let value = try JSONDecoder().decode(T.self, from: data)
    let encoded = try JSONEncoder().encode(value)
    let json = JSONDecoder()
    return (try json.decode(JSONNode.self, from: data), try json.decode(JSONNode.self, from: encoded), value)
  }

  /// Fixture file name to the model that carries it.
  static let roundtripModels: [(String, any Codable.Type)] = [
    ("batch-ack.json", BatchAckResponse.self),
    ("batch-request.json", BatchIngestRequest.self),
    ("batch-request-ios.json", BatchIngestRequest.self),
    ("client-report-counts.json", ClientReportCounts.self),
    ("client-report-request.json", ClientReportRequest.self),
    ("client-report-request-ios.json", ClientReportRequest.self),
    ("error-400.json", ModelErrorResponse.self),
    ("event-name-counts.json", EventNameCounts.self),
    ("event-plan-status-counts.json", EventPlanStatusCounts.self),
    ("event-receipt-detail.json", EventReceiptDetail.self),
    ("event-receipt-detail-plan-status.json", EventReceiptDetail.self),
    ("event-receipt-page.json", EventReceiptPage.self),
    ("event-receipt-page-plan-status.json", EventReceiptPage.self),
    ("event-time-counts.json", EventTimeCounts.self),
  ]

  @Test func everyRoundtripFixtureHasAModel() {
    #expect(Set(Self.files(in: "roundtrip")) == Set(Self.roundtripModels.map(\.0)))
  }

  @Test(arguments: roundtripModels.map(\.0))
  func roundtripFixtureKeepsItsJSONValue(file: String) throws {
    let model = try #require(Self.roundtripModels.first { $0.0 == file }?.1)
    let data = try Self.fixture("roundtrip/\(file)")
    func check<T: Codable>(_ type: T.Type) throws {
      let (original, encoded, _) = try Self.roundTrip(type, data)
      #expect(encoded == original.withoutNulls, "\(file)")
    }
    try check(model)
  }

  @Test(arguments: WireModelTests.files(in: "sdk-wire/android"))
  func androidWireCaseKeepsItsJSONValue(file: String) throws {
    let (original, encoded, request) = try Self.roundTrip(
      BatchIngestRequest.self, Self.fixture("sdk-wire/android/\(file)"))
    #expect(encoded == original, "\(file)")
    #expect(!request.events.isEmpty)
  }

  @Test func androidWireCasesAreFound() {
    #expect(Self.files(in: "sdk-wire/android").count >= 5)
  }

  @Test func propertyValueDecodesStringNumberAndBooleanByValue() throws {
    let data = try Self.fixture("sdk-wire/android/all-property-types.json")
    let props = try #require(JSONDecoder().decode(BatchIngestRequest.self, from: data).events.first).properties
    #expect(props["text"] == .typeString("hello"))
    #expect(props["empty"] == .typeString(""))
    #expect(props["yes"] == .typeBool(true))
    #expect(props["no"] == .typeBool(false))
    #expect(props["int"] == .typeDouble(2))  // 2 is a number, not true
    #expect(props["zero"] == .typeDouble(0))  // 0 is a number, not false
    #expect(props["int_min"] == .typeDouble(-2_147_483_648))
    #expect(props["long_max"] == .typeDouble(9_007_199_254_740_991))
    #expect(props["double_small"] == .typeDouble(1.0e-7))
    #expect(props["double_tiny"] == .typeDouble(4.9e-324))
    #expect(props["double_big"] == .typeDouble(9.00719925474099e15))
  }

  @Test func propertyValueEncodesEachCaseAsItsOwnJSONType() throws {
    let encoder = JSONEncoder()
    #expect(String(decoding: try encoder.encode([PropertyValue.typeString("a")]), as: UTF8.self) == #"["a"]"#)
    #expect(String(decoding: try encoder.encode([PropertyValue.typeBool(true)]), as: UTF8.self) == "[true]")
    #expect(String(decoding: try encoder.encode([PropertyValue.typeDouble(2)]), as: UTF8.self) == "[2]")
    #expect(String(decoding: try encoder.encode([PropertyValue.typeDouble(12.5)]), as: UTF8.self) == "[12.5]")
  }

  @Test func propertyValueRejectsNullAndNesting() {
    let decoder = JSONDecoder()
    for bad in ["null", "[1]", #"{"a":1}"#] {
      #expect(throws: DecodingError.self) { try decoder.decode(PropertyValue.self, from: Data(bad.utf8)) }
    }
  }

  @Test func occurredAtStaysAStringAndSdkPlatformIsKept() throws {
    let data = try Self.fixture("roundtrip/batch-request-ios.json")
    let events = try JSONDecoder().decode(BatchIngestRequest.self, from: data).events
    #expect(events[1].occurredAt == "2026-10-10T06:30:00.123000+09:00")  // the offset form stays as written
    #expect(events.allSatisfy { $0.sdkPlatform == .ios })
  }
}
