/// Result of ``SignalDockSDK/initialize(_:)``. Handle both cases.
@available(iOSApplicationExtension, unavailable)
public enum InitResult: Sendable {
  /// Initialization succeeded. Call ``SignalDockSDK/track(isolation:_:_:)`` on the instance.
  case success(SignalDockSDK)

  /// Initialization failed. The host may call ``SignalDockSDK/initialize(_:)`` again.
  case failure(InitError)
}

/// Why ``SignalDockSDK/initialize(_:)`` failed.
public enum InitError: Sendable, Equatable {
  /// The configuration is invalid. No SDK state was created. `message` says what is wrong; it never contains the
  /// collection key.
  case invalidConfiguration(message: String)

  /// A different configuration is active or initializing.
  case configurationConflict

  /// Storage could not be prepared, for example because the system does not allow the files before the first unlock
  /// after a restart. There is no instance. The host may call initialize again later. `message` holds codes only:
  /// it is no secret and no event content.
  case storageFailure(message: String)
}

/// Result of ``SignalDockSDK/track(isolation:_:_:)``. Handle every case.
public enum TrackResult: Sendable, Equatable {
  /// The event is durably stored in the queue. This is not server receipt. `eventId` is the SDK-generated UUIDv4 of
  /// the event; it stays the same on every resend.
  case success(eventId: String)

  /// Rejected input. Nothing was stored. `field` is `"name"` or the property key; it is `nil` for
  /// ``InvalidReason/eventTooLarge``. It is never logged.
  case invalid(reason: InvalidReason, field: String?)

  /// The host called ``SignalDockSDK/setOptOut(_:)`` with `true`. Nothing was validated, stored, or sent. This is not
  /// an error: handle it as "collection is off".
  case optedOut

  /// The queue is at its event or byte limit. Existing events are kept.
  case queueFull

  /// Storage failed. Nothing is known to be stored. The SDK catches storage failures; the host never sees a thrown
  /// error from storage. `message` holds codes only: it is no event content.
  case storageError(message: String)
}

/// The rule that ``TrackResult/invalid(reason:field:)`` reports. The SDK does not correct input.
public enum InvalidReason: Sendable, Equatable, CaseIterable {
  /// The name or property key is empty or has only whitespace characters.
  case emptyName

  /// The name or property key starts or ends with a whitespace character.
  case leadingOrTrailingWhitespace

  /// Text contains a NUL character. (A Swift `String` cannot hold a lone surrogate, so that case of the Android SDK
  /// cannot happen here.)
  case forbiddenCharacter

  /// A property key was used twice in one call, even with the same value. Keys that Swift treats as equal (for
  /// example `é` as one code point and as `e` with a combining accent) count as the same key.
  case duplicateKey

  /// A decimal value is NaN or infinite.
  case nonFiniteNumber

  /// An integer is outside plus or minus (2^53 - 1), also when it is passed as a `Double`.
  case numberOutOfRange

  /// The event is larger than 16 KiB of transport JSON.
  case eventTooLarge
}

/// One recent permanent rejection by the server. It holds no event content and no event name.
public struct RejectionDiagnostic: Sendable, Equatable {
  /// The ID of the rejected event.
  public let eventId: String

  /// The server's rejection code, cut to 64 characters; `UNSPECIFIED` if none was sent.
  public let code: String

  /// When the SDK recorded the rejection, in epoch milliseconds.
  public let rejectedAtEpochMillis: Int64
}

/// Counts of the last valid ACK. A recent ACK does not mean the whole queue was sent.
public struct AckDiagnostic: Sendable, Equatable {
  /// When the SDK applied the ACK, in epoch milliseconds.
  public let atEpochMillis: Int64

  /// How many events the server stored.
  public let accepted: Int

  /// How many events the server already had.
  public let duplicate: Int

  /// How many events the server rejected permanently.
  public let rejected: Int
}

/// Final delivery failure classes. The raw values are the names stored in the queue database (the Android names).
public enum DeliveryErrorKind: String, Sendable, Equatable, CaseIterable {
  /// A network error. The queue is kept and the SDK backs off.
  case network = "NETWORK"

  /// A call timeout or HTTP 408. The queue is kept and the SDK backs off.
  case timeout = "TIMEOUT"

  /// HTTP 429. The queue is kept and the SDK backs off.
  case rateLimited = "RATE_LIMITED"

  /// A temporary HTTP 5xx. The queue is kept and the SDK backs off.
  case serverError = "SERVER_ERROR"

  /// An authentication, configuration, or redirect problem. Normal sending pauses; one batch is tried every 30
  /// minutes.
  case authOrConfiguration = "AUTH_OR_CONFIGURATION"

  /// A contract error (HTTP 400). Normal sending pauses; one batch is tried every 30 minutes.
  case contract = "CONTRACT"

  /// HTTP 413 for a single event. Normal sending pauses; that request is tried every 30 minutes.
  case payloadTooLarge = "PAYLOAD_TOO_LARGE"

  /// The ACK was unreadable, incomplete, or contradictory. The SDK applies none of it and backs off.
  case invalidAck = "INVALID_ACK"
}

/// Last final delivery error. Content-free: a kind, an optional HTTP status, and a time.
public struct DeliveryErrorDiagnostic: Sendable, Equatable {
  /// The failure class.
  public let kind: DeliveryErrorKind

  /// The HTTP status, or `nil` when there was none (for example a network error).
  public let httpStatus: Int?

  /// When the SDK recorded the error, in epoch milliseconds.
  public let atEpochMillis: Int64
}

/// A server warning that the contract version in use will be retired (RFC 9745 `Deprecation`, RFC 8594 `Sunset`).
/// Delivery does not change because of it. Valid headers only; a field is `nil` when its header was absent or invalid.
public struct ContractDeprecation: Sendable, Equatable {
  /// The `Deprecation` date in epoch milliseconds.
  public let deprecatedAtEpochMillis: Int64?

  /// The `Sunset` date in epoch milliseconds.
  public let sunsetAtEpochMillis: Int64?
}

/// Read-only snapshot of the device queue. It says nothing about server state. ``storageAvailable`` is `false` when
/// the queue could not be read; the other fields are then empty and mean nothing.
public struct Diagnostics: Sendable, Equatable {
  /// The SDK release version, for example `0.1.0`. It is not the contract version.
  public let sdkVersion: String

  /// How many events wait in the queue.
  public let queuedEvents: Int

  /// The total transport JSON bytes of the queued events.
  public let queuedBytes: Int64

  /// When the oldest queued event was collected, or `nil` if the queue is empty.
  public let oldestPendingEpochMillis: Int64?

  /// The most recent permanent rejections, at most 100.
  public let recentRejections: [RejectionDiagnostic]

  /// The last valid ACK, or `nil` if there was none.
  public let lastAck: AckDiagnostic?

  /// The last final delivery error, or `nil` if there was none. It stays as history after recovery.
  public let lastDeliveryError: DeliveryErrorDiagnostic?

  /// The count of temporary failures since the last valid ACK.
  public let retryFailureCount: Int

  /// The earliest time of the next send, or `nil` if none is waiting.
  public let nextAttemptEpochMillis: Int64?

  /// `false` when the queue could not be read.
  public let storageAvailable: Bool

  /// The server told this process that the contract version in use is deprecated, or `nil` if it did not. It lives in
  /// memory for the process; it is not stored. Informational only: delivery does not change.
  public let contractDeprecation: ContractDeprecation?
}
