import Foundation

/// Opt-out state (ADR-0034). A marker file `opt-out` in the SDK directory: the file exists while the host is opted
/// out. The directory is excluded from backup (ADR-0047 D6 amendment), so a restored app starts with collection on.
///
/// An actor on a dedicated serial executor owns the flag and the file, so file work stays off the cooperative pool and
/// two `set` calls cannot leave the file different from the flag: `set` has no `await`, so no other call runs between
/// the flag change and the file write. `track` reads the flag first (ADR-0034 decision 2).
internal actor OptOutStore {
  internal static let fileName = "opt-out"

  private let executor = StorageExecutor(label: "local.signaldock.optout")
  private let file: URL
  private var optedOut: Bool

  internal nonisolated var unownedExecutor: UnownedSerialExecutor { executor.asUnownedSerialExecutor() }

  /// Reads the marker once. Only the existence is read, never the content, so the file class does not matter here.
  internal init(directory: URL) {
    file = directory.appendingPathComponent(Self.fileName)
    optedOut = FileManager.default.fileExists(atPath: file.path)
  }

  internal func isOptedOut() -> Bool { optedOut }

  /// Sets the flag first, so the change holds at once, then writes the marker. Returns `false` when the marker could
  /// not be stored; the change still holds for this process. A failed marker removal leaves the marker, so the next
  /// start is opted out (the safe side).
  internal func set(_ value: Bool) -> Bool {
    optedOut = value
    do {
      if value {
        if !FileManager.default.fileExists(atPath: file.path) { try write() }
      } else if FileManager.default.fileExists(atPath: file.path) {
        try FileManager.default.removeItem(at: file)
      }
      return true
    } catch {
      return false  // reported as data (ADR-0017)
    }
  }

  /// One byte, synced. The file class is the one of the other SDK files (ADR-0047 amendment).
  private func write() throws {
    try Data([1]).write(to: file, options: [.completeFileProtectionUntilFirstUserAuthentication])
    let handle = try FileHandle(forWritingTo: file)
    defer { try? handle.close() }
    try handle.synchronize()
  }
}
