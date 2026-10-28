import Foundation

internal let maxSafeInteger: Int64 = 9_007_199_254_740_991  // 2^53 - 1

/// Fixed whitespace set (Q64), identical to the server's and the Android SDK's: U+0009..U+000D, U+0020, U+0085,
/// U+00A0, U+1680, U+2000..U+200A, U+2028, U+2029, U+202F, U+205F, U+3000. Not `Character.isWhitespace` or
/// `CharacterSet.whitespaces`, which differ (for example U+001C..U+001F, or U+200B in some versions).
internal func isNameWhitespace(_ scalar: Unicode.Scalar) -> Bool {
  switch scalar.value {
  case 0x09...0x0D, 0x20, 0x85, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: true
  default: false
  }
}

/// Validates a name, property key (`isName`), or string value. Names and keys: non-empty, no leading or trailing
/// whitespace; no trimming (Q64) and no Unicode normalization (Q84). All text: no NUL (Q85). Lone surrogates, which
/// the Android check rejects, cannot exist in a Swift `String`. The check works on Unicode scalars, so a line break
/// pair like CR LF (one `Character`) is two whitespace scalars.
internal func checkText(_ text: String, isName: Bool) -> InvalidReason? {
  let scalars = text.unicodeScalars
  if isName {
    guard let first = scalars.first, let last = scalars.last, !scalars.allSatisfy(isNameWhitespace) else {
      return .emptyName
    }
    if isNameWhitespace(first) || isNameWhitespace(last) { return .leadingOrTrailingWhitespace }
  }
  if scalars.contains(where: { $0.value == 0 }) { return .forbiddenCharacter }
  return nil
}

private let timeoutMillisLimit = Int64(Int32.max)

/// Returns a message for the first problem, or `nil`. Messages never contain the collection key (Q115).
internal func validateConfig(_ config: SignalDockConfig) -> String? {
  if config.collectionKey.unicodeScalars.allSatisfy({ $0.properties.isWhitespace }) { return "collectionKey is blank" }
  guard let components = URLComponents(string: config.endpoint) else { return "endpoint is not a valid URI" }
  let scheme = components.scheme?.lowercased()
  guard let scheme, scheme == "http" || scheme == "https", !(components.host ?? "").isEmpty else {
    return "endpoint must be an absolute https URL with a host"
  }
  if scheme == "http" && !config.allowInsecureEndpoint {
    return "endpoint uses http; use https, or set allowInsecureEndpoint = true for development"
  }
  let timeouts = [
    ("callTimeout", config.callTimeout), ("connectTimeout", config.connectTimeout),
    ("readTimeout", config.readTimeout), ("writeTimeout", config.writeTimeout),
  ]
  for (name, seconds) in timeouts {
    // Whole milliseconds that fit in an Int32, like the Android SDK (its HTTP client takes Int milliseconds).
    let millis = seconds * 1000
    guard seconds.isFinite, millis >= 1, millis < Double(timeoutMillisLimit) + 1 else {
      return "\(name) must be positive, finite and at most \(timeoutMillisLimit) ms"
    }
  }
  return nil
}
