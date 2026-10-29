// Empty stand-in for the SDK public API that compatibility/consumer-ios and the generated tracking plan use. It has the
// same names and signatures (sdk-ios/api/SignalDock.swiftinterface) and does nothing, so the consumer app sources build
// unchanged and the baseline binary holds no SDK code. If the app uses more of the API, the baseline build fails to
// compile: add the missing signature here. scripts/measure-ios-sdk.sh puts this package at
// compatibility/consumer-ios/.sdk/SignalDock in a temporary copy.
import Foundation

public enum LogLevel: Sendable { case debug, info, warn, error, none }

public struct SignalDockConfig: Sendable {
  public init(
    endpoint: String, collectionKey: String, logLevel: LogLevel = .warn, callTimeout: TimeInterval = 30,
    connectTimeout: TimeInterval = 10, readTimeout: TimeInterval = 10, writeTimeout: TimeInterval = 10,
    allowInsecureEndpoint: Bool = false
  ) {}
}

public struct EventBuilder: Sendable {
  public mutating func property(_ key: String, _ value: String) {}
  public mutating func property(_ key: String, _ value: Bool) {}
  public mutating func property(_ key: String, _ value: Int) {}
  public mutating func property(_ key: String, _ value: Int64) {}
  public mutating func property(_ key: String, _ value: Double) {}
}

public enum InitError: Sendable { case none }
public enum TrackResult: Sendable { case success(eventId: String), queueFull }
public struct Diagnostics: Sendable {}

public enum InitResult: Sendable {
  case success(SignalDockSDK)
  case failure(InitError)
}

public final class SignalDockSDK: Sendable {
  public static let version = "baseline"
  public static func initialize(_ config: SignalDockConfig) async throws(CancellationError) -> InitResult {
    .success(SignalDockSDK())
  }
  public func track(
    isolation: isolated (any Actor)? = #isolation, _ name: String, _ build: (inout EventBuilder) -> Void = { _ in }
  ) async throws(CancellationError) -> TrackResult { .success(eventId: "") }
  public func diagnostics() async throws(CancellationError) -> Diagnostics { Diagnostics() }
}
