import Foundation
import Testing

@testable import SignalDock

/// ADR-0017: the public API does not throw into the host. Each storage failure ends in the result that the
/// Android SDK gives; only caller cancellation is thrown.
@Suite struct CrashSafetyTests {
  @Test func aClosedQueueGivesStorageErrorUnavailableDiagnosticsAndNilClearQueue() async throws {
    let h = try await Harness.make()
    _ = try await h.sdk.track("stored before")
    await h.sdk.queue.close()

    let tracked = try await h.sdk.track("e")
    #expect(tracked == .storageError(message: "StorageError(closed, 0)"))
    let diagnostics = try await h.sdk.diagnostics()
    #expect(diagnostics.storageAvailable == false)
    #expect(diagnostics.queuedEvents == 0)
    #expect(diagnostics.queuedBytes == 0)
    #expect(diagnostics.oldestPendingEpochMillis == nil)
    #expect(diagnostics.recentRejections.isEmpty)
    #expect(diagnostics.lastAck == nil)
    #expect(diagnostics.lastDeliveryError == nil)
    #expect(diagnostics.retryFailureCount == 0)
    #expect(diagnostics.nextAttemptEpochMillis == nil)
    #expect(diagnostics.sdkVersion == SignalDockSDK.version)
    #expect(try await h.sdk.clearQueue() == nil)
    #expect(await h.sdk.isOptedOut() == false)  // the opt-out calls need no queue
    #expect(await h.sdk.setOptOut(true))
    #expect(h.logger.messages.contains(.trackStorageFailed))
    #expect(h.logger.messages.contains(.diagnosticsStorageFailed))
    #expect(h.logger.messages.contains(.clearQueueStorageFailed))
  }

  @Test func anyErrorFromTheSinkIsAStorageErrorWithAContentFreeMessage() async throws {
    let h = try await Harness.make()
    let storage = h.replacingSink(ThrowingSink(error: StorageError(.sqlite, 13)))  // SQLITE_FULL
    #expect(try await storage.track("e") == .storageError(message: "StorageError(sqlite, 13)"))
    let other = h.replacingSink(ThrowingSink(error: OtherError()))
    #expect(try await other.track("e") == .storageError(message: "OtherError"))
    #expect(try await h.sdk.queue.usage().count == 0)
  }

  @Test func theStorageMessageHoldsCodesOnly() async throws {
    let h = try await Harness.make()
    await h.sdk.queue.close()
    let result = try await h.sdk.track("secret_event_name") { $0.property("secret_key", "secret_value") }
    guard case .storageError(let message) = result else { Issue.record("\(result)"); return }
    for secret in ["secret_event_name", "secret_key", "secret_value", h.dir.url.path, "test-key"] {
      #expect(!message.contains(secret))
    }
  }

  @Test func everyInvalidReasonIsAResultNotAnError() async throws {
    let h = try await Harness.make()
    let sdk = h.sdk
    let cases: [(InvalidReason, TrackResult)] = [
      (.emptyName, try await sdk.track("")),
      (.emptyName, try await sdk.track(" \t")),
      (.leadingOrTrailingWhitespace, try await sdk.track("a ")),
      (.forbiddenCharacter, try await sdk.track("a\u{0}")),
      (.forbiddenCharacter, try await sdk.track("e") { $0.property("v", "x\u{0}") }),
      (
        .duplicateKey,
        try await sdk.track("e") {
          $0.property("k", 1); $0.property("k", 1)
        }
      ),
      (.nonFiniteNumber, try await sdk.track("e") { $0.property("n", Double.nan) }),
      (.nonFiniteNumber, try await sdk.track("e") { $0.property("n", Double.infinity) }),
      (.numberOutOfRange, try await sdk.track("e") { $0.property("n", Int64.min) }),
      (.numberOutOfRange, try await sdk.track("e") { $0.property("n", Int.max) }),
      (.numberOutOfRange, try await sdk.track("e") { $0.property("n", 1e300) }),
      (.eventTooLarge, try await sdk.track("e") { $0.property("big", String(repeating: "x", count: 20_000)) }),
    ]
    for (reason, result) in cases {
      guard case .invalid(let actual, _) = result else { Issue.record("\(reason): \(result)"); continue }
      #expect(actual == reason)
    }
    #expect(Set(cases.map(\.0)) == Set(InvalidReason.allCases))
    #expect(try await sdk.diagnostics().queuedEvents == 0)
  }

  @Test func everyTrackResultCaseIsReachable() async throws {
    let h = try await Harness.make(limits: QueueLimits(maxEvents: 1))
    guard case .success(let id) = try await h.sdk.track("a") else { Issue.record("success"); return }
    #expect(isUuidV4(id) || id.hasPrefix("00000000-0000-4000-8000-"))
    #expect(try await h.sdk.track("") == .invalid(reason: .emptyName, field: "name"))
    #expect(try await h.sdk.track("b") == .queueFull)
    _ = await h.sdk.setOptOut(true)
    #expect(try await h.sdk.track("c") == .optedOut)
    await h.sdk.queue.close()
    _ = await h.sdk.setOptOut(false)
    guard case .storageError = try await h.sdk.track("d") else { Issue.record("storageError"); return }
  }

  // MARK: Cancellation (spec section 3; Q114)

  @Test func aTrackCancelledBeforeStorageStoresNothingAndLogsNothing() async throws {
    let h = try await Harness.make()
    let sdk = h.sdk
    let outcome = await runCancelled { try await sdk.track("e") }
    guard case .failure = outcome else { Issue.record("expected CancellationError, got \(outcome)"); return }
    #expect(try await sdk.queue.usage().count == 0)
    #expect(h.logger.warnings.isEmpty)
  }

  @Test func aCancelledCallerStillGetsAValidationResultBeforeStorageStarts() async throws {
    // As on Android: cancellation is checked just before storage, so invalid input is still a result.
    let h = try await Harness.make()
    let sdk = h.sdk
    let outcome = await runCancelled { try await sdk.track("") }
    guard case .success(let result) = outcome else { Issue.record("expected a result, got \(outcome)"); return }
    #expect(result == .invalid(reason: .emptyName, field: "name"))
  }

  @Test func aTrackCancelledDuringStorageThrowsWithAWarningAndTheEventStaysStored() async throws {
    let h = try await Harness.make()
    let gated = GatedSink(inner: h.sdk.queue)
    let sdk = h.replacingSink(gated)
    let task = Task { () async -> Bool in
      do {
        _ = try await sdk.track("e")
        return false
      } catch {
        return true
      }
    }
    await gated.entered.wait()
    task.cancel()
    await gated.release.open()
    #expect(await task.value, "the cancelled caller gets CancellationError, not a result")
    #expect(h.logger.warnings == [.trackCancelledDuringStorage])
    #expect(try await sdk.queue.usage().count == 1)  // committed: the host must not collect the action again
  }

  @Test func diagnosticsAndClearQueueThrowOnlyCancellation() async throws {
    let h = try await Harness.make()
    _ = try await h.sdk.track("e")
    let sdk = h.sdk
    guard case .failure = await runCancelled({ try await sdk.diagnostics() }) else {
      Issue.record("diagnostics must propagate cancellation")
      return
    }
    guard case .failure = await runCancelled({ try await sdk.clearQueue() }) else {
      Issue.record("clearQueue must propagate cancellation")
      return
    }
    #expect(try await sdk.diagnostics().queuedEvents == 1)  // the cancelled clearQueue deleted nothing
  }

  @Test func setOptOutIsNotUndoneByCancellation() async throws {
    let h = try await Harness.make()
    let sdk = h.sdk
    let task = Task { () async -> Bool in await sdk.setOptOut(true) }
    task.cancel()
    #expect(await task.value)
    #expect(await sdk.isOptedOut())
  }

  @Test func aClosedQueueIsAStorageFailureForAnActiveCallerNotCancellation() async throws {
    // The Android case: a closed Room database cancels its own scope. Here nothing cancels; the result is data.
    let h = try await Harness.make()
    await h.sdk.queue.close()
    guard case .storageError = try await h.sdk.track("e") else { Issue.record("storageError"); return }
    #expect(try await h.sdk.diagnostics().storageAvailable == false)
  }

  @Test func manyCallsAtOnceNeverThrowAnythingButCancellation() async throws {
    let h = try await Harness.make(limits: QueueLimits(maxEvents: 50))
    let sdk = h.sdk
    try await withThrowingTaskGroup(of: Void.self) { group in
      for n in 0..<40 {
        group.addTask { _ = try await sdk.track("e\(n)") }
        group.addTask { _ = await sdk.setOptOut(n % 2 == 0) }
        group.addTask { _ = try await sdk.diagnostics() }
        group.addTask { _ = await sdk.isOptedOut() }
        if n % 10 == 0 { group.addTask { _ = try await sdk.clearQueue() } }
      }
      try await group.waitForAll()
    }
    #expect(try await sdk.diagnostics().storageAvailable)
  }
}
