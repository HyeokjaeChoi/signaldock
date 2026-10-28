/// The SDK instance. Get it from ``initialize(_:)``; call ``track(isolation:_:_:)`` only on a successful result.
/// One instance with one fixed configuration per process.
///
/// The public calls are ``track(isolation:_:_:)``, ``diagnostics()``, ``setOptOut(_:)``, ``isOptedOut()``,
/// ``clearQueue()``, and ``initialize(_:)``. Expected failures come back as result values; an error from the SDK's
/// storage does not reach the host (ADR-0017). Caller cancellation is the exception: the calls that can be cancelled
/// throw `CancellationError` and nothing else.
///
/// The type is for apps. App extensions are not supported in this release.
@available(iOSApplicationExtension, unavailable)
public final class SignalDockSDK: Sendable {
  /// The SDK release version, in `MAJOR.MINOR.PATCH` form. It is the one source of the iOS SDK version; the release
  /// workflow checks it against the `ios-v` tag.
  public static let version = "0.1.0"

  internal let collector: EventCollector
  internal let queue: QueueStore
  internal let logger: any SDKLogger
  internal let optOut: OptOutStore
  internal let notice: DeprecationNotice
  internal let config: SignalDockConfig

  internal init(
    collector: EventCollector, queue: QueueStore, logger: any SDKLogger, optOut: OptOutStore,
    notice: DeprecationNotice, config: SignalDockConfig
  ) {
    self.collector = collector
    self.queue = queue
    self.logger = logger
    self.optOut = optOut
    self.notice = notice
    self.config = config
  }

  /// Initializes the SDK. The same configuration shares one attempt and reuses a success. A different configuration
  /// gives ``InitError/configurationConflict``. A failed attempt is not repeated automatically; call again to retry.
  /// Cancelling the caller cancels only its own wait; the attempt goes on.
  ///
  /// Call it once from the app, for example in `application(_:didFinishLaunchingWithOptions:)`. A storage failure,
  /// for example before the first unlock after a restart, gives ``InitError/storageFailure(message:)`` and no instance;
  /// call again after the first unlock.
  ///
  /// - Parameter config: the configuration, fixed for the SDK lifetime.
  /// - Returns: ``InitResult/success(_:)`` with the instance, or ``InitResult/failure(_:)`` with the reason.
  /// - Throws: `CancellationError` when the calling task is cancelled while it waits.
  public static func initialize(_ config: SignalDockConfig) async throws(CancellationError) -> InitResult {
    try await InitCoordinator.shared.initialize(config)
  }

  /// Collects one event named `name`. Returns ``TrackResult/success(eventId:)`` after the event is committed to the
  /// queue. This is not server receipt. If the calling task is cancelled the call throws `CancellationError`; the
  /// event may already be stored, so do not collect the same action again on cancellation.
  ///
  /// Names and property keys must not be empty and must not start or end with whitespace. The SDK does not trim,
  /// normalize, or convert anything; it returns ``TrackResult/invalid(reason:field:)`` instead. The call needs no
  /// network. The SDK sends stored events later.
  ///
  /// - Parameters:
  ///   - isolation: the actor of the caller. Leave it out: the closure then runs on the caller's actor.
  ///   - name: the event name, chosen by the host app.
  ///   - build: adds flat string, number, and boolean properties to the event; see ``EventBuilder``.
  /// - Returns: the collection result. Handle every case; see ``TrackResult``.
  /// - Throws: `CancellationError` when the calling task is cancelled.
  public func track(
    isolation: isolated (any Actor)? = #isolation, _ name: String, _ build: (inout EventBuilder) -> Void = { _ in }
  ) async throws(CancellationError) -> TrackResult {
    try await collector.track(name, isolation: isolation, build)
  }

  /// Turns collection off or on. While `optedOut` is `true`, ``track(isolation:_:_:)`` stores nothing and returns
  /// ``TrackResult/optedOut``. The state is kept across app restarts, in SDK storage that backups do not restore.
  /// The SDK starts with collection on.
  ///
  /// Events already in the queue stay, and the SDK still sends them. To delete them, call ``clearQueue()``. A `track`
  /// call that started before this call returned can still store its event.
  ///
  /// The change applies at once in this process. The return value says whether it was also stored for the next
  /// start. Caller cancellation does not undo the change.
  ///
  /// - Parameter optedOut: `true` to stop collection, `false` to resume it.
  /// - Returns: `true` if the state was stored on disk; `false` if storage failed. The change still holds for this
  ///   process, and the SDK writes a warning.
  public func setOptOut(_ optedOut: Bool) async -> Bool {
    let stored = await optOut.set(optedOut)
    logger.log(stored ? .optOutSet : .optOutNotStored)
    return stored
  }

  /// `true` while collection is off because of ``setOptOut(_:)``.
  public func isOptedOut() -> Bool { optOut.isOptedOut }

  /// Deletes every queued event from the device. Use it with ``setOptOut(_:)`` when a user withdraws consent.
  /// Diagnostics about past rejections and ACKs stay; they hold no event content. A batch that the SDK is sending at
  /// this moment can still reach the server.
  ///
  /// - Returns: how many events were deleted, or `nil` if storage failed. A storage failure is not thrown.
  /// - Throws: `CancellationError` when the calling task is cancelled.
  public func clearQueue() async throws(CancellationError) -> Int? {
    if Task.isCancelled { throw CancellationError() }
    do {
      let deleted = try await queue.deleteAll()
      // Like Android's `withContext`: a caller cancelled during the call gets the cancellation, not the result.
      if Task.isCancelled { throw CancellationError() }
      return deleted
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      logger.log(.clearQueueStorageFailed)
      if Task.isCancelled { throw CancellationError() }
      return nil
    }
  }

  /// Read-only snapshot of the device queue, recent permanent rejections, ACK and retry state. It does not describe
  /// server state. A storage failure is reported as data, not as an error: ``Diagnostics/storageAvailable`` is then
  /// `false`.
  ///
  /// - Returns: one consistent snapshot of the queue state.
  /// - Throws: `CancellationError` when the calling task is cancelled.
  public func diagnostics() async throws(CancellationError) -> Diagnostics {
    if Task.isCancelled { throw CancellationError() }
    let deprecation = await notice.current()
    let snapshot: DiagnosticsSnapshot
    do {
      snapshot = try await queue.diagnosticsSnapshot()
      // Like Android's `withContext`: a caller cancelled during the call gets the cancellation, not the result.
      if Task.isCancelled { throw CancellationError() }
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      // Storage failure: reported as data, never as an error (ADR-0017). Caller cancellation still propagates.
      logger.log(.diagnosticsStorageFailed)
      if Task.isCancelled { throw CancellationError() }
      return Diagnostics(
        sdkVersion: Self.version, queuedEvents: 0, queuedBytes: 0, oldestPendingEpochMillis: nil,
        recentRejections: [], lastAck: nil, lastDeliveryError: nil, retryFailureCount: 0,
        nextAttemptEpochMillis: nil, storageAvailable: false, contractDeprecation: deprecation)
    }
    let retry = snapshot.retry
    return Diagnostics(
      sdkVersion: Self.version,
      queuedEvents: snapshot.usage.count,
      queuedBytes: snapshot.usage.bytes,
      oldestPendingEpochMillis: snapshot.usage.oldestCreatedAtMillis,
      recentRejections: snapshot.rejections.map {
        RejectionDiagnostic(eventId: $0.eventId, code: $0.code, rejectedAtEpochMillis: $0.rejectedAtMillis)
      },
      lastAck: retry?.lastAckAtMillis.map {
        AckDiagnostic(
          atEpochMillis: $0, accepted: retry?.lastAckAccepted ?? 0, duplicate: retry?.lastAckDuplicate ?? 0,
          rejected: retry?.lastAckRejected ?? 0)
      },
      lastDeliveryError: retry?.lastErrorAtMillis.flatMap { at in
        DeliveryErrorKind(rawValue: retry?.lastErrorKind ?? "").map {
          DeliveryErrorDiagnostic(kind: $0, httpStatus: retry?.lastErrorHttpStatus, atEpochMillis: at)
        }
      },
      retryFailureCount: retry?.failureCount ?? 0,
      nextAttemptEpochMillis: retry?.nextAttemptAtMillis,
      storageAvailable: true,
      contractDeprecation: deprecation)
  }
}
