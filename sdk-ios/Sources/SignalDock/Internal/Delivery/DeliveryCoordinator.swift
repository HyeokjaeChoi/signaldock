import Foundation
import UIKit

/// What the factory needs to build delivery. Production values come from `production(config:)`; a test replaces the
/// transport, the randomness, the timer, and the background task boundary, and can switch the triggers off.
@available(iOSApplicationExtension, unavailable)
internal struct DeliveryDependencies: Sendable {
  /// `nil` means the `URLSession` transport of the configuration.
  internal var transport: (any BatchTransport)?
  internal var jitter: any Jitter = RandomJitter()
  internal var timer: any TriggerTimer = TaskSleepTimer()
  internal var backgroundTasks: any BackgroundTasks = UIKitBackgroundTasks()
  internal var eventThreshold = triggerEvents
  internal var delayMillis = triggerDelayMillis
  /// Observe the app lifecycle (foreground return and background entry).
  internal var observeLifecycle = true
  /// Whether the app is active now, read once on the main actor when the SDK is built. A launch in the background
  /// (a silent push, a background fetch) starts without the retry timer.
  internal var initialActive: @MainActor @Sendable () -> Bool = { UIApplication.shared.applicationState == .active }
  /// One drain when the SDK is built (a launch with a restored queue).
  internal var drainAtLaunch = true
  /// Where the lifecycle notifications come from. A test seam: a test posts on its own center.
  internal var notificationCenter = NotificationCenter.default
  /// Runs after every drain, also a cancelled one. A test seam: it tells a test that the drain is over.
  internal var afterDrain: (@Sendable () async -> Void)?

  internal static var production: DeliveryDependencies { DeliveryDependencies() }
}

/// Runs the shared `Sender` for every trigger: the 20-events-or-10-seconds foreground trigger, the launch and
/// foreground-return drains, and the one drain at entry into the background (ADR-0047 D8). There is no periodic
/// background send on iOS. Mirrors the Android `DeliveryCoordinator`.
@available(iOSApplicationExtension, unavailable)
internal final class DeliveryCoordinator: Sendable {
  internal let sender: Sender
  internal let trigger: DeliveryTrigger
  private let observer: LifecycleObserver?

  /// - Parameter observer: created by the caller on the main actor; kept here so it lives as long as the SDK.
  internal init(sender: Sender, trigger: DeliveryTrigger, observer: LifecycleObserver?) {
    self.sender = sender
    self.trigger = trigger
    self.observer = observer
  }

  /// A drain that never throws: cancellation ends it quietly; a storage failure is logged and the queue stays as it
  /// was, so the next trigger tries again.
  @discardableResult
  internal static func drain(_ sender: Sender, _ logger: any SDKLogger) async -> Int64? {
    do {
      if case .waiting(let until) = try await sender.run().end { return until }
    } catch is CancellationError {
      return nil
    } catch {
      logger.log(.deliveryRunFailed)
    }
    return nil
  }

  /// Builds sender, trigger, and lifecycle observer.
  internal static func make(
    queue: QueueStore, config: SignalDockConfig, clock: any MillisClock, logger: any SDKLogger,
    notice: DeprecationNotice, dependencies: DeliveryDependencies
  ) async -> DeliveryCoordinator {
    let sender = Sender(
      queue: queue, transport: dependencies.transport ?? URLSessionBatchTransport(config: config), clock: clock,
      jitter: dependencies.jitter, logger: logger, notice: notice)
    let afterDrain = dependencies.afterDrain
    let drain: @Sendable () async -> Int64? = {
      let until = await Self.drain(sender, logger)
      await afterDrain?()
      return until
    }
    let initialActive: Bool =
      dependencies.observeLifecycle ? await MainActor.run { dependencies.initialActive() } : true
    let trigger = DeliveryTrigger(
      eventThreshold: dependencies.eventThreshold, delayMillis: dependencies.delayMillis, timer: dependencies.timer,
      clock: clock, active: initialActive, drain: drain)
    let observer: LifecycleObserver? =
      dependencies.observeLifecycle
      ? await MainActor.run {
        let drainer = BackgroundDrainer(
          tasks: dependencies.backgroundTasks, onExpire: { sender.cancelInFlight() }, drain: { _ = await drain() })
        return LifecycleObserver(
          center: dependencies.notificationCenter,
          onForeground: {
            trigger.setActive(true)
            Task { await trigger.requestDrain() }
          },
          onBackground: {
            trigger.setActive(false)  // at once: before the background drain starts, no foreground retry can start
            drainer.enteredBackground()
          })
      } : nil
    let coordinator = DeliveryCoordinator(sender: sender, trigger: trigger, observer: observer)
    if dependencies.drainAtLaunch { await trigger.requestDrain() }
    return coordinator
  }
}
