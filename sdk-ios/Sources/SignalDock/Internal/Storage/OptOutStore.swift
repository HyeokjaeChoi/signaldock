import Foundation

/// Opt-out state (ADR-0034). A marker file `opt-out` in the SDK directory: the file exists while the host is opted
/// out. The directory is excluded from backup (ADR-0047 D6 amendment), so a restored app starts with collection on.
///
/// An actor on a dedicated serial executor owns the flag and the file, so file work stays off the cooperative pool and
/// two `set` calls cannot leave the file different from the flag: `set` has no `await`, so no other call runs between
/// the flag change and the file write. `track` reads the flag first (ADR-0034 decision 2).
///
/// The flag lives in ``OptOutFlag``, so `isOptedOut` is a synchronous read like Android's `@Volatile var optedOut`.
internal actor OptOutStore {
  internal static let fileName = "opt-out"

  private let executor = StorageExecutor(label: "local.signaldock.optout")
  private let file: URL
  private let flag: OptOutFlag

  internal nonisolated var unownedExecutor: UnownedSerialExecutor { executor.asUnownedSerialExecutor() }

  /// Reads the marker once. Only the existence is read, never the content, so the file class does not matter here.
  internal init(directory: URL) {
    file = directory.appendingPathComponent(Self.fileName)
    flag = OptOutFlag(FileManager.default.fileExists(atPath: file.path))
  }

  /// Synchronous and lock-guarded; callable from any context (ADR-0047 amendment on `OptOutFlag`).
  internal nonisolated var isOptedOut: Bool { flag.value }

  /// Sets the flag first, so the change holds at once, then writes the marker. Returns `false` when the marker could
  /// not be stored; the change still holds for this process. A failed marker removal leaves the marker, so the next
  /// start is opted out (the safe side).
  internal func set(_ value: Bool) -> Bool {
    flag.value = value
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

/// The opt-out flag, readable without `await`. `@unchecked Sendable` because the compiler cannot see the lock.
/// Invariant (ADR-0047 amendment): `lock` guards `stored`, and `stored` is touched only inside `lock`. Each access
/// holds the lock for one load or one store and calls nothing else, so the lock is never nested and cannot deadlock.
internal final class OptOutFlag: @unchecked Sendable {
  private let lock = NSLock()
  private var stored: Bool

  internal init(_ initial: Bool) { stored = initial }

  internal var value: Bool {
    get { lock.lock(); defer { lock.unlock() }; return stored }
    set { lock.lock(); defer { lock.unlock() }; stored = newValue }
  }
}
