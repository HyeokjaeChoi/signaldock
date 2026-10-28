import Foundation

/// TEMPORARY diagnosis of the CI stall: prints when a real thread, a GCD timer, or a Swift task sleep wakes late.
enum Heartbeat {
  static let start: Void = {
    let thread = Thread {
      var last = Date()
      while true {
        Thread.sleep(forTimeInterval: 0.5)
        let now = Date()
        if now.timeIntervalSince(last) > 2 { print("HEARTBEAT thread gap \(now.timeIntervalSince(last)) at \(now)") }
        last = now
      }
    }
    thread.start()
    let source = DispatchSource.makeTimerSource(queue: DispatchQueue.global())
    let box = Box()
    source.schedule(deadline: .now() + 0.5, repeating: 0.5)
    source.setEventHandler {
      let now = Date()
      if now.timeIntervalSince(box.last) > 2 { print("HEARTBEAT gcd gap \(now.timeIntervalSince(box.last)) at \(now)") }
      box.last = now
    }
    source.resume()
    box.source = source
    Task.detached {
      var last = Date()
      while true {
        try? await Task.sleep(nanoseconds: 500_000_000)
        let now = Date()
        if now.timeIntervalSince(last) > 2 { print("HEARTBEAT task gap \(now.timeIntervalSince(last)) at \(now)") }
        last = now
      }
    }
  }()

  private final class Box: @unchecked Sendable {
    var last = Date()
    var source: DispatchSourceTimer?
  }
}
