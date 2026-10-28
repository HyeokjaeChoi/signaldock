import Foundation
import Testing

@testable import SignalDock

/// The whole ACK is checked before the queue changes (architecture "Validate the entire ACK"), against the Android
/// `AckValidator`.
@Suite struct AckValidatorTests {
  let ids = ["a", "b", "c"]

  private func validate(_ json: String?, ids: [String]? = nil) -> [ItemAck]? {
    validateAck(requestEventIds: ids ?? self.ids, body: json.map { Data($0.utf8) })
  }

  private func item(_ index: Int, _ status: String, _ id: String? = nil, extra: String = "") -> String {
    let eventId = id.map { #","eventId":"\#($0)""# } ?? ""
    return #"{"index":\#(index),"status":"\#(status)"\#(eventId)\#(extra)}"#
  }

  private func body(_ items: [String]) -> String { #"{"results":["# + items.joined(separator: ",") + "]}" }

  @Test func aCompleteAckComesBackOrderedByRequestIndex() throws {
    let result = try #require(
      validate(body([item(2, "accepted", "c"), item(0, "duplicate", "a"), item(1, "accepted", "b")])))
    #expect(result.map(\.index) == [0, 1, 2])
    #expect(result.map(\.status) == [.duplicate, .accepted, .accepted])
  }

  @Test func extraFieldsAreIgnored() throws {
    let json =
      #"{"results":["#
      + [
        item(0, "accepted", "a", extra: #","future":{"x":1}"#), item(1, "accepted", "b"),
        item(2, "rejected", "c", extra: #","code":"BAD","message":"m","more":[1]"#),
      ].joined(separator: ",") + #"],"serverTime":"x"}"#
    #expect(validate(json)?.count == 3)
  }

  @Test func aRejectedItemMayOmitItsEventIdButNotContradictIt() {
    #expect(validate(body([item(0, "accepted", "a"), item(1, "accepted", "b"), item(2, "rejected")])) != nil)
    #expect(validate(body([item(0, "accepted", "a"), item(1, "accepted", "b"), item(2, "rejected", "x")])) == nil)
  }

  @Test func acceptedAndDuplicateMustEchoTheRequestEventId() {
    for status in ["accepted", "duplicate"] {
      #expect(validate(body([item(0, status, "a"), item(1, "accepted", "b"), item(2, "accepted", "x")])) == nil)
      #expect(validate(body([item(0, status), item(1, "accepted", "b"), item(2, "accepted", "c")])) == nil)
    }
  }

  @Test func theEventIdMatchesByteForByteNotByCanonicalEquivalence() {
    #expect(validate(body([item(0, "accepted", "\u{e9}")]), ids: ["e\u{301}"]) == nil)
    #expect(validate(body([item(0, "accepted", "\u{e9}")]), ids: ["\u{e9}"]) != nil)
  }

  @Test func missingDuplicateAndOutOfRangeIndexesAreInvalid() {
    #expect(validate(body([item(0, "accepted", "a"), item(1, "accepted", "b")])) == nil)  // one missing
    #expect(
      validate(
        body([item(0, "accepted", "a"), item(1, "accepted", "b"), item(2, "accepted", "c"), item(2, "accepted", "c")]))
        == nil)
    #expect(validate(body([item(0, "accepted", "a"), item(1, "accepted", "b"), item(1, "accepted", "b")])) == nil)
    #expect(validate(body([item(0, "accepted", "a"), item(1, "accepted", "b"), item(3, "accepted", "c")])) == nil)
    #expect(validate(body([item(0, "accepted", "a"), item(1, "accepted", "b"), item(-1, "accepted", "c")])) == nil)
  }

  @Test func anUnknownStatusIsInvalid() {
    #expect(validate(body([item(0, "accepted", "a"), item(1, "accepted", "b"), item(2, "maybe", "c")])) == nil)
  }

  @Test func aWrongJsonTypeOfAKnownFieldIsInvalid() {
    let ok = [item(1, "accepted", "b"), item(2, "accepted", "c")]
    let bad = [
      #"{"index":"0","status":"accepted","eventId":"a"}"#,  // quoted index
      #"{"index":0.0,"status":"accepted","eventId":"a"}"#,  // fraction
      #"{"index":0e0,"status":"accepted","eventId":"a"}"#,  // exponent
      #"{"index":true,"status":"accepted","eventId":"a"}"#,
      #"{"index":null,"status":"accepted","eventId":"a"}"#,
      #"{"status":"accepted","eventId":"a"}"#,  // no index
      #"{"index":0,"status":1,"eventId":"a"}"#,
      #"{"index":0,"status":"accepted","eventId":5}"#,
      #"{"index":0,"status":"rejected","code":5}"#,
      #"{"index":0,"status":"rejected","message":false}"#,
    ]
    for first in bad {
      #expect(validate(body([first] + ok)) == nil, "\(first)")
    }
    // null is the same as absent for the optional string fields.
    #expect(
      validate(body([#"{"index":0,"status":"rejected","eventId":null,"code":null,"message":null}"#] + ok)) != nil)
  }

  @Test func anUnreadableOrWronglyShapedBodyIsInvalid() {
    for text in [
      nil, "", "not json", "[]", "{}", #"{"results":{}}"#, #"{"results":null}"#, #"{"results":[1,2,3]}"#,
      #"{"results":"x"}"#, #"{"results":[]"#,
    ] as [String?] {
      #expect(validate(text) == nil, "\(text ?? "nil")")
    }
  }

  @Test func aRequestOfOneEventNeedsExactlyOneResult() {
    #expect(validate(body([item(0, "accepted", "a")]), ids: ["a"]) != nil)
    #expect(validate(body([]), ids: ["a"]) == nil)
  }
}
