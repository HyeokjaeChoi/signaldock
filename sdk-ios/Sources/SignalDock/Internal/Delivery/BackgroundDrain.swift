import Foundation
import UIKit

/// The iOS background task boundary (`beginBackgroundTask`). A test supplies a fake that records begin and end calls
/// and runs the expiration handler by hand.
internal protocol BackgroundTasks: Sendable {
  /// Starts a background task. `expiration` runs on the main actor when the system is about to end the task; it must
  /// end the task (`end`) before it returns. Returns `nil` if the system refused the task.
  @MainActor func begin(expiration: @escaping @MainActor @Sendable () -> Void) -> Int?

  /// Ends a task that `begin` returned.
  @MainActor func end(_ task: Int)
}

@available(iOSApplicationExtension, unavailable)
internal struct UIKitBackgroundTasks: BackgroundTasks {
  internal init() {}

  @MainActor internal func begin(expiration: @escaping @MainActor @Sendable () -> Void) -> Int? {
    let id = UIApplication.shared.beginBackgroundTask(withName: "local.signaldock.drain", expirationHandler: expiration)
    return id == .invalid ? nil : id.rawValue
  }

  @MainActor internal func end(_ task: Int) {
    UIApplication.shared.endBackgroundTask(UIBackgroundTaskIdentifier(rawValue: task))
  }
}

/// One drain when the app enters the background, inside a background task (ADR-0047 D8): the task starts before the
/// work and ends once. The expiration handler cancels the request and ends the task. A cancelled request leaves the
/// queue and the retry state as they were; the queue and the ACK transaction keep the data, and the same event IDs and
/// bytes go out at the next drain (the server answers `duplicate` if the request had arrived).
@MainActor
internal final class BackgroundDrainer {
  /// One entry into the background: the task, its token, and the one-time end.
  private final class Run {
    var token: Int?
    var task: Task<Void, Never>?
    var ended = false
  }

  /// The drain task of the latest entry into the background. Tests await it to know that the end has run.
  internal private(set) var lastTask: Task<Void, Never>?
  private let tasks: any BackgroundTasks
  private let drain: @Sendable () async -> Void
  private let onExpire: @Sendable () -> Void

  /// - Parameters:
  ///   - drain: one drain; it handles its own errors and returns when it is cancelled.
  ///   - onExpire: runs when the system ends the task, before it ends: cancels a request that another drain owns.
  internal init(
    tasks: any BackgroundTasks, onExpire: @escaping @Sendable () -> Void = {},
    drain: @escaping @Sendable () async -> Void
  ) {
    self.onExpire = onExpire
    self.tasks = tasks
    self.drain = drain
  }

  internal func enteredBackground() {
    let run = Run()
    // Before the work. If the system refuses the task the drain still runs: it may get a little time anyway.
    run.token = tasks.begin { [self] in expire(run) }
    run.task = Task { [drain] in
      await drain()
      finish(run)
    }
    lastTask = run.task
  }

  private func expire(_ run: Run) {
    run.task?.cancel()
    onExpire()
    end(run)
  }

  private func finish(_ run: Run) { end(run) }

  private func end(_ run: Run) {
    if run.ended { return }
    run.ended = true
    if let token = run.token { tasks.end(token) }
  }
}

/// Connects the app lifecycle to delivery: a drain at return to the foreground, and one drain inside a background
/// task at entry into the background. The notification center is a parameter so a test posts the notifications.
@MainActor
internal final class LifecycleObserver: NSObject {
  private let onForeground: @MainActor () -> Void
  private let onBackground: @MainActor () -> Void

  internal init(
    center: NotificationCenter = .default, onForeground: @escaping @MainActor () -> Void,
    onBackground: @escaping @MainActor () -> Void
  ) {
    self.onForeground = onForeground
    self.onBackground = onBackground
    super.init()
    // Selector observers are called on the posting thread, synchronously: `begin` must run before the handler returns.
    center.addObserver(
      self, selector: #selector(didEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
    center.addObserver(
      self, selector: #selector(willEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
  }

  @objc private func didEnterBackground() { onBackground() }

  @objc private func willEnterForeground() { onForeground() }
}
