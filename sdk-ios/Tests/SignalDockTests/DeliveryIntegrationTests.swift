import Foundation
import Testing
import UIKit

@testable import SignalDock

/// The real factory with a fake transport, a manual timer, and a fake background task: `track` to ACK, and the launch,
/// foreground, and background triggers (ADR-0047 D8).
@Suite struct DeliveryIntegrationTests {
  private struct Parts {
    var deps: DeliveryDependencies
    let transport: FakeTransport
    let timer: ManualTimer
    let tasks: FakeBackgroundTasks
    let probe: DrainProbe
    let center: NotificationCenter
  }

  private func parts(replies: [FakeTransport.Reply] = [], lifecycle: Bool = false, launch: Bool = false) -> Parts {
    let transport = FakeTransport(replies)
    let timer = ManualTimer()
    let tasks = FakeBackgroundTasks()
    let probe = DrainProbe()
    let center = NotificationCenter()
    var deps = DeliveryDependencies()
    deps.transport = transport
    deps.timer = timer
    deps.backgroundTasks = tasks
    deps.notificationCenter = center
    deps.observeLifecycle = lifecycle
    deps.drainAtLaunch = launch
    deps.afterDrain = { await probe.drain() }
    return Parts(deps: deps, transport: transport, timer: timer, tasks: tasks, probe: probe, center: center)
  }

  private func track(_ h: Harness, _ name: String) async throws {
    let result = try await h.sdk.track(name)
    guard case .success = result else { Issue.record("track gave \(result)"); return }
  }

  @Test func twentyTrackedEventsAreSentAndAckedWithoutAnyWait() async throws {
    let p = parts(replies: [.ack()])
    let h = try await Harness.make(delivery: p.deps)
    for i in 0..<20 { try await track(h, "e\(i)") }
    await p.probe.waitFinished(1)
    #expect(await p.transport.requests.count == 1)
    let d = try await h.sdk.diagnostics()
    #expect(d.queuedEvents == 0 && d.lastAck?.accepted == 20)
  }

  @Test func oneTrackedEventIsSentWhenTheTenSecondDelayEnds() async throws {
    let p = parts(replies: [.ack()])
    let h = try await Harness.make(delivery: p.deps)
    try await track(h, "e")
    await p.timer.waitUntilSleeping()
    #expect(await p.timer.requestedMillis == [10_000])
    #expect(await p.transport.requests.isEmpty)
    await p.timer.fire()
    await p.probe.waitFinished(1)
    #expect(try await h.sdk.diagnostics().queuedEvents == 0)
  }

  @Test func aLaunchWithARestoredQueueDrainsAtOnce() async throws {
    let dir = try TempDirectory()
    let first = try await Harness.make(dir: dir)
    try await track(first, "kept")
    await first.sdk.queue.close()
    let p = parts(replies: [.ack()], launch: true)
    let second = try await Harness.make(dir: dir, delivery: p.deps)
    await p.probe.waitFinished(1)
    #expect(await p.transport.requests.count == 1)
    #expect(try await second.sdk.diagnostics().queuedEvents == 0)
  }

  @Test func aLaunchBeforeThePersistedNextAttemptTimeSendsNothing() async throws {
    let dir = try TempDirectory()
    let first = try await Harness.make(dir: dir)
    try await track(first, "kept")
    let now = FixedClock().millis
    try await first.sdk.queue.saveRetryState(
      RetryStateRow(failureCount: 2, nextAttemptAtMillis: now + 3_600_000, waitMillis: 3_600_000))
    await first.sdk.queue.close()
    let p = parts(replies: [.ack()], launch: true)
    let second = try await Harness.make(dir: dir, delivery: p.deps)
    await p.probe.waitFinished(1)
    #expect(await p.transport.requests.isEmpty, "the stored time is the only retry clock")
    #expect(try await second.sdk.diagnostics().queuedEvents == 1)
  }

  @Test func aLaunchWithAnEmptyQueueSendsNothing() async throws {
    let p = parts(launch: true)
    _ = try await Harness.make(delivery: p.deps)
    await p.probe.waitFinished(1)
    #expect(await p.transport.requests.isEmpty)
  }

  @Test func returningToTheForegroundDrains() async throws {
    let p = parts(replies: [.ack()], lifecycle: true)
    let h = try await Harness.make(delivery: p.deps)
    try await track(h, "e")
    await MainActor.run { p.center.post(name: UIApplication.willEnterForegroundNotification, object: nil) }
    await p.probe.waitFinished(1)
    #expect(await p.transport.requests.count == 1)
    #expect(try await h.sdk.diagnostics().queuedEvents == 0)
  }

  @Test func enteringTheBackgroundDrainsOnceInsideABackgroundTask() async throws {
    let p = parts(replies: [.ack()], lifecycle: true)
    let h = try await Harness.make(delivery: p.deps)
    try await track(h, "e")
    await MainActor.run { p.center.post(name: UIApplication.didEnterBackgroundNotification, object: nil) }
    #expect(p.tasks.begun == [1], "the task began before the notification handler returned")
    await p.probe.waitFinished(1)
    await p.tasks.waitEnded(1)
    #expect(p.tasks.ended == [1])
    #expect(await p.transport.requests.count == 1)
    #expect(try await h.sdk.diagnostics().queuedEvents == 0)
  }

  @Test func theExpirationOfTheBackgroundTaskKeepsTheQueueAndTheNextLaunchSendsTheSameEvents() async throws {
    let entered = Gate()
    let server = FakeServer()
    let p = parts(replies: [.serverHold(server, entered: entered)], lifecycle: true)
    let dir = try TempDirectory()
    let h = try await Harness.make(dir: dir, delivery: p.deps)
    for i in 0..<3 { try await track(h, "e\(i)") }
    let ids = try await h.sdk.queue.oldest(limit: 10).map(\.eventId)
    await MainActor.run { p.center.post(name: UIApplication.didEnterBackgroundNotification, object: nil) }
    await entered.wait()  // the request is in flight
    await MainActor.run { p.tasks.expireLast() }
    #expect(p.tasks.ended == [1])
    await p.probe.waitFinished(1)  // the cancelled drain returned
    #expect(try await h.sdk.queue.oldest(limit: 10).map(\.eventId) == ids, "nothing is lost")
    #expect(try await h.sdk.diagnostics().retryFailureCount == 0)
    let sent = try #require(await p.transport.requests.first)

    await h.sdk.queue.close()  // the process ends; the app starts again
    let relaunch = parts(replies: [.server(server)], launch: true)
    let again = try await Harness.make(dir: dir, delivery: relaunch.deps)
    await relaunch.probe.waitFinished(1)
    let resent = try #require(await relaunch.transport.requests.first)
    #expect(resent.body == sent.body, "the same bytes and event IDs")
    #expect(await server.stored == ids, "no event reached the server twice as new")
    #expect(try await again.sdk.diagnostics().queuedEvents == 0)
    #expect(try await again.sdk.diagnostics().lastAck?.duplicate == 3)
  }
}
