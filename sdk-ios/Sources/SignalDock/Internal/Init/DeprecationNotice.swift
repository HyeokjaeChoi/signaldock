import Foundation

/// The contract deprecation notice of this process (ADR-0030), shown in `Diagnostics`. The sender records it from
/// the server's `Deprecation` and `Sunset` headers on every response. It keeps the latest valid value of each field,
/// writes one WARN per process, and never changes delivery. It is in memory only.
internal actor DeprecationNotice {
  private var notice: ContractDeprecation?
  private var warned = false
  private let logger: (any SDKLogger)?

  internal init(logger: (any SDKLogger)? = nil) { self.logger = logger }

  internal func current() -> ContractDeprecation? { notice }

  internal func record(_ deprecation: ContractDeprecation) { notice = deprecation }

  /// Reads the raw header values. An absent or invalid header is ignored; one valid header is enough.
  internal func observe(deprecation: String?, sunset: String?) {
    let deprecatedAt = parseDeprecationMillis(deprecation)
    let sunsetAt = sunset.flatMap { parseImfFixdateMillis($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
    if deprecatedAt == nil && sunsetAt == nil { return }
    notice = ContractDeprecation(
      deprecatedAtEpochMillis: deprecatedAt ?? notice?.deprecatedAtEpochMillis,
      sunsetAtEpochMillis: sunsetAt ?? notice?.sunsetAtEpochMillis)
    if !warned {
      warned = true
      logger?.log(.contractDeprecated)
    }
  }
}
