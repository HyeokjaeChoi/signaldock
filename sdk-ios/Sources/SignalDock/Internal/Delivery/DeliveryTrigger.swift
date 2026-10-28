import Foundation

internal let triggerEvents = 20
internal let triggerDelayMillis: Int64 = 10_000

/// The delay of the foreground trigger. A test supplies a timer that it fires by hand.
internal protocol TriggerTimer: Sendable {
  /// Returns after `milliseconds`, or throws `CancellationError` when the calling task is cancelled.
  func sleep(milliseconds: Int64) async throws
}

internal struct TaskSleepTimer: TriggerTimer {
  internal func sleep(milliseconds: Int64) async throws {
    // A wait can be huge (a Retry-After is never capped). The multiplication must not trap, and a timer longer than a
    // day is pointless: the next drain arms a new one.
    let capped = UInt64(min(max(milliseconds, 0), maxTimerMillis))
    try await Task.sleep(nanoseconds: capped * 1_000_000)
  }
}

internal let maxTimerMillis: Int64 = 24 * 3_600_000

/// Whether the app is active. Read and written without `await`, so the background entry can switch it off before the
/// background drain starts. `@unchecked Sendable`: `value` is read and written only under `lock`.
internal final class ActiveFlag: @unchecked Sendable {
  private let lock = NSLock()
  private var value: Bool

  internal init(_ value: Bool) { self.value = value }

  internal var isActive: Bool {
    get {
      lock.lock()
      defer { lock.unlock() }
      return value
    }
    set {
      lock.lock()
      defer { lock.unlock() }
      value = newValue
    }
  }
}

/// Foreground trigger (ADR-0006 decision 11, ADR-0047 D8): a drain starts after `eventThreshold` events were stored, or
/// `delayMillis` after the first event stored since the last drain started. Retry waits are not checked here; the
/// sender refuses to send before the persisted time. Only one drain runs at a time; a request during a drain makes it
/// run once more. Mirrors the Android `ForegroundTrigger`.
internal actor DeliveryTrigger {
  private enum DrainState { case idle, draining, drainingAgain }

  private let eventThreshold: Int
  private let delayMillis: Int64
  private let timer: any TriggerTimer
  private let clock: any MillisClock
  /// One drain; it returns the time at which it must run again when it ended with a wait.
  private let drain: @Sendable () async -> Int64?

  private nonisolated let flag: ActiveFlag
  private var retryTask: Task<Void, Never>?
  private var retryGeneration = 0
  private var stored = 0
  private var timerTask: Task<Void, Never>?
  private var timerGeneration = 0
  private var drainState = DrainState.idle
  private var idleWaiters: [CheckedContinuation<Void, Never>] = []

  /// - Parameter drain: one drain; it handles its own errors and returns the end of its wait, if it ended with one.
  internal init(
    eventThreshold: Int = triggerEvents, delayMillis: Int64 = triggerDelayMillis, timer: any TriggerTimer,
    clock: any MillisClock, active: Bool = true, drain: @escaping @Sendable () async -> Int64?
  ) {
    flag = ActiveFlag(active)
    self.clock = clock
    self.eventThreshold = eventThreshold
    self.delayMillis = delayMillis
    self.timer = timer
    self.drain = drain
  }

  internal func onEventStored() {
    // Not active: no timer and no drain here. The background drain or the next foreground return sends the event.
    guard flag.isActive else { return }
    stored += 1
    if stored >= eventThreshold {
      stored = 0
      cancelTimer()
      requestDrain()
    } else if timerTask == nil {
      timerGeneration += 1
      let generation = timerGeneration
      let timer = timer
      let delay = delayMillis
      timerTask = Task {
        do { try await timer.sleep(milliseconds: delay) } catch { return }
        timerFired(generation)
      }
    }
  }

  /// A fire that was overtaken by the threshold path (or by a newer timer) is ignored.
  private func timerFired(_ generation: Int) {
    guard generation == timerGeneration, timerTask != nil, flag.isActive else { return }
    stored = 0
    timerTask = nil
    requestDrain()
  }

  private func cancelTimer() {
    timerTask?.cancel()
    timerTask = nil
    timerGeneration += 1
  }

  /// Returns once no drain runs and none is requested, after the drain's own follow-up (the retry timer) is done. A
  /// test seam: the drain itself reports its end before that follow-up.
  internal func waitUntilIdle() async {
    if drainState == .idle { return }
    await withCheckedContinuation { idleWaiters.append($0) }
  }

  /// One drain at a time; a request during a drain makes it run once more.
  internal func requestDrain() {
    switch drainState {
    case .idle:
      drainState = .draining
      Task { await runDrains() }
    case .draining: drainState = .drainingAgain
    case .drainingAgain: break
    }
  }

  /// The app is in the foreground (`true`) or not. The flag changes at once, so a timer that fires in the window
  /// before the cancellation below does nothing. No timer runs in the background (owner decision, 2026-10-28).
  internal nonisolated func setActive(_ value: Bool) {
    flag.isActive = value
    if !value { Task { await cancelTimers() } }
  }

  private func cancelTimers() {
    guard !flag.isActive else { return }
    cancelRetry()
    cancelTimer()
    stored = 0
  }

  private func cancelRetry() {
    retryTask?.cancel()
    retryTask = nil
    retryGeneration += 1
  }

  /// A drain that ended with a wait arms the timer once for the persisted time (the Android `requestAt`). The next
  /// drain cancels it and arms a new one, so there is at most one.
  private func armRetry(until: Int64) {
    cancelRetry()
    let generation = retryGeneration
    let timer = timer
    let delay = max(0, until - clock.nowMillis())
    retryTask = Task {
      do { try await timer.sleep(milliseconds: delay) } catch { return }
      retryFired(generation)
    }
  }

  private func retryFired(_ generation: Int) {
    guard generation == retryGeneration, retryTask != nil, flag.isActive else { return }
    retryTask = nil
    requestDrain()
  }

  private func runDrains() async {
    while true {
      cancelRetry()
      let until = await drain()
      if flag.isActive, let until { armRetry(until: until) }
      if drainState == .draining {
        drainState = .idle
        let waiting = idleWaiters
        idleWaiters = []
        for waiter in waiting { waiter.resume() }
        return
      }
      drainState = .draining  // a request arrived during the drain: run once more
    }
  }
}

/// Tells the trigger about each stored event; the collector stays unaware (Android `NotifyingEventSink`).
internal struct NotifyingEventSink: EventSink {
  internal let inner: any EventSink
  internal let trigger: DeliveryTrigger

  internal func add(_ event: EventRow, limits: QueueLimits) async throws -> AddOutcome {
    let outcome = try await inner.add(event, limits: limits)
    if outcome == .added { await trigger.onEventStored() }
    return outcome
  }
}
