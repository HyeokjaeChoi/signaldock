import Foundation

@testable import SignalDock

/// A fresh temporary directory, removed when the test ends. A test that needs a clock passes fixed
/// millisecond values in the rows it stores: the storage layer reads no clock.
final class TempDirectory {
  let url: URL

  init() throws {
    url = FileManager.default.temporaryDirectory.appendingPathComponent("signaldock-test-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
  }

  deinit {
    // A test may leave a read-only directory behind.
    _ = try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: url.path)
    try? FileManager.default.removeItem(at: url)
  }

  func file(_ name: String) -> URL { url.appendingPathComponent(name) }
}

/// Collects log messages from many tasks. `@unchecked Sendable`: `stored` is read and written only under `lock`
/// (ADR-0047 amendment, ticket 08: test helpers).
final class RecordingLogger: SDKLogger, @unchecked Sendable {
  private let lock = NSLock()
  private var stored: [LogMessage] = []

  func log(_ message: LogMessage) {
    lock.lock()
    defer { lock.unlock() }
    stored.append(message)
  }

  var messages: [LogMessage] {
    lock.lock()
    defer { lock.unlock() }
    return stored
  }

  var warnings: [LogMessage] { messages.filter { $0.level == .warn } }
}

struct FixedId: IdGenerator {
  let id: String

  func newId() -> String { id }
}

func makeEvent(
  _ n: Int, installationId: String = "install-1", contractVersion: String = "1", size: Int = 100,
  createdAt: Int64? = nil
) -> EventRow {
  EventRow(
    eventId: "event-\(n)", installationId: installationId, contractVersion: contractVersion,
    payloadJson: "{\"n\":\(n)}", sizeBytes: size, createdAtMillis: createdAt ?? Int64(1_000 + n))
}

func isUuidV4(_ text: String) -> Bool {
  text.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", options: .regularExpression)
    != nil
}

/// A second raw connection to the same database file, for checks and for planting a failure.
func rawConnection(_ directory: URL) throws -> SQLiteDatabase {
  try SQLiteDatabase(path: directory.appendingPathComponent("queue.db").path)
}
