import Foundation

/// How much the SDK writes to the unified log (`os.Logger`, subsystem `local.signaldock`). The SDK never logs event
/// content, event names, installation IDs, credentials, or HTTP bodies, at any level.
public enum LogLevel: Int, Sendable, Equatable, CaseIterable {
  /// Writes debug, warning, and error messages (initialization, send start and finish, final delivery failure kind).
  case debug

  /// Writes warning and error messages. The SDK has no info messages.
  case info

  /// Writes warning and error messages. This is the default of ``SignalDockConfig/logLevel``.
  case warn

  /// Writes error messages only.
  case error

  /// Writes nothing.
  case none

  /// `true` when a message of `level` is written at this configured level. A message level is `.debug`, `.warn`, or
  /// `.error`.
  internal func writes(_ level: LogLevel) -> Bool { self.rawValue <= level.rawValue }
}

/// The configuration of the SDK, fixed for its lifetime. Timeouts must be positive and finite.
/// ``collectionKey`` is left out of `description`.
public struct SignalDockConfig: Sendable, Equatable, CustomStringConvertible, CustomDebugStringConvertible,
  CustomReflectable
{
  /// Absolute `https` URL of the SignalDock server. The SDK does not change `http` to `https` and does not follow
  /// redirects. An `http` URL is rejected at initialization, unless ``allowInsecureEndpoint`` is `true`.
  public var endpoint: String

  /// The collection key of this project. It is write-only and not a secret that apps can keep from users.
  /// It must not be blank.
  public var collectionKey: String

  /// The log level. The default is ``LogLevel/warn``.
  public var logLevel: LogLevel

  /// Timeout of one whole HTTP call, in seconds. The default is 30.
  public var callTimeout: TimeInterval

  /// Timeout to connect, in seconds. The default is 10.
  public var connectTimeout: TimeInterval

  /// Timeout of one read, in seconds. The default is 10.
  public var readTimeout: TimeInterval

  /// Timeout of one write, in seconds. The default is 10.
  public var writeTimeout: TimeInterval

  /// `true` lets ``endpoint`` use `http`. The default is `false`: the SDK sends the collection key in every request,
  /// so a plain-text endpoint is for development only.
  public var allowInsecureEndpoint: Bool

  /// Creates a configuration. ``SignalDockSDK/initialize(_:)`` checks it.
  ///
  /// - Parameters:
  ///   - endpoint: absolute `https` URL of the server.
  ///   - collectionKey: the collection key of this project.
  ///   - logLevel: the log level.
  ///   - callTimeout: timeout of one whole HTTP call, in seconds.
  ///   - connectTimeout: timeout to connect, in seconds.
  ///   - readTimeout: timeout of one read, in seconds.
  ///   - writeTimeout: timeout of one write, in seconds.
  ///   - allowInsecureEndpoint: `true` lets `endpoint` use `http` (development only).
  public init(
    endpoint: String,
    collectionKey: String,
    logLevel: LogLevel = .warn,
    callTimeout: TimeInterval = 30,
    connectTimeout: TimeInterval = 10,
    readTimeout: TimeInterval = 10,
    writeTimeout: TimeInterval = 10,
    allowInsecureEndpoint: Bool = false
  ) {
    self.endpoint = endpoint
    self.collectionKey = collectionKey
    self.logLevel = logLevel
    self.callTimeout = callTimeout
    self.connectTimeout = connectTimeout
    self.readTimeout = readTimeout
    self.writeTimeout = writeTimeout
    self.allowInsecureEndpoint = allowInsecureEndpoint
  }

  /// A text without the collection key.
  public var description: String {
    "SignalDockConfig(endpoint=\(endpoint), collectionKey=***, logLevel=\(logLevel))"
  }

  /// The same text as ``description``, so `po` and `String(reflecting:)` do not show the collection key.
  public var debugDescription: String { description }

  /// A mirror with the collection key hidden, so `dump` does not show it.
  public var customMirror: Mirror {
    Mirror(
      self,
      children: [
        "endpoint": endpoint, "collectionKey": "***", "logLevel": logLevel, "callTimeout": callTimeout,
        "connectTimeout": connectTimeout, "readTimeout": readTimeout, "writeTimeout": writeTimeout,
        "allowInsecureEndpoint": allowInsecureEndpoint,
      ], displayStyle: .struct)
  }
}
