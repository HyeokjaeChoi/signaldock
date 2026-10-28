import Foundation
import Testing
import UIKit

@testable import SignalDock

/// Counts the drains of a trigger and lets a test hold one back. `drain()` is what a trigger runs.
actor DrainProbe {
  private(set) var started = 0
  private(set) var finished = 0
  private var gates: [Int: Gate] = [:]
  private var watchers: [(Int, CheckedContinuation<Void, Never>)] = []

  /// Holds the `n`th drain (1-based) until the returned gate opens.
  func hold(_ n: Int) -> Gate {
    let gate = Gate()
    gates[n] = gate
    return gate
  }

  func drain() async {
    started += 1
    wake()
    if let gate = gates[started] { await gate.wait() }
    finished += 1
    wake()
  }

  /// Returns once `finished >= count`.
  func waitFinished(_ count: Int) async {
    if finished >= count { return }
    await withCheckedContinuation { watchers.append((count, $0)) }
  }

  /// Returns once `started >= count`.
  func waitStarted(_ count: Int) async {
    if started >= count { return }
    await withCheckedContinuation { watchers.append((-count, $0)) }
  }

  private func wake() {
    let ready = watchers.filter { $0.0 >= 0 ? finished >= $0.0 : started >= -$0.0 }
    watchers.removeAll { $0.0 >= 0 ? finished >= $0.0 : started >= -$0.0 }
    for watcher in ready { watcher.1.resume() }
  }
}

/// The foreground trigger, the background drain, and the lifecycle observer (ADR-0047 D8; Android `SchedulingTest`).
/// No test sleeps: the timer and the background task are fakes that the test drives.
@Suite struct TriggerTests {
  private func trigger(
    threshold: Int = 20, timer: ManualTimer = ManualTimer(), probe: DrainProbe = DrainProbe(),
    clock: TestClock = TestClock(), waiting: Int64? = nil
  ) -> DeliveryTrigger {
    DeliveryTrigger(eventThreshold: threshold, delayMillis: 10_000, timer: timer, clock: clock) {
      await probe.drain()
      return waiting
    }
  }

  // MARK: Foreground trigger

  @Test func twentyStoredEventsStartADrainAtOnce() async {
    let probe = DrainProbe()
    let t = trigger(probe: probe)
    for _ in 1..<20 { await t.onEventStored() }
    #expect(await probe.started == 0)
    await t.onEventStored()
    await probe.waitFinished(1)
    #expect(await probe.started == 1)
  }

  @Test func tenSecondsAfterTheFirstStoredEventADrainStarts() async {
    let probe = DrainProbe()
    let timer = ManualTimer()
    let t = trigger(timer: timer, probe: probe)
    await t.onEventStored()
    await t.onEventStored()
    await timer.waitUntilSleeping()
    #expect(await timer.requestedMillis == [10_000], "one timer for the first event, not one per event")
    #expect(await probe.started == 0)
    await timer.fire()
    await probe.waitFinished(1)
    #expect(await probe.started == 1)
  }

  @Test func theCountStartsAgainAfterADrainStarted() async {
    let probe = DrainProbe()
    let timer = ManualTimer()
    let t = trigger(threshold: 3, timer: timer, probe: probe)
    await t.onEventStored()
    await t.onEventStored()
    await timer.waitUntilSleeping()
    await timer.fire()
    await probe.waitFinished(1)
    await t.onEventStored()  // the first event after the drain: one, not three
    await t.onEventStored()
    #expect(await probe.started == 1)
    await timer.waitUntilSleeping()  // a new timer for it
    #expect(await timer.requestedMillis == [10_000, 10_000])
  }

  @Test func theThresholdCancelsTheTimerSoItStartsNoSecondDrain() async {
    let probe = DrainProbe()
    let timer = ManualTimer()
    let t = trigger(threshold: 2, timer: timer, probe: probe)
    await t.onEventStored()
    await timer.waitUntilSleeping()
    await t.onEventStored()  // the second event: threshold
    await probe.waitFinished(1)
    await timer.waitUntilIdle()  // the sleeping timer was cancelled
    await timer.fire()
    await t.requestDrain()  // a barrier: a stale timer fire would have counted before this drain
    await probe.waitFinished(2)
    #expect(await probe.started == 2)
  }

  @Test func oneDrainRunsAtATimeAndRequestsDuringADrainCauseExactlyOneMore() async {
    let probe = DrainProbe()
    let t = trigger(probe: probe)
    let first = await probe.hold(1)
    await t.requestDrain()
    await probe.waitStarted(1)
    await t.requestDrain()
    await t.requestDrain()
    await t.requestDrain()
    #expect(await probe.started == 1, "no second drain runs at the same time")
    await first.open()
    await probe.waitFinished(2)
    #expect(await probe.started == 2)
    await t.requestDrain()  // idle again: a new request starts a new drain
    await probe.waitFinished(3)
    #expect(await probe.started == 3)
  }

  @Test func aRequestAfterTheLastDrainFinishedStartsAFreshOne() async {
    let probe = DrainProbe()
    let t = trigger(probe: probe)
    await t.requestDrain()
    await probe.waitFinished(1)
    await t.requestDrain()
    await probe.waitFinished(2)
    #expect(await probe.started == 2)
  }

  @Test func aTriggerRespectsThePersistedRetryWait() async throws {
    let rig = try await SenderRig.make(replies: [http(503), .ack()], jitter: [30_000])
    let probe = DrainProbe()
    let sender = rig.sender
    let t = DeliveryTrigger(eventThreshold: 1, delayMillis: 10_000, timer: ManualTimer(), clock: rig.clock) {
      _ = try? await sender.run()
      await probe.drain()
      return nil
    }
    await t.onEventStored()
    await probe.waitFinished(1)
    #expect(await rig.transport.requests.count == 1)
    await t.onEventStored()  // the trigger starts a drain, but the sender refuses to send before the stored time
    await probe.waitFinished(2)
    #expect(await rig.transport.requests.count == 1)
    rig.clock.advance(by: 10_000)
    await t.onEventStored()
    await probe.waitFinished(3)
    #expect(await rig.transport.requests.count == 2)
    #expect(try await rig.queuedIds.isEmpty)
  }

  // MARK: Retry timer (owner decision 2026-10-28: the Android `requestAt`, in the foreground)

  @Test func aDrainThatEndsWithAWaitArmsTheTimerOnceForThatTimeAndTheFireDrainsAgain() async {
    let probe = DrainProbe()
    let timer = ManualTimer()
    let clock = TestClock(1_000_000)
    let t = trigger(timer: timer, probe: probe, clock: clock, waiting: 1_007_500)
    await t.requestDrain()
    await probe.waitFinished(1)
    await timer.waitUntilSleeping()
    #expect(await timer.requestedMillis == [7500], "the delay is the stored time minus now")
    clock.set(1_007_500)
    await timer.fire()
    await probe.waitFinished(2)  // the drain of the fire; it ends with the same wait and arms again
    #expect(await probe.started == 2)
  }

  @Test func theNextDrainReplacesTheArmedTimer() async {
    let probe = DrainProbe()
    let timer = ManualTimer()
    let t = trigger(timer: timer, probe: probe, waiting: TestClock().nowMillis() + 60_000)
    await t.requestDrain()
    await probe.waitFinished(1)
    await timer.waitUntilSleeping()
    await t.requestDrain()
    await probe.waitFinished(2)
    await timer.waitUntilSleeping()
    #expect(await timer.requestedMillis == [60_000, 60_000])
    await timer.fire()  // only the second timer is left; the first was cancelled
    await probe.waitFinished(3)
    #expect(await probe.started == 3)
  }

  @Test func aWaitThatHasPassedFiresWithoutDelay() async {
    let probe = DrainProbe()
    let timer = ManualTimer()
    let clock = TestClock(5_000)
    let t = trigger(timer: timer, probe: probe, clock: clock, waiting: 1_000)
    await t.requestDrain()
    await probe.waitFinished(1)
    await timer.waitUntilSleeping()
    #expect(await timer.requestedMillis == [0])
  }

  @Test func noTimerRunsInTheBackgroundAndNoneIsArmedWithoutAWait() async {
    let probe = DrainProbe()
    let timer = ManualTimer()
    let t = trigger(timer: timer, probe: probe, waiting: TestClock().nowMillis() + 5000)
    await t.requestDrain()
    await probe.waitFinished(1)
    await timer.waitUntilSleeping()
    t.setActive(false)  // the app went to the background: the timer is cancelled
    await timer.waitUntilIdle()
    await t.requestDrain()
    await probe.waitFinished(2)
    await t.waitUntilIdle()  // the drain has done its follow-up while the app was still inactive
    #expect(await timer.requestedMillis == [5000], "no timer is armed while the app is not active")
    t.setActive(true)
    await t.requestDrain()
    await probe.waitFinished(3)
    await timer.waitUntilSleeping()
    #expect(await timer.requestedMillis == [5000, 5000])
  }

  @Test func aTriggerThatStartsInactiveArmsNoRetryTimerAndAnInactiveTriggerIgnoresStoredEvents() async {
    let probe = DrainProbe()
    let timer = ManualTimer()
    let t = DeliveryTrigger(
      eventThreshold: 1, delayMillis: 10_000, timer: timer, clock: TestClock(), active: false
    ) {
      await probe.drain()
      return TestClock().nowMillis() + 5000
    }
    await t.onEventStored()  // threshold 1, but the app is not active: no drain, no timer
    await t.requestDrain()  // a barrier: the only drain is this one
    await probe.waitFinished(1)
    #expect(await probe.started == 1)
    #expect(await timer.requestedMillis.isEmpty, "a launch in the background arms no retry timer")
  }

  @Test func goingInactiveCancelsTheEventTimerAndItStartsNoDrain() async {
    let probe = DrainProbe()
    let timer = ManualTimer()
    let t = trigger(timer: timer, probe: probe)
    await t.onEventStored()
    await timer.waitUntilSleeping()
    t.setActive(false)
    await timer.waitUntilIdle()  // cancelled
    await timer.fire()
    await t.requestDrain()  // a barrier
    await probe.waitFinished(1)
    #expect(await probe.started == 1, "the event timer did not drain outside the background drain")
  }

  @Test func theTimerSleepDoesNotTrapOnAHugeDelay() async throws {
    let task = Task { try await TaskSleepTimer().sleep(milliseconds: Int64.max) }
    task.cancel()
    await #expect(throws: CancellationError.self) { try await task.value }
    let negative = Task { try await TaskSleepTimer().sleep(milliseconds: Int64.min) }
    _ = try await negative.value  // a negative delay is zero
  }

  @Test func aHugeWaitArmsTheTimerWithoutOverflow() async {
    let probe = DrainProbe()
    let timer = ManualTimer()
    let t = trigger(timer: timer, probe: probe, clock: TestClock(1000), waiting: Int64.max)
    await t.requestDrain()
    await probe.waitFinished(1)
    await timer.waitUntilSleeping()
    #expect(await timer.requestedMillis == [Int64.max - 1000])
  }

  @Test func expirationRunsTheExpireHookBeforeTheTaskEnds() async {
    let tasks = FakeBackgroundTasks()
    let probe = DrainProbe()
    let order = Order()
    let held = await probe.hold(1)
    let drainer = await MainActor.run {
      BackgroundDrainer(
        tasks: tasks, onExpire: { order.record(tasks.ended.isEmpty ? "expire first" : "expire after end") }
      ) { await probe.drain() }
    }
    await MainActor.run { drainer.enteredBackground() }
    await probe.waitStarted(1)
    await MainActor.run { tasks.expireLast() }
    #expect(order.events == ["expire first"] && tasks.ended == [1])
    await held.open()
    await (MainActor.run { drainer.lastTask })?.value
    #expect(tasks.ended == [1])
  }

  // MARK: Events stored

  @Test func aStoredEventNotifiesTheTriggerAndAFullQueueOrAFailureDoesNot() async throws {
    let probe = DrainProbe()
    let t = trigger(threshold: 1, probe: probe)
    let h = try await Harness.make(limits: QueueLimits(maxEvents: 1))
    let sink = NotifyingEventSink(inner: h.sdk.queue, trigger: t)
    #expect(try await sink.add(makeEvent(1), limits: QueueLimits(maxEvents: 1)) == .added)
    await probe.waitFinished(1)
    #expect(try await sink.add(makeEvent(2), limits: QueueLimits(maxEvents: 1)) == .queueFull)
    await #expect(throws: StorageError.self) {
      try await NotifyingEventSink(inner: ThrowingSink(error: StorageError(.closed)), trigger: t).add(
        makeEvent(3), limits: QueueLimits())
    }
    await t.requestDrain()  // a barrier
    await probe.waitFinished(2)
    #expect(await probe.started == 2, "only the stored event and the barrier started drains")
  }

  // MARK: Background drain

  @Test func enteringTheBackgroundStartsTheTaskBeforeTheWorkAndEndsItOnceWhenTheWorkIsDone() async throws {
    let tasks = FakeBackgroundTasks()
    let order = Order()
    let done = Gate()
    let drainer = await MainActor.run {
      BackgroundDrainer(tasks: tasks) {
        order.record(tasks.begun.isEmpty ? "work before begin" : "work")
        await done.open()
      }
    }
    await MainActor.run { drainer.enteredBackground() }
    await done.wait()
    await tasks.waitEnded(1)
    #expect(order.events == ["work"], "the task began before the work")
    #expect(tasks.begun == [1] && tasks.ended == [1])
  }

  @Test func expirationCancelsTheRequestEndsTheTaskOnceAndLosesNothing() async throws {
    let tasks = FakeBackgroundTasks()
    let entered = Gate()
    let finished = Gate()
    let rig = try await SenderRig.make(replies: [.hold(entered: entered)])
    let ids = try await rig.queuedIds
    let sender = rig.sender
    let logger = rig.logger
    let drainer = await MainActor.run {
      BackgroundDrainer(tasks: tasks) {
        await DeliveryCoordinator.drain(sender, logger)
        await finished.open()
      }
    }
    await MainActor.run { drainer.enteredBackground() }
    await entered.wait()  // the request is in flight
    #expect(tasks.ended.isEmpty)
    await MainActor.run { tasks.expireLast() }  // the system is about to end the task
    #expect(tasks.ended == [1], "the expiration handler ends the task before it returns")
    await finished.wait()  // the cancelled request returned
    await (MainActor.run { drainer.lastTask })?.value  // and the end path of the drainer has run
    #expect(tasks.ended == [1], "ended once, not again when the work finishes")
    #expect(try await rig.queuedIds == ids)
    #expect(try await rig.queue.retryState() == nil, "no failure is counted for a cancelled request")
    #expect(rig.logger.warnings.isEmpty)
  }

  @Test func eachEntryIntoTheBackgroundGetsItsOwnTask() async throws {
    let tasks = FakeBackgroundTasks()
    let probe = DrainProbe()
    let drainer = await MainActor.run { BackgroundDrainer(tasks: tasks) { await probe.drain() } }
    await MainActor.run { drainer.enteredBackground() }
    await probe.waitFinished(1)
    await tasks.waitEnded(1)
    await MainActor.run { drainer.enteredBackground() }
    await probe.waitFinished(2)
    await tasks.waitEnded(2)
    #expect(tasks.begun == [1, 2] && tasks.ended == [1, 2])
  }

  @Test func aRefusedBackgroundTaskStillDrainsAndEndsNothing() async throws {
    let tasks = FakeBackgroundTasks()
    tasks.refuse = true
    let probe = DrainProbe()
    let drainer = await MainActor.run { BackgroundDrainer(tasks: tasks) { await probe.drain() } }
    await MainActor.run { drainer.enteredBackground() }
    await probe.waitFinished(1)
    await (MainActor.run { drainer.lastTask })?.value  // the end path has run
    #expect(tasks.begun.isEmpty && tasks.ended.isEmpty)
  }

  // MARK: Lifecycle notifications

  @Test @MainActor func theObserverCallsTheHandlersSynchronouslyOnTheNotifications() {
    let center = NotificationCenter()
    let calls = Order()
    let observer = LifecycleObserver(
      center: center, onForeground: { calls.record("foreground") }, onBackground: { calls.record("background") })
    center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
    #expect(calls.events == ["background"], "begin must run before the notification handler returns")
    center.post(name: UIApplication.willEnterForegroundNotification, object: nil)
    #expect(calls.events == ["background", "foreground"])
    center.post(name: UIApplication.didBecomeActiveNotification, object: nil)  // not a trigger
    #expect(calls.events.count == 2)
    withExtendedLifetime(observer) {}
  }
}

/// Records what happened, in order. `@unchecked Sendable`: `stored` is read and written only under `lock`.
final class Order: @unchecked Sendable {
  private let lock = NSLock()
  private var stored: [String] = []

  func record(_ event: String) {
    lock.lock()
    defer { lock.unlock() }
    stored.append(event)
  }

  var events: [String] {
    lock.lock()
    defer { lock.unlock() }
    return stored
  }
}
