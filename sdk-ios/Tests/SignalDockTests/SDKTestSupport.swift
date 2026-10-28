import Foundation
import Testing

@testable import SignalDock

struct FixedClock: MillisClock {
  var millis: Int64 = 1_760_000_000_123

  func nowMillis() -> Int64 { millis }
}

/// `00000000-0000-4000-8000-000000000001`, `...002`, and so on. `@unchecked Sendable`: `counter` is read and
/// written only under `lock` (ADR-0047 amendment, ticket 08: test helpers).
final class SequenceIds: IdGenerator, @unchecked Sendable {
  private let lock = NSLock()
  private var counter = 0

  func newId() -> String {
    lock.lock()
    defer { lock.unlock() }
    counter += 1
    return String(format: "00000000-0000-4000-8000-%012d", counter)
  }

  var used: Int {
    lock.lock()
    defer { lock.unlock() }
    return counter
  }
}

/// A one-way latch: `wait()` suspends until `open()` is called. It does not look at cancellation, so a test can
/// cancel a task while it waits and still release it.
actor Gate {
  private var isOpen = false
  private var waiters: [CheckedContinuation<Void, Never>] = []

  func wait() async {
    if isOpen { return }
    await withCheckedContinuation { waiters.append($0) }
  }

  func open() {
    isOpen = true
    let waiting = waiters
    waiters = []
    for waiter in waiting { waiter.resume() }
  }
}

/// A sink that tells when a call arrived and holds it until released. It writes to the real queue.
struct GatedSink: EventSink {
  let inner: QueueStore
  let entered = Gate()
  let release = Gate()

  func add(_ event: EventRow, limits: QueueLimits) async throws -> AddOutcome {
    await entered.open()
    await release.wait()
    return try await inner.add(event, limits: limits)
  }
}

struct OtherError: Error {}

/// A sink that throws what the test says.
struct ThrowingSink: EventSink {
  let error: any Error

  func add(_ event: EventRow, limits: QueueLimits) async throws -> AddOutcome { throw error }
}

/// An SDK on a temporary directory, built by the real factory.
struct Harness {
  let dir: TempDirectory
  let sdk: SignalDockSDK
  let logger: RecordingLogger

  static let config = SignalDockConfig(
    endpoint: "http://localhost:8080", collectionKey: "test-key", allowInsecureEndpoint: true)

  static func make(
    dir: TempDirectory? = nil, limits: QueueLimits = QueueLimits(), ids: any IdGenerator = SequenceIds(),
    clock: FixedClock = FixedClock(), logger: RecordingLogger = RecordingLogger()
  ) async throws -> Harness {
    let dir = try dir ?? TempDirectory()
    let sdk = try await SDKFactory.create(
      config: config, base: dir.url, clock: clock, ids: ids, limits: limits, logger: logger)
    return Harness(dir: dir, sdk: sdk, logger: logger)
  }

  /// The SDK directory, `<temp>/signaldock`.
  var sdkDirectory: URL { dir.url.appendingPathComponent("signaldock", isDirectory: true) }

  /// The stored payloads, parsed, in insertion order.
  func storedEvents() async throws -> [[String: Any]] {
    try await sdk.queue.oldest(limit: 1_000).map {
      try #require(JSONSerialization.jsonObject(with: Data($0.payloadJson.utf8)) as? [String: Any])
    }
  }

  /// The same SDK with another sink in the collector (same queue, same opt-out state).
  func replacingSink(_ sink: any EventSink, limits: QueueLimits = QueueLimits()) -> SignalDockSDK {
    let old = sdk.collector
    return SignalDockSDK(
      collector: EventCollector(
        installationId: old.installationId, sink: sink, clock: old.clock, ids: old.ids, limits: limits,
        logger: old.logger, optOut: old.optOut),
      queue: sdk.queue, logger: sdk.logger, optOut: sdk.optOut, notice: sdk.notice, config: sdk.config)
  }
}

/// Runs `body` in a task that is cancelled before `body` starts, so the test needs no timing. `.failure` means the
/// body threw `CancellationError`.
func runCancelled<T: Sendable>(_ body: @escaping @Sendable () async throws -> T) async -> Result<T, CancellationError> {
  let start = Gate()
  let task = Task { () async -> Result<T, CancellationError> in
    await start.wait()
    do {
      return .success(try await body())
    } catch {
      if !(error is CancellationError) { Issue.record("expected only CancellationError, got \(error)") }
      return .failure(CancellationError())
    }
  }
  task.cancel()
  await start.open()
  return await task.value
}

extension InitResult {
  /// The error of a failure, or `nil` for a success.
  var error: InitError? {
    if case .failure(let error) = self { error } else { nil }
  }
}
