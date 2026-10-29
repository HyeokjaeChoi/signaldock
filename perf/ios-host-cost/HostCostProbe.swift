// Measurement probe for scripts/measure-ios-sdk.sh (ADR-0047 amendment, ticket 16). It is NOT part of the consumer app:
// the script appends this file to ConsumerApp.swift in a temporary copy of compatibility/consumer-ios, replaces
// `SignalDockSDK.initialize(config)` with `HostCostProbe.initialize(config)`, and adds `.onAppear` to the root view.
// No checked-in app source and no Release build of the repository contains it.
//
// Two modes, chosen by the launch environment (HOSTCOST_SETTLE):
// - 0 (default): `initialize()` runs where the shipped app calls it (AppModel.init, before the first view). Used for
//   cold start (process start to first view, with initialize inside, ADR-0020 item 4) and for memory after launch. The
//   main/wall values of this mode are recorded but the main value is not used: the sentinel waits behind SwiftUI work.
// - 1: the probe waits for the first view and a 500 ms settle time before it calls `initialize()`, so the main-actor
//   queue is idle and the sentinel task (queued on the main actor right before the call; it runs when the caller first
//   suspends) measures the main-thread time of the call: the time from the call until the caller first suspends, or
//   until the call returns if it never suspends (never more than wall). This is ADR-0020 item 2. It includes call setup
//   and the class loading the call causes, so it is an upper bound for the SDK alone. `track()` calls (burst runs) use
//   this mode too, so they run with an idle queue. Wall time runs until the result is ready.
import Darwin
import Foundation

@MainActor
private final class ProbeBox {
  var stamp: UInt64 = 0
}

@MainActor
enum HostCostProbe {
  private static var out: [String: Any] = ["done": false]
  private static var firstViewDone = false

  private static func now() -> UInt64 { clock_gettime_nsec_np(CLOCK_UPTIME_RAW) }

  /// Process start (kernel record) as milliseconds since the Unix epoch.
  private static func processStartMs() -> Double {
    var info = kinfo_proc()
    var size = MemoryLayout<kinfo_proc>.stride
    var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
    guard sysctl(&mib, 4, &info, &size, nil, 0) == 0 else { return 0 }
    let tv = info.kp_proc.p_un.__p_starttime
    return Double(tv.tv_sec) * 1000 + Double(tv.tv_usec) / 1000
  }

  private static func flush() {
    guard let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first,
      let data = try? JSONSerialization.data(withJSONObject: out, options: [.sortedKeys])
    else { return }
    try? data.write(to: dir.appendingPathComponent("hostcost.json"), options: .atomic)
  }

  /// Runs `op` and returns its result with the main-thread time (until the first suspension) and the wall time, in ns.
  private static func timed<T, E: Error>(_ op: () async throws(E) -> T) async throws(E) -> (T, UInt64, UInt64) {
    let box = ProbeBox()
    let t0 = now()
    let sentinel = Task { @MainActor in box.stamp = now() }
    let value = try await op()
    let t1 = now()
    await sentinel.value
    return (value, min(box.stamp, t1) - t0, t1 - t0)
  }

  static func firstView() {
    guard !firstViewDone else { return }
    firstViewDone = true
    out["first_view_after_process_start_ms"] = Date().timeIntervalSince1970 * 1000 - processStartMs()
    flush()
  }

  static func initialize(_ config: SignalDockConfig) async throws(CancellationError) -> InitResult {
    let burst = Int(ProcessInfo.processInfo.environment["HOSTCOST_BURST"] ?? "") ?? 0
    let settle = ProcessInfo.processInfo.environment["HOSTCOST_SETTLE"] == "1"
    out["settled"] = settle
    if settle {
      while !firstViewDone { try? await Task.sleep(nanoseconds: 20_000_000) }
      try? await Task.sleep(nanoseconds: 500_000_000)
    }
    out["init_started_after_process_start_ms"] = Date().timeIntervalSince1970 * 1000 - processStartMs()
    let (result, mainNs, wallNs) = try await timed { () async throws(CancellationError) -> InitResult in
      try await SignalDockSDK.initialize(config)
    }
    out["init_main_ns"] = mainNs
    out["init_wall_ns"] = wallNs
    switch result {
    case .success(let sdk):
      out["init_result"] = "success"
      var track: [[UInt64]] = []
      var failed = 0
      for i in 0..<burst {
        let (r, m, w) = try await timed { () async throws(CancellationError) -> TrackResult in
          try await sdk.productViewed(productId: "p-\(i % 7)", priceMinor: 100)
        }
        if case .success = r {} else { failed += 1 }
        track.append([m, w])
      }
      out["track_main_wall_ns"] = track
      out["track_failed"] = failed
    case .failure(let error):
      out["init_result"] = "failure: \(error)"
    }
    out["done"] = true
    flush()
    return result
  }
}
