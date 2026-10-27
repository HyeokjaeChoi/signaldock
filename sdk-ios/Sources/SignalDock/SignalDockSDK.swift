/// Placeholder for the SignalDock iOS SDK (ADR-0047). Ticket 08 replaces it with the public API.
///
/// `version` is the one source of the iOS SDK version. The release workflow checks it against the `ios-v` tag.
@available(iOSApplicationExtension, unavailable)
public struct SignalDockSDK: Sendable {
  /// The SDK version, in `MAJOR.MINOR.PATCH` form.
  public static let version = "0.1.0"
}
