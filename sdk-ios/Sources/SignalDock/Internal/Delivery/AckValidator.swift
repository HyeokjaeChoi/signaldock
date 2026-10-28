import Foundation

/// Validates the whole ACK against the immutable request-time ID list (architecture "Validate the entire ACK before
/// changing the queue"). Returns the results ordered by request index, or `nil` if the response is unreadable,
/// incomplete, or contradictory. The caller then applies nothing. Mirrors the Android `AckValidator`.
///
/// Rules: valid JSON of the expected shape; known statuses only; exactly one result per request index, none outside
/// the request; `accepted` and `duplicate` echo the request `eventId`; a `rejected` item may omit `eventId` but must
/// not contradict it. Extra fields are ignored (Q68). `index` must be a bare integer and the other known fields strings
/// or `null`: a quoted `"1"` is an invalid ACK.
internal func validateAck(requestEventIds: [String], body: Data?) -> [ItemAck]? {
  guard let body, hasStrictTypes(body), let response = try? JSONDecoder().decode(BatchAckResponse.self, from: body)
  else { return nil }
  let results = response.results
  guard results.count == requestEventIds.count else { return nil }
  var byIndex = [ItemAck?](repeating: nil, count: requestEventIds.count)
  for item in results {
    guard requestEventIds.indices.contains(item.index), byIndex[item.index] == nil else { return nil }
    let expected = requestEventIds[item.index]
    switch item.status {
    case .accepted, .duplicate:
      guard let id = item.eventId, sameBytes(id, expected) else { return nil }
    case .rejected:
      if let id = item.eventId, !sameBytes(id, expected) { return nil }
    }
    byIndex[item.index] = item
  }
  let ordered = byIndex.compactMap { $0 }
  return ordered.count == requestEventIds.count ? ordered : nil
}

/// Swift `String` equality treats canonically equal text as equal; an event ID must match byte for byte.
private func sameBytes(_ a: String, _ b: String) -> Bool { a.utf8.elementsEqual(b.utf8) }

/// `JSONDecoder` reads `1.0` and `true` leniently in some cases, so check the JSON types of the known fields first.
private func hasStrictTypes(_ body: Data) -> Bool {
  guard let root = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
    let results = root["results"] as? [Any]
  else { return false }
  return results.allSatisfy { element in
    guard let item = element as? [String: Any], let index = item["index"] as? NSNumber, isInteger(index) else {
      return false
    }
    return ["status", "eventId", "code", "message"].allSatisfy { key in
      guard let value = item[key] else { return true }
      return value is NSNull || value is String
    }
  }
}

/// A JSON number without a fraction or exponent; not a boolean.
private func isInteger(_ number: NSNumber) -> Bool {
  CFGetTypeID(number) != CFBooleanGetTypeID() && !CFNumberIsFloatType(number)
}
