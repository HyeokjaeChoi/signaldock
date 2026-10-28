import Foundation

/// Wall clock in epoch milliseconds. A test supplies fixed values (ADR-0047 D13: injected clock).
internal protocol MillisClock: Sendable {
  func nowMillis() -> Int64
}

internal struct SystemMillisClock: MillisClock {
  internal func nowMillis() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }
}
