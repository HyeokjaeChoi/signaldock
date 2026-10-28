import Foundation

/// Shared initialization (Q113, Q114). Callers with the same config share one attempt. A caller's cancellation
/// cancels only its own wait: the attempt is an unstructured `Task`, and the waiters hold continuations, so a
/// cancelled waiter leaves while the attempt goes on. Mirrors the Android `InitCoordinator`.
@available(iOSApplicationExtension, unavailable)
internal actor InitCoordinator {
  internal typealias Build = @Sendable (SignalDockConfig) async throws -> SignalDockSDK

  /// The process-wide coordinator that `SignalDockSDK.initialize(_:)` uses.
  internal static let shared = InitCoordinator { try await SDKFactory.create(config: $0) }

  private let build: Build
  private var instance: SignalDockSDK?
  private var instanceConfig: SignalDockConfig?
  private var attemptConfig: SignalDockConfig?
  private var waiters: [UInt64: CheckedContinuation<InitResult, any Error>] = [:]
  private var nextWaiter: UInt64 = 0

  internal init(build: @escaping Build) { self.build = build }

  /// How many callers wait for the running attempt. Tests use it to know that a caller has registered.
  internal var waiterCount: Int { waiters.count }

  internal func initialize(_ config: SignalDockConfig) async throws(CancellationError) -> InitResult {
    if let instance {
      return config == instanceConfig ? .success(instance) : .failure(.configurationConflict)
    }
    if let attemptConfig {
      if config != attemptConfig { return .failure(.configurationConflict) }
    } else {
      if let message = validateConfig(config) { return .failure(.invalidConfiguration(message: message)) }
      attemptConfig = config
      let build = build
      // Not a child task: it must go on when every waiter has left (Q114). It does not hold this actor.
      Task.detached { [self] in
        let result = await Self.run(build, config)
        await finish(result, config)
      }
    }
    do {
      return try await wait()
    } catch {
      throw CancellationError()  // the only error `wait()` throws
    }
  }

  private func wait() async throws -> InitResult {
    let id = nextWaiter
    nextWaiter += 1
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<InitResult, any Error>) in
        // Checked after the registration point, in the same actor turn: a cancel before this line is seen here, a
        // cancel after it finds the waiter.
        if Task.isCancelled {
          continuation.resume(throwing: CancellationError())
        } else {
          waiters[id] = continuation
        }
      }
    } onCancel: {
      Task { await self.cancelWaiter(id) }
    }
  }

  private func cancelWaiter(_ id: UInt64) {
    waiters.removeValue(forKey: id)?.resume(throwing: CancellationError())
  }

  /// Any thrown error is an expected-style storage failure (ADR-0017). Fatal errors and crashes are not thrown, so
  /// they are not caught.
  private static func run(_ build: Build, _ config: SignalDockConfig) async -> InitResult {
    do {
      return .success(try await build(config))
    } catch {
      return .failure(.storageFailure(message: describe(error)))
    }
  }

  private func finish(_ result: InitResult, _ config: SignalDockConfig) {
    attemptConfig = nil
    if case .success(let sdk) = result {
      instance = sdk
      instanceConfig = config
    }
    let waiting = waiters
    waiters = [:]
    for continuation in waiting.values { continuation.resume(returning: result) }
  }
}
