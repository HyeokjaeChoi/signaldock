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
    try await Task.sleep(nanoseconds: UInt64(milliseconds) * 1_000_000)
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
  private let drain: @Sendable () async -> Void

  private var stored = 0
  private var timerTask: Task<Void, Never>?
  private var timerGeneration = 0
  private var drainState = DrainState.idle

  /// - Parameter drain: one drain; it handles its own errors.
  internal init(
    eventThreshold: Int = triggerEvents, delayMillis: Int64 = triggerDelayMillis, timer: any TriggerTimer,
    drain: @escaping @Sendable () async -> Void
  ) {
    self.eventThreshold = eventThreshold
    self.delayMillis = delayMillis
    self.timer = timer
    self.drain = drain
  }

  internal func onEventStored() {
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
    guard generation == timerGeneration, timerTask != nil else { return }
    stored = 0
    timerTask = nil
    requestDrain()
  }

  private func cancelTimer() {
    timerTask?.cancel()
    timerTask = nil
    timerGeneration += 1
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

  private func runDrains() async {
    while true {
      await drain()
      if drainState == .draining {
        drainState = .idle
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
