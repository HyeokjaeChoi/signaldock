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
    threshold: Int = 20, timer: ManualTimer = ManualTimer(), probe: DrainProbe = DrainProbe()
  ) -> DeliveryTrigger {
    DeliveryTrigger(eventThreshold: threshold, delayMillis: 10_000, timer: timer) { await probe.drain() }
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
    let t = DeliveryTrigger(eventThreshold: 1, delayMillis: 10_000, timer: ManualTimer()) {
      _ = try? await sender.run()
      await probe.drain()
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
    await Task.yield()
    while tasks.ended.isEmpty { await Task.yield() }
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
    await Task.yield()
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
    while tasks.ended.count < 1 { await Task.yield() }
    await MainActor.run { drainer.enteredBackground() }
    await probe.waitFinished(2)
    while tasks.ended.count < 2 { await Task.yield() }
    #expect(tasks.begun == [1, 2] && tasks.ended == [1, 2])
  }

  @Test func aRefusedBackgroundTaskStillDrainsAndEndsNothing() async throws {
    let tasks = FakeBackgroundTasks()
    tasks.refuse = true
    let probe = DrainProbe()
    let drainer = await MainActor.run { BackgroundDrainer(tasks: tasks) { await probe.drain() } }
    await MainActor.run { drainer.enteredBackground() }
    await probe.waitFinished(1)
    await Task.yield()
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
