import Foundation
import Testing

@testable import SignalDock

/// A clock that a test moves by hand. `@unchecked Sendable`: `millis` is read and written only under `lock`
/// (ADR-0047 amendment, ticket 08: test helpers).
final class TestClock: MillisClock, @unchecked Sendable {
  private let lock = NSLock()
  private var millis: Int64

  init(_ millis: Int64 = 1_760_000_000_123) { self.millis = millis }

  func nowMillis() -> Int64 {
    lock.lock()
    defer { lock.unlock() }
    return millis
  }

  func set(_ value: Int64) {
    lock.lock()
    defer { lock.unlock() }
    millis = value
  }

  func advance(by delta: Int64) { set(nowMillis() + delta) }
}

/// The jitter draw of a test: returns the next scripted value and records the bounds it was asked for.
/// `@unchecked Sendable`: `draws` and `bounds` are read and written only under `lock`.
final class ScriptedJitter: Jitter, @unchecked Sendable {
  private let lock = NSLock()
  private var draws: [Int64]
  private var recorded: [Int64] = []

  /// `draws` are returned in order; when they run out, the draw is 0.
  init(_ draws: [Int64] = []) { self.draws = draws }

  func nextMillis(boundInclusive: Int64) -> Int64 {
    lock.lock()
    defer { lock.unlock() }
    recorded.append(boundInclusive)
    return draws.isEmpty ? 0 : draws.removeFirst()
  }

  var bounds: [Int64] {
    lock.lock()
    defer { lock.unlock() }
    return recorded
  }
}

/// Parks a task until it is cancelled. The wait does not use time.
actor CancellationLatch {
  private var continuation: CheckedContinuation<Void, Never>?
  private var cancelled = false

  func wait() async {
    await withTaskCancellationHandler {
      await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
        if cancelled { c.resume() } else { continuation = c }
      }
    } onCancel: {
      Task { await self.cancel() }
    }
  }

  private func cancel() {
    cancelled = true
    continuation?.resume()
    continuation = nil
  }
}

/// A transport that answers from a script and records every request. A reply that is not scripted is a network error.
actor FakeTransport: BatchTransport {
  enum Reply: Sendable {
    case result(TransportResult)
    /// A 200 with an ACK that settles every event with `status`.
    case ack(_ status: String = "accepted")
    /// A 200 with this exact body.
    case body(String)
    /// Tells the test that the request is in flight, then holds it until the task is cancelled.
    case hold(entered: Gate)
    /// The server of `FakeServer` stores the events and answers with a valid ACK.
    case server(FakeServer)
    /// The server stores the events, then the request hangs until the task is cancelled: the ACK is lost.
    case serverHold(FakeServer, entered: Gate)
    /// Tells the test that the request is in flight, waits for `release`, then answers with a valid ACK.
    case gated(entered: Gate, release: Gate)
  }

  private(set) var requests: [BatchHttpRequest] = []
  private(set) var maxInFlight = 0
  private var inFlight = 0
  private var script: [Reply]
  private let latch = CancellationLatch()

  init(_ script: [Reply] = []) { self.script = script }

  func enqueue(_ replies: Reply...) { script.append(contentsOf: replies) }

  func send(_ request: BatchHttpRequest) async throws(CancellationError) -> TransportResult {
    requests.append(request)
    inFlight += 1
    maxInFlight = max(maxInFlight, inFlight)
    defer { inFlight -= 1 }
    let reply = script.isEmpty ? Reply.result(.failed(.network)) : script.removeFirst()
    switch reply {
    case .result(let result): return result
    case .ack(let status): return ok(ackJson(ids: Self.eventIds(request), status: status))
    case .body(let body): return ok(body)
    case .server(let server): return ok(ackJson(ids: Self.eventIds(request), statuses: await server.store(request)))
    case .serverHold(let server, let entered):
      _ = await server.store(request)
      await entered.open()
      await latch.wait()
      throw CancellationError()
    case .gated(let entered, let release):
      await entered.open()
      await release.wait()
      return ok(ackJson(ids: Self.eventIds(request)))
    case .hold(let entered):
      await entered.open()
      await latch.wait()
      throw CancellationError()
    }
  }

  private func ok(_ body: String) -> TransportResult { .http(HttpResult(status: 200, body: Data(body.utf8))) }

  static func eventIds(_ request: BatchHttpRequest) -> [String] {
    events(request).compactMap { $0["eventId"] as? String }
  }

  static func events(_ request: BatchHttpRequest) -> [[String: Any]] {
    let root = try? JSONSerialization.jsonObject(with: request.body) as? [String: Any]
    return root?["events"] as? [[String: Any]] ?? []
  }
}

/// A server that remembers which event IDs it stored: a resend of a stored ID is a `duplicate` (Q69).
actor FakeServer {
  private(set) var stored: [String] = []
  private(set) var requests = 0

  func store(_ request: BatchHttpRequest) -> [String] {
    requests += 1
    return FakeTransport.eventIds(request).map { id in
      if stored.contains(id) { return "duplicate" }
      stored.append(id)
      return "accepted"
    }
  }
}

func ackJson(ids: [String], status: String = "accepted") -> String {
  ackJson(ids: ids, statuses: ids.map { _ in status })
}

func ackJson(ids: [String], statuses: [String]) -> String {
  let items = zip(ids.indices, zip(ids, statuses)).map { index, pair in
    #"{"index":\#(index),"status":"\#(pair.1)","eventId":"\#(pair.0)""#
      + (pair.1 == "rejected" ? #","code":"INVALID_FIELD","message":"bad""# : "") + "}"
  }
  return #"{"results":["# + items.joined(separator: ",") + "]}"
}

func http(
  _ status: Int, retryAfter: String? = nil, deprecation: String? = nil, sunset: String? = nil
) -> FakeTransport.Reply {
  .result(
    .http(HttpResult(status: status, body: nil, retryAfter: retryAfter, deprecation: deprecation, sunset: sunset)))
}

/// A queue, a sender, and the fakes around it.
struct SenderRig {
  let dir: TempDirectory
  let queue: QueueStore
  let transport: FakeTransport
  let clock: TestClock
  let jitter: ScriptedJitter
  let logger: RecordingLogger
  let notice: DeprecationNotice
  let sender: Sender

  static func make(
    replies: [FakeTransport.Reply] = [], jitter: [Int64] = [], events: Int = 3, dir: TempDirectory? = nil,
    clock: TestClock = TestClock(), transport: FakeTransport? = nil, betweenReads: (@Sendable () async -> Void)? = nil,
    onLockWait: (@Sendable () -> Void)? = nil
  ) async throws -> SenderRig {
    let dir = try dir ?? TempDirectory()
    let queue = try await QueueStore.open(directory: dir.url)
    return try await SenderRig(
      dir: dir, queue: queue, transport: transport ?? FakeTransport(replies), clock: clock,
      jitter: ScriptedJitter(jitter), events: events, betweenReads: betweenReads, onLockWait: onLockWait)
  }

  private init(
    dir: TempDirectory, queue: QueueStore, transport: FakeTransport, clock: TestClock, jitter: ScriptedJitter,
    events: Int, betweenReads: (@Sendable () async -> Void)?, onLockWait: (@Sendable () -> Void)?
  ) async throws {
    self.dir = dir
    self.queue = queue
    self.transport = transport
    self.clock = clock
    self.jitter = jitter
    logger = RecordingLogger()
    notice = DeprecationNotice(logger: logger)
    sender = Sender(
      queue: queue, transport: transport, clock: clock, jitter: jitter, logger: logger, notice: notice,
      betweenReads: betweenReads, onLockWait: onLockWait)
    for n in stride(from: 1, through: events, by: 1) {
      _ = try await queue.addWithinLimits(sendEvent(n, createdAt: clock.nowMillis()), limits: QueueLimits())
    }
  }

  /// A new sender on the same queue file and the same transport: a relaunch.
  func relaunched(transport: FakeTransport? = nil) async throws -> SenderRig {
    await queue.close()
    return try await SenderRig.make(events: 0, dir: dir, clock: clock, transport: transport ?? self.transport)
  }

  var queuedIds: [String] {
    get async throws { try await queue.oldest(limit: 100_000).map(\.eventId) }
  }

  var retry: RetryStateRow {
    get async throws { try await queue.retryState() ?? RetryStateRow() }
  }
}

/// A timer that a test fires by hand. A cancelled sleeper throws `CancellationError`, like `Task.sleep`.
actor ManualTimer: TriggerTimer {
  private var sleepers: [UInt64: CheckedContinuation<Void, any Error>] = [:]
  private var nextId: UInt64 = 0
  private var watchers: [(count: Int, continuation: CheckedContinuation<Void, Never>)] = []
  private(set) var requestedMillis: [Int64] = []

  func sleep(milliseconds: Int64) async throws {
    requestedMillis.append(milliseconds)
    let id = nextId
    nextId += 1
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, any Error>) in
        if Task.isCancelled {
          c.resume(throwing: CancellationError())
        } else {
          sleepers[id] = c
          wakeWatchers()
        }
      }
    } onCancel: {
      Task { await self.cancel(id) }
    }
  }

  private func cancel(_ id: UInt64) {
    sleepers.removeValue(forKey: id)?.resume(throwing: CancellationError())
    wakeIdleWatchers()
  }

  private var idleWatchers: [CheckedContinuation<Void, Never>] = []

  private func wakeIdleWatchers() {
    guard sleepers.isEmpty else { return }
    let waiting = idleWatchers
    idleWatchers = []
    for watcher in waiting { watcher.resume() }
  }

  /// Returns once no task sleeps on this timer (a cancelled sleeper leaves after its cancellation reaches the timer).
  func waitUntilIdle() async {
    if sleepers.isEmpty { return }
    await withCheckedContinuation { idleWatchers.append($0) }
  }

  private func wakeWatchers() {
    let ready = watchers.filter { sleepers.count >= $0.count }
    watchers.removeAll { sleepers.count >= $0.count }
    for watcher in ready { watcher.continuation.resume() }
  }

  /// Returns once at least `count` tasks sleep on this timer.
  func waitUntilSleeping(_ count: Int = 1) async {
    if sleepers.count >= count { return }
    await withCheckedContinuation { watchers.append((count, $0)) }
  }

  var sleeping: Int { sleepers.count }

  /// Ends every sleep normally: the delay has passed.
  func fire() {
    let all = sleepers
    sleepers = [:]
    for c in all.values { c.resume() }
    wakeIdleWatchers()
  }
}

/// The background task boundary of a test. `@unchecked Sendable`: the state is read and written only under `lock`.
final class FakeBackgroundTasks: BackgroundTasks, @unchecked Sendable {
  private let lock = NSLock()
  private var nextToken = 1
  private var begunTokens: [Int] = []
  private var endedTokens: [Int] = []
  private var handlers: [@MainActor @Sendable () -> Void] = []
  var refuse = false
  private var endWatchers: [(Int, CheckedContinuation<Void, Never>)] = []

  @MainActor func begin(expiration: @escaping @MainActor @Sendable () -> Void) -> Int? {
    lock.lock()
    defer { lock.unlock() }
    if refuse { return nil }
    let token = nextToken
    nextToken += 1
    begunTokens.append(token)
    handlers.append(expiration)
    return token
  }

  @MainActor func end(_ task: Int) {
    lock.lock()
    defer { lock.unlock() }
    endedTokens.append(task)
    let ready = endWatchers.filter { endedTokens.count >= $0.0 }
    endWatchers.removeAll { endedTokens.count >= $0.0 }
    for watcher in ready { watcher.1.resume() }
  }

  var begun: [Int] {
    lock.lock()
    defer { lock.unlock() }
    return begunTokens
  }

  /// Returns once at least `count` tasks were ended.
  func waitEnded(_ count: Int) async {
    await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
      lock.lock()
      defer { lock.unlock() }
      if endedTokens.count >= count {
        c.resume()
      } else {
        endWatchers.append((count, c))
      }
    }
  }

  var ended: [Int] {
    lock.lock()
    defer { lock.unlock() }
    return endedTokens
  }

  /// The system is about to end the last task: runs its expiration handler.
  @MainActor func expireLast() {
    lock.lock()
    let handler = handlers.last
    lock.unlock()
    handler?()
  }
}

extension DeliveryDependencies {
  /// Delivery that does nothing by itself: no launch drain, no lifecycle observer, no event threshold, and a timer
  /// that never fires. A test that wants a trigger builds its own.
  static var inert: DeliveryDependencies {
    var d = DeliveryDependencies()
    d.initialActive = { true }
    d.transport = FakeTransport()
    d.timer = ManualTimer()
    d.backgroundTasks = FakeBackgroundTasks()
    d.eventThreshold = Int.max
    d.observeLifecycle = false
    d.drainAtLaunch = false
    return d
  }
}

extension Result {
  var isFailure: Bool { if case .failure = self { true } else { false } }
}

/// A queue row whose payload JSON has its `eventId`, like a real stored event, so that a fake server can read the IDs
/// from the request body. With `payloadBytes` the payload is padded to exactly that many UTF-8 bytes.
func sendEvent(
  _ n: Int, installationId: String = "install-1", contractVersion: String = "1", payloadBytes: Int? = nil,
  createdAt: Int64? = nil
) -> EventRow {
  var row = makeEvent(n, installationId: installationId, contractVersion: contractVersion, createdAt: createdAt)
  let head = #"{"eventId":"\#(row.eventId)","#
  if let payloadBytes {
    let pad = payloadBytes - (head + #""p":""}"#).utf8.count
    row.payloadJson = head + #""p":""# + String(repeating: "x", count: pad) + #""}"#
  } else {
    row.payloadJson = head + #""n":\#(n)}"#
  }
  return row
}
