import Foundation
import Testing

@testable import SignalDock

/// Opt-out and `clearQueue` (ADR-0034), against the Android `OptOutTest`.
@Suite struct OptOutTests {
  @Test func collectionIsOnByDefault() async throws {
    let h = try await Harness.make()
    #expect(await h.sdk.isOptedOut() == false)
    guard case .success = try await h.sdk.track("e") else { Issue.record("not stored"); return }
  }

  @Test func whileOptedOutTrackStoresNothingEvenForInvalidInput() async throws {
    let ids = SequenceIds()
    let h = try await Harness.make(ids: ids)
    #expect(await h.sdk.setOptOut(true))
    #expect(await h.sdk.isOptedOut())
    #expect(try await h.sdk.track("e") { $0.property("k", 1) } == .optedOut)
    #expect(try await h.sdk.track(" bad name ") == .optedOut)  // not validated
    #expect(try await h.sdk.diagnostics().queuedEvents == 0)
    #expect(ids.used == 0)  // no ID was made
  }

  @Test func setOptOutFalseResumesCollectionAndRemovesTheMarker() async throws {
    let h = try await Harness.make()
    _ = await h.sdk.setOptOut(true)
    #expect(FileManager.default.fileExists(atPath: h.sdkDirectory.appendingPathComponent("opt-out").path))
    #expect(await h.sdk.setOptOut(false))
    #expect(await h.sdk.isOptedOut() == false)
    guard case .success = try await h.sdk.track("e") else { Issue.record("not stored"); return }
    #expect(!FileManager.default.fileExists(atPath: h.sdkDirectory.appendingPathComponent("opt-out").path))
  }

  @Test func queuedEventsStayWhileOptedOut() async throws {
    let h = try await Harness.make()
    _ = try await h.sdk.track("before")
    _ = await h.sdk.setOptOut(true)
    #expect(try await h.sdk.diagnostics().queuedEvents == 1)  // the sender (ticket 09) still sends them
    _ = try await h.sdk.track("after")
    #expect(try await h.sdk.diagnostics().queuedEvents == 1)
  }

  @Test func theStateSurvivesRecreatingTheSDKOnTheSameFilesInTheBackupExcludedDirectory() async throws {
    let first = try await Harness.make()
    _ = await first.sdk.setOptOut(true)
    let marker = first.sdkDirectory.appendingPathComponent("opt-out")
    #expect(FileManager.default.fileExists(atPath: marker.path))
    #expect(try first.sdkDirectory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)

    // The same storage opened again, as after a process restart (a real restart: not run).
    let second = try await Harness.make(dir: first.dir)
    #expect(await second.sdk.isOptedOut())
    #expect(try await second.sdk.track("e") == .optedOut)

    _ = await second.sdk.setOptOut(false)
    let third = try await Harness.make(dir: first.dir)
    #expect(await third.sdk.isOptedOut() == false)
  }

  @Test func setOptOutReportsFalseWhenTheStateCannotBeStoredAndStillAppliesInMemory() async throws {
    let h = try await Harness.make()
    // A directory that cannot be written: the marker can be neither made nor removed.
    #expect(chmod(h.sdkDirectory.path, 0o500) == 0)
    defer { chmod(h.sdkDirectory.path, 0o700) }
    #expect(await h.sdk.setOptOut(true) == false)
    #expect(await h.sdk.isOptedOut())
    #expect(try await h.sdk.track("e") == .optedOut)
    #expect(h.logger.messages.contains(.optOutNotStored))

    chmod(h.sdkDirectory.path, 0o700)
    #expect(await h.sdk.setOptOut(true))  // stored now
    chmod(h.sdkDirectory.path, 0o500)
    #expect(await h.sdk.setOptOut(false) == false)  // the marker stays: the next start is opted out
    #expect(await h.sdk.isOptedOut() == false)
    guard case .success = try await h.sdk.track("e") else { Issue.record("applies in this process"); return }
  }

  @Test func theMarkerFileHasTheStatedClassOrTheSimulatorReportsTheDefault() async throws {
    let h = try await Harness.make()
    _ = await h.sdk.setOptOut(true)
    let marker = h.sdkDirectory.appendingPathComponent("opt-out")
    // The Simulator does not enforce Data Protection (ADR-0047 ticket 07 results): it reports this class for every
    // file. On a device the explicit write option sets it.
    let values = try marker.resourceValues(forKeys: [.fileProtectionKey])
    #expect(values.fileProtection == .completeUntilFirstUserAuthentication)
  }

  @Test func clearQueueDeletesEveryQueuedEventAndReturnsTheCount() async throws {
    let h = try await Harness.make()
    for _ in 0..<3 { _ = try await h.sdk.track("e") }
    #expect(try await h.sdk.clearQueue() == 3)
    #expect(try await h.sdk.diagnostics().queuedEvents == 0)
    #expect(try await h.sdk.clearQueue() == 0)
    guard case .success = try await h.sdk.track("after") else { Issue.record("not stored"); return }
  }

  /// ADR-0034 decision 8: a `track` that started before `setOptOut(true)` returned can still store its event.
  @Test func aTrackThatStartedBeforeOptOutReturnedStoresItsEvent() async throws {
    let h = try await Harness.make()
    let gated = GatedSink(inner: h.sdk.queue)
    let sdk = h.replacingSink(gated)

    let started = Task { try await sdk.track("racing") }
    await gated.entered.wait()  // the sink opens it: the track passed the opt-out check and is in storage
    #expect(await sdk.setOptOut(true))  // returns while that track is still in storage
    await gated.release.open()
    guard case .success = try await started.value else { Issue.record("the racing event is stored"); return }

    #expect(try await sdk.track("later") == .optedOut)  // everything that starts after the return is dropped
    #expect(try await h.storedEvents().compactMap { $0["name"] as? String } == ["racing"])
  }
}
