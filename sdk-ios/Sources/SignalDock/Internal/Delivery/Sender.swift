import Foundation

internal enum RunEnd: Sendable, Equatable {
  case queueEmpty
  /// The persisted next-attempt time has not come. The sender sends nothing before it.
  case waiting(untilMillis: Int64)
}

internal struct RunResult: Sendable, Equatable {
  internal let batches: Int
  internal let end: RunEnd
}

/// The one sender (ADR-0006 decisions 2 to 10, ADR-0047 D8). One batch is selected, sent, validated, and applied while
/// the lock is held; the lock is released between batches. An actor alone is not enough: its methods can interleave at
/// every `await`, and a second request must never start while one is in flight, so `locked` makes a batch one critical
/// section. No SQLite transaction is open during HTTP. New `track` writes never touch this lock.
///
/// Errors: `CancellationError` when the calling task is cancelled before or during a request (the queue and retry state
/// stay as they were, the same event IDs and bytes go out later), and `StorageError` from the queue.
internal actor Sender {
  private let queue: QueueStore
  private let transport: any BatchTransport
  private let clock: any MillisClock
  private let jitter: any Jitter
  private let logger: any SDKLogger
  private let notice: DeprecationNotice
  private let betweenReads: (@Sendable () async -> Void)?
  private let onLockWait: (@Sendable () -> Void)?

  private var locked = false
  private var lockWaiters: [(id: UInt64, continuation: CheckedContinuation<Void, any Error>)] = []
  private var nextWaiter: UInt64 = 0
  private nonisolated let inFlight = InFlightRequest()

  private enum Step {
    case empty
    case `continue`
    case acked
    case wait(untilMillis: Int64)
  }

  internal init(
    queue: QueueStore, transport: any BatchTransport, clock: any MillisClock, jitter: any Jitter,
    logger: any SDKLogger, notice: DeprecationNotice, betweenReads: (@Sendable () async -> Void)? = nil,
    onLockWait: (@Sendable () -> Void)? = nil
  ) {
    self.betweenReads = betweenReads
    self.onLockWait = onLockWait
    self.queue = queue
    self.transport = transport
    self.clock = clock
    self.jitter = jitter
    self.logger = logger
    self.notice = notice
  }

  /// Sends batches until the queue is empty or a wait applies. When the run applied a valid ACK, it then sends the
  /// client report once (ADR-0036).
  internal func run() async throws -> RunResult {
    var batches = 0
    var acked = false
    var end: RunEnd?
    while end == nil {
      try Task.checkCancellation()
      switch try await sendLocked() {
      case .empty: end = .queueEmpty
      case .wait(let at): end = .waiting(untilMillis: at)
      case .continue: batches += 1
      case .acked:
        batches += 1
        acked = true
      }
    }
    if acked { try await sendClientReport() }
    return RunResult(batches: batches, end: end ?? .queueEmpty)
  }

  /// One report per send cycle, after the event batches, when a counter is not zero. It never touches the retry
  /// state, so a failed report cannot delay events. 2xx or a permanent answer (404 from an old server, another 4xx)
  /// removes the sent counts; a transient failure keeps them for the next cycle. Only cancellation is thrown: a
  /// storage failure is logged, so the batch result still reaches the scheduler.
  private func sendClientReport() async throws {
    try await acquire()
    defer { release() }
    do {
      try Task.checkCancellation()
      guard let report = try buildClientReport(try await queue.droppedCounts()) else { return }
      let request = BatchHttpRequest(contractVersion: contractVersion, body: report.body)
      let transport = transport
      let result = try await transmit { try await transport.sendReport(request) }
      if classifyReport(result) != .kept { try await queue.subtractDropped(report.rows) }
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      logger.log(.clientReportFailed)
    }
  }

  /// Cancels the request that is in flight, whichever drain started it (the expiration of a background task). The
  /// drain that owns it throws `CancellationError`; the queue and the retry state stay as they were.
  internal nonisolated func cancelInFlight() { inFlight.cancel() }

  private func sendLocked() async throws -> Step {
    try await acquire()
    defer { release() }
    try Task.checkCancellation()
    return try await sendOne()
  }

  /// A waiter that is cancelled leaves the line and throws; it never owns the lock then.
  private func acquire() async throws {
    if !locked {
      locked = true
      return
    }
    let id = nextWaiter
    nextWaiter += 1
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, any Error>) in
        if Task.isCancelled {
          c.resume(throwing: CancellationError())
          return
        }
        lockWaiters.append((id, c))  // the lock is handed over on release, `locked` stays true
        onLockWait?()  // a test seam: the caller is registered
      }
    } onCancel: {
      Task { await self.cancelWaiter(id) }
    }
  }

  private func cancelWaiter(_ id: UInt64) {
    guard let index = lockWaiters.firstIndex(where: { $0.id == id }) else { return }
    lockWaiters.remove(at: index).continuation.resume(throwing: CancellationError())
  }

  private func release() {
    if lockWaiters.isEmpty {
      locked = false
    } else {
      lockWaiters.removeFirst().continuation.resume()
    }
  }

  private func sendOne() async throws -> Step {
    guard let head = try await queue.oldest(limit: 1).first else { return .empty }  // empty queue: no HTTP
    let now = clock.nowMillis()
    let stored = try await queue.retryState() ?? RetryStateRow()
    let state = try await clampToWait(stored, now)
    if let at = state.nextAttemptAtMillis, now < at { return .wait(untilMillis: at) }

    // Batches are grouped by stored contract version (Q66); the request also carries one installationId.
    let cap = min(maxBatchEvents, state.maxBatchEvents ?? maxBatchEvents)
    await betweenReads?()  // a test seam: `clearQueue()` can run between the two reads
    let rows = try await queue.oldestOfGroup(
      contractVersion: head.contractVersion, installationId: head.installationId, limit: cap)
    if rows.isEmpty { return .continue }  // the queue was cleared meanwhile: look again
    let batch = try buildBatch(rows: rows, maxEvents: cap)

    logger.log(.deliveryAttemptStarted)
    // A cancellation here leaves the queue untouched; the same IDs and bytes are sent again later.
    let request = BatchHttpRequest(contractVersion: batch.contractVersion, body: batch.body)
    let transport = transport
    let result = try await transmit { try await transport.send(request) }
    let after = clock.nowMillis()
    logger.log(.deliveryAttemptFinished)
    switch result {
    case .failed(let kind): return try await fail(state, after, kind, status: nil, retryAfter: nil)
    case .http(let http): return try await onHttp(state, batch, http, after)
    }
  }

  /// Runs one request as a task that `cancelInFlight` can reach. Caller cancellation cancels the request.
  private func transmit(_ call: @escaping @Sendable () async throws -> TransportResult) async throws
    -> TransportResult
  {
    let task = Task<TransportResult, any Error> { try await call() }
    inFlight.set(task)
    defer { inFlight.set(nil) }
    do {
      return try await withTaskCancellationHandler {
        try await task.value
      } onCancel: {
        task.cancel()
      }
    } catch {
      throw CancellationError()  // the transport throws nothing else
    }
  }

  /// A wall clock moved back would delay sending by the size of the jump. The stored time is cut to now + the stored
  /// wait and saved, so the next read does not slide it forward again (ADR-0025).
  private func clampToWait(_ state: RetryStateRow, _ now: Int64) async throws -> RetryStateRow {
    guard let at = state.nextAttemptAtMillis, let wait = state.waitMillis else { return state }
    let limit = satAdd(now, wait)
    if at <= limit { return state }
    var clamped = state
    clamped.nextAttemptAtMillis = limit
    try await queue.saveRetryState(clamped)
    return clamped
  }

  private func onHttp(_ state: RetryStateRow, _ batch: Batch, _ http: HttpResult, _ now: Int64) async throws -> Step {
    await notice.observe(deprecation: http.deprecation, sunset: http.sunset)  // headers only; never changes the result
    let retryAfter = parseRetryAfterMillis(http.retryAfter, nowMillis: now)
    let status = http.status
    switch status {
    case 200: return try await applyResponse(state, batch, http, now, retryAfter)
    case 413:
      if batch.events.count > 1 { return try await split(state, batch.events.count, retryAfter, now) }
      return try await hold(state, now, .payloadTooLarge, status, retryAfter, singleEvent: true)
    case 408: return try await fail(state, now, .timeout, status: status, retryAfter: retryAfter)
    case 429: return try await fail(state, now, .rateLimited, status: status, retryAfter: retryAfter)
    case 501, 505: return try await hold(state, now, .authOrConfiguration, status, retryAfter)
    case 500...599: return try await fail(state, now, .serverError, status: status, retryAfter: retryAfter)
    case 400: return try await hold(state, now, .contract, status, retryAfter)
    // 401, 403, other 4xx (404, 415 ...) and redirects (not followed): configuration or authentication.
    case 300...499: return try await hold(state, now, .authOrConfiguration, status, retryAfter)
    // 1xx or a 2xx other than 200: not a valid ACK.
    default: return try await fail(state, now, .invalidAck, status: status, retryAfter: retryAfter)
    }
  }

  private func applyResponse(
    _ state: RetryStateRow, _ batch: Batch, _ http: HttpResult, _ now: Int64, _ retryAfter: Int64?
  ) async throws -> Step {
    let ids = batch.events.map(\.eventId)
    guard let items = validateAck(requestEventIds: ids, body: http.body) else {
      // Applies nothing (Q59). A valid Retry-After applies to every row (ADR-0006 decision 5).
      return try await fail(state, now, .invalidAck, status: http.status, retryAfter: retryAfter)
    }
    let rejected = items.filter { $0.status == .rejected }
    let rejections = rejected.map {
      RejectionRow(eventId: ids[$0.index], code: Self.code(of: $0), rejectedAtMillis: now)
    }
    var next = state
    next.failureCount = 0
    next.nextAttemptAtMillis = nil
    next.waitMillis = nil
    next.hold = false
    // After each valid ACK the cap doubles; it is cleared when it reaches 50 (decision 6).
    next.maxBatchEvents = state.maxBatchEvents.flatMap { $0 * 2 < maxBatchEvents ? $0 * 2 : nil }
    next.lastAckAtMillis = now
    next.lastAckAccepted = items.filter { $0.status == .accepted }.count
    next.lastAckDuplicate = items.filter { $0.status == .duplicate }.count
    next.lastAckRejected = rejected.count
    // One transaction: delete settled events, store rejection diagnostics, reset retry state (decision 7).
    try await queue.applyAck(removeIds: ids, rejections: rejections, state: next)
    if state.hold { logger.log(.deliveryResumed) }
    return .acked
  }

  /// Temporary failure: persist `failureCount + 1` and one Full Jitter draw, once per failure (Q74).
  private func fail(
    _ state: RetryStateRow, _ now: Int64, _ kind: DeliveryErrorKind, status: Int?, retryAfter: Int64?
  ) async throws -> Step {
    let count = state.failureCount == Int.max ? Int.max : state.failureCount + 1
    let bound = backoffBase(failureCount: count)
    let draw = min(max(jitter.nextMillis(boundInclusive: bound), 0), bound)
    let wait = max(draw, retryAfter ?? 0, state.hold ? holdRecheckMillis : 0)
    let at = satAdd(now, wait)
    var next = withError(state, kind, status, now)
    next.failureCount = count
    next.nextAttemptAtMillis = at
    next.waitMillis = wait
    try await queue.saveRetryState(next)
    logger.log(.deliveryFailed)
    return .wait(untilMillis: at)
  }

  /// Authentication, configuration, or contract error (or a single oversized event): stop normal sending.
  private func hold(
    _ state: RetryStateRow, _ now: Int64, _ kind: DeliveryErrorKind, _ status: Int, _ retryAfter: Int64?,
    singleEvent: Bool = false
  ) async throws -> Step {
    let wait = max(holdRecheckMillis, retryAfter ?? 0)
    let at = satAdd(now, wait)
    var next = withError(state, kind, status, now)
    next.hold = true
    next.nextAttemptAtMillis = at
    next.waitMillis = wait
    if singleEvent { next.maxBatchEvents = 1 }
    try await queue.saveRetryState(next)
    if !state.hold { logger.log(.deliveryPaused) }
    return .wait(untilMillis: at)
  }

  /// 413 for a multi-event batch: smaller batches, same IDs, no earlier retry than a valid Retry-After.
  private func split(_ state: RetryStateRow, _ size: Int, _ retryAfter: Int64?, _ now: Int64) async throws -> Step {
    let at = retryAfter.map { satAdd(now, $0) }
    var next = withError(state, .payloadTooLarge, 413, now)
    next.maxBatchEvents = max(1, size / 2)
    next.nextAttemptAtMillis = at
    next.waitMillis = retryAfter
    try await queue.saveRetryState(next)
    return at.map { .wait(untilMillis: $0) } ?? .continue
  }

  private func withError(_ state: RetryStateRow, _ kind: DeliveryErrorKind, _ status: Int?, _ now: Int64)
    -> RetryStateRow
  {
    var next = state
    next.lastErrorKind = kind.rawValue
    next.lastErrorHttpStatus = status
    next.lastErrorAtMillis = now
    return next
  }

  private static let maxCodeLength = 64

  private static func code(of item: ItemAck) -> String {
    let code = item.code.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
    return String((code ?? "UNSPECIFIED").prefix(maxCodeLength))
  }
}

/// The task of the request in flight. `@unchecked Sendable`: `task` is read and written only under `lock`.
private final class InFlightRequest: @unchecked Sendable {
  private let lock = NSLock()
  private var task: Task<TransportResult, any Error>?

  func set(_ task: Task<TransportResult, any Error>?) {
    lock.lock()
    defer { lock.unlock() }
    self.task = task
  }

  func cancel() {
    lock.lock()
    let current = task
    lock.unlock()
    current?.cancel()
  }
}
