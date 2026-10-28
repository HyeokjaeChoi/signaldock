/// The builder of ``SignalDockSDK/track(isolation:_:_:)``. Properties are flat string, number, and boolean values. A
/// repeated key fails the whole call, even with the same value. Invalid input is reported through
/// ``TrackResult/invalid(reason:field:)``; nothing is trimmed or converted.
public struct EventBuilder: Sendable {
  internal private(set) var properties: [String: EventIngestPropertiesValue] = [:]
  internal private(set) var error: TrackResult?

  internal init() {}

  /// Adds a string property. The key follows the name rules; the value is kept as given (no trimming), but it must not
  /// contain a NUL character. A failure is reported by `track` as ``TrackResult/invalid(reason:field:)``.
  ///
  /// - Parameters:
  ///   - key: the property key.
  ///   - value: the string value.
  public mutating func property(_ key: String, _ value: String) {
    put(key, .typeString(value), stringValue: value)
  }

  /// Adds a boolean property. A failure is reported by `track` as ``TrackResult/invalid(reason:field:)``.
  ///
  /// - Parameters:
  ///   - key: the property key.
  ///   - value: the boolean value.
  public mutating func property(_ key: String, _ value: Bool) {
    put(key, .typeBool(value))
  }

  /// Adds an integer property. The value must be within plus or minus (2^53 - 1); otherwise `track` returns
  /// ``TrackResult/invalid(reason:field:)`` with ``InvalidReason/numberOutOfRange``. Send larger IDs as strings.
  ///
  /// - Parameters:
  ///   - key: the property key.
  ///   - value: the integer value.
  public mutating func property(_ key: String, _ value: Int) {
    property(key, Int64(value))
  }

  /// Adds an integer property. The value must be within plus or minus (2^53 - 1); otherwise `track` returns
  /// ``TrackResult/invalid(reason:field:)`` with ``InvalidReason/numberOutOfRange``. Send larger IDs as strings.
  ///
  /// - Parameters:
  ///   - key: the property key.
  ///   - value: the integer value.
  public mutating func property(_ key: String, _ value: Int64) {
    // Range check, not abs(): abs(Int64.min) overflows.
    guard (-maxSafeInteger...maxSafeInteger).contains(value) else { return fail(.numberOutOfRange, key) }
    put(key, .typeDouble(Double(value)))
  }

  /// Adds a decimal property. NaN and infinite values give ``InvalidReason/nonFiniteNumber``. A whole number outside
  /// plus or minus (2^53 - 1) gives ``InvalidReason/numberOutOfRange``. There is no `Float` overload, because widening
  /// a `Float` changes its value.
  ///
  /// - Parameters:
  ///   - key: the property key.
  ///   - value: the decimal value.
  public mutating func property(_ key: String, _ value: Double) {
    guard value.isFinite else { return fail(.nonFiniteNumber, key) }
    // The integer limit also applies to integer values passed as Double.
    if abs(value) > Double(maxSafeInteger) && value == value.rounded(.down) {
      return fail(.numberOutOfRange, key)
    }
    put(key, .typeDouble(value))
  }

  private mutating func put(_ key: String, _ value: EventIngestPropertiesValue, stringValue: String? = nil) {
    if error != nil { return }
    // Key rules are the name rules; string values only get the character rule.
    if let reason = checkText(key, isName: true) { return fail(reason, key) }
    if let stringValue, let reason = checkText(stringValue, isName: false) { return fail(reason, key) }
    if properties[key] != nil { return fail(.duplicateKey, key) }
    properties[key] = value
  }

  private mutating func fail(_ reason: InvalidReason, _ key: String) {
    if error == nil { error = .invalid(reason: reason, field: key) }
  }
}
