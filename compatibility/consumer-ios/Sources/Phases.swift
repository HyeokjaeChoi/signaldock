#if DEBUG
  import Foundation
  import SignalDock

  /// Debug builds only (ADR-0047 "Amendment (2026-10-29, ticket 15, durability phases)"). With
  /// `SIMCTL_CHILD_SIGNALDOCK_PHASE=<name> xcrun simctl launch ...` the app runs one phase of the Simulator durability
  /// script (`scripts/simulator-durability.sh`) after `initialize()` and writes `Documents/phase-result.txt`. The script
  /// kills, reinstalls, and relaunches the app between phases. Not in Release. Phase code calls the public API; the only
  /// SDK internal it touches is a read of the file `Application Support/signaldock/installation-id` for the report.
  enum PhaseRun {
    private static let env = ProcessInfo.processInfo.environment
    private static let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]

    @MainActor
    static func runIfRequested(_ model: AppModel) async {
      guard let phase = env["SIGNALDOCK_PHASE"], !phase.isEmpty else { return }
      var result = ["phase": phase, "pid": "\(ProcessInfo.processInfo.processIdentifier)"]
      guard let sdk = model.sdk else {
        result["status"] = "fail"
        result["reason"] = "initialize failed: \(model.state)"
        write(result)
        return
      }
      let count = Int(env["SIGNALDOCK_PHASE_COUNT"] ?? "") ?? 5
      let name = env["SIGNALDOCK_PHASE_NAME"] ?? "dur_event"
      do {
        switch phase {
        case "store":
          var ids: [String] = []
          for i in 0..<count { if let id = try await track(sdk, name, i) { ids.append(id) } }
          result["eventIds"] = ids.joined(separator: ",")
          result["status"] = ids.count == count ? "pass" : "fail"
        case "burst":
          // Each stored event is appended to acked.log and fsynced; the script kills the process meanwhile.
          write(result.merging(["status": "started"]) { $1 })
          FileManager.default.createFile(atPath: acked.path, contents: nil)
          let log = try FileHandle(forWritingTo: acked)
          var i = 0
          while let id = try await track(sdk, name, i) {
            try log.write(contentsOf: Data((id + "\n").utf8))
            try log.synchronize()
            i += 1
            try await Task.sleep(nanoseconds: 2_000_000)
          }
          result["status"] = "fail"
          result["reason"] = "track stopped storing"
        case "snapshot":
          result["status"] = "pass"
        case "drain":
          // Delivery runs by itself (the launch drain, the persisted retry time, the foreground timer).
          let deadline = Date().addingTimeInterval(Double(env["SIGNALDOCK_PHASE_TIMEOUT"] ?? "") ?? 240)
          while try await sdk.diagnostics().queuedEvents > 0, Date() < deadline {
            try await Task.sleep(nanoseconds: 500_000_000)
          }
          // An unreadable queue also reports 0 events.
          let d = try await sdk.diagnostics()
          result["status"] = d.storageAvailable && d.queuedEvents == 0 ? "pass" : "fail"
        case "optout_set":
          result["setOptOut"] = "\(await sdk.setOptOut(true))"
          result["track"] = try await trackText(sdk, name)
          result["status"] = result["track"] == "optedOut" && sdk.isOptedOut() ? "pass" : "fail"
        case "optout_check":
          result["isOptedOut"] = "\(sdk.isOptedOut())"
          result["track"] = try await trackText(sdk, name)
          try await Task.sleep(nanoseconds: 3_000_000_000)  // a send needs a stored event: any request here is a bug
          result["status"] = result["track"] == "optedOut" && sdk.isOptedOut() ? "pass" : "fail"
        case "optout_off":
          result["isOptedOutAtStart"] = "\(sdk.isOptedOut())"
          result["setOptOut"] = "\(await sdk.setOptOut(false))"
          result["track"] = try await trackText(sdk, name)
          result["status"] = result["track"] == "stored" && !sdk.isOptedOut() ? "pass" : "fail"
        default:
          result["status"] = "fail"
          result["reason"] = "unknown phase"
        }
        let d = try await sdk.diagnostics()
        result["queuedEvents"] = "\(d.queuedEvents)"
        result["storageAvailable"] = "\(d.storageAvailable)"
        result["retryFailureCount"] = "\(d.retryFailureCount)"
        result["lastAck"] = d.lastAck.map { "accepted=\($0.accepted) duplicate=\($0.duplicate) rejected=\($0.rejected)" } ?? "none"
      } catch {
        result["status"] = "fail"
        result["reason"] = "\(error)"
      }
      result["installationId"] =
        (try? String(contentsOf: sdkDirectory.appendingPathComponent("installation-id"), encoding: .utf8))?
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
      write(result)
    }

    private static var acked: URL { documents.appendingPathComponent("acked.log") }
    private static var sdkDirectory: URL {
      FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("signaldock")
    }

    /// The event ID if `track()` reported a stored event, else `nil`.
    private static func track(_ sdk: SignalDockSDK, _ name: String, _ i: Int) async throws -> String? {
      let result = try await sdk.track(name) {
        $0.property("index", i)
        $0.property("text", "durability \(i)")
      }
      if case .success(let id) = result { return id }
      return nil
    }

    private static func trackText(_ sdk: SignalDockSDK, _ name: String) async throws -> String {
      switch try await sdk.track(name) {
      case .success: return "stored"
      case .optedOut: return "optedOut"
      case let other: return "\(other)"
      }
    }

    private static func write(_ result: [String: String]) {
      let text = result.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: "\n") + "\n"
      try? text.write(to: documents.appendingPathComponent("phase-result.txt"), atomically: true, encoding: .utf8)
    }
  }
#endif
