/// The contract deprecation notice of this process (ADR-0030), shown in `Diagnostics`. The delivery code (ticket 09)
/// records it when the server sends valid `Deprecation` or `Sunset` headers. It is in memory only.
internal actor DeprecationNotice {
  private var notice: ContractDeprecation?

  internal func current() -> ContractDeprecation? { notice }

  internal func record(_ deprecation: ContractDeprecation) { notice = deprecation }
}
