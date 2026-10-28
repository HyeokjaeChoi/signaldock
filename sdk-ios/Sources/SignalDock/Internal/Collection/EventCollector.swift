import Foundation

/// `track()`: opt-out check, validation, size check, and one queue insert. Mirrors the Android `EventCollector`.
internal struct EventCollector: Sendable {
  internal let installationId: String
  internal let sink: any EventSink
  internal let clock: any MillisClock
  internal let ids: any IdGenerator
  internal let limits: QueueLimits
  internal let logger: any SDKLogger
  internal let optOut: OptOutStore

  /// Only caller cancellation is thrown (ADR-0017, spec section 3): before storage starts nothing is stored; after
  /// storage the event may be stored already.
  internal func track(
    _ name: String, isolation: isolated (any Actor)? = #isolation, _ build: (inout EventBuilder) -> Void
  ) async throws(CancellationError) -> TrackResult {
    // Before anything else: nothing is evaluated, stored, or sent while opted out (ADR-0034 decision 2).
    // The flag can change right after this read: a `track` that started before `setOptOut(true)` returned can still
    // store its event (ADR-0034 decision 8).
    if optOut.isOptedOut { return .optedOut }
    let now = clock.nowMillis()
    var builder = EventBuilder()
    build(&builder)
    if let reason = checkText(name, isName: true) { return .invalid(reason: reason, field: "name") }
    if let error = builder.error { return error }

    let eventId = ids.newId()
    let event = EventIngest(
      eventId: eventId, name: name, occurredAt: formatUtcMillis(now), properties: builder.properties,
      sdkVersion: SignalDockSDK.version, sdkPlatform: .ios, contractVersion: ._1)
    let payload: String
    let size: Int
    do {
      payload = String(decoding: try transportEncoder().encode(event), as: UTF8.self)
      size = try singleEventRequestBytes(installationId: installationId, event: event)
    } catch {
      return storageError("EncodingError")
    }
    if size > limits.maxEventBytes { return .invalid(reason: .eventTooLarge, field: nil) }

    // Cancelled before storage starts: nothing stored, no warning (spec section 3).
    if Task.isCancelled { throw CancellationError() }
    let row = EventRow(
      eventId: eventId, installationId: installationId, contractVersion: contractVersion, payloadJson: payload,
      sizeBytes: size, createdAtMillis: now)
    let outcome: AddOutcome
    do {
      outcome = try await sink.add(row, limits: limits)
    } catch {
      // Whatever the storage layer throws, the host never sees it (ADR-0017).
      logger.log(.trackStorageFailed)
      return .storageError(message: describe(error))
    }
    // The commit happened. A caller that was cancelled meanwhile gets the cancellation, not the result; the event
    // stays stored, so the host must not collect the same action again.
    if Task.isCancelled {
      logger.log(.trackCancelledDuringStorage)
      throw CancellationError()
    }
    switch outcome {
    case .added: return .success(eventId: eventId)
    case .queueFull: return .queueFull
    }
  }

  private func storageError(_ message: String) -> TrackResult {
    logger.log(.trackStorageFailed)
    return .storageError(message: message)
  }
}

/// A short, content-free name of a failure: codes for `StorageError`, the type name for anything else.
internal func describe(_ error: any Error) -> String {
  if let storage = error as? StorageError { return "StorageError(\(storage.kind.rawValue), \(storage.code))" }
  return String(describing: type(of: error))
}
