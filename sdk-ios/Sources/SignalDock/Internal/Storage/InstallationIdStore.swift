import Foundation

/// Source of new installation IDs. A test supplies fixed values.
internal protocol IdGenerator: Sendable {
  func newId() -> String
}

/// A random UUIDv4 in lowercase, the form of the Android SDK and the contract.
internal struct RandomUuidV4: IdGenerator {
  internal func newId() -> String { UUID().uuidString.lowercased() }
}

/// Installation ID in a file in the SDK directory (Application Support `signaldock/`), excluded from backup
/// (ADR-0001, ADR-0047 D6). Generated once per installation; concurrent callers in the process get the same
/// value. The file class is `completeUntilFirstUserAuthentication`, set explicitly (ADR-0047 amendment).
internal struct InstallationIdStore: Sendable {
  internal static let fileName = "installation-id"

  /// The file class is set when the file is made (ADR-0047 amendment).
  internal static let writeOptions: Data.WritingOptions = [.completeFileProtectionUntilFirstUserAuthentication]

  private static let lock = NSLock()

  private let directory: URL
  private let ids: any IdGenerator

  internal init(directory: URL, ids: any IdGenerator = RandomUuidV4()) {
    self.directory = directory
    self.ids = ids
  }

  /// Returns the stored ID or makes and stores a new one. A stored value that is not a UUIDv4 is replaced and
  /// warned about; stored events keep the ID they were made with, so that is safe (ADR-0003 item 6). A file
  /// that cannot be read or written throws `StorageError` (a storage failure, as on Android).
  internal func getOrCreate(logger: any SDKLogger) throws -> String {
    Self.lock.lock()
    defer { Self.lock.unlock() }
    let file = directory.appendingPathComponent(Self.fileName)
    if FileManager.default.fileExists(atPath: file.path) {
      let data: Data
      do { data = try Data(contentsOf: file) } catch { throw StorageError(.file, Self.errnoOf(error)) }
      if let text = String(data: data, encoding: .utf8) {
        let existing = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if Self.isUuidV4(existing) { return existing }
      }
      logger.log(.installationIdInvalid)
    }
    let id = ids.newId()
    try write(id, to: file)
    return id
  }

  /// Temp file, fsync, then rename: readers never see a partial value. The class and the backup exclusion are
  /// set on the temp file, and a rename keeps both.
  private func write(_ id: String, to file: URL) throws {
    let temp = directory.appendingPathComponent(Self.fileName + ".tmp")
    do {
      try Data(id.utf8).write(to: temp, options: Self.writeOptions)
      let handle = try FileHandle(forWritingTo: temp)
      defer { try? handle.close() }
      try handle.synchronize()
      var values = URLResourceValues()
      values.isExcludedFromBackup = true
      var tempURL = temp
      try tempURL.setResourceValues(values)
    } catch {
      throw StorageError(.file, Self.errnoOf(error))
    }
    guard rename(temp.path, file.path) == 0 else { throw StorageError(.file, errno) }
  }

  /// Lowercase `8-4-4-4-12` hex with version 4 and variant 8, 9, a, or b (the Android check).
  private static func isUuidV4(_ text: String) -> Bool {
    let c = Array(text.utf8)
    return UUID(uuidString: text) != nil && text == text.lowercased() && c[14] == UInt8(ascii: "4")
      && "89ab".utf8.contains(c[19])
  }

  internal static func errnoOf(_ error: Error) -> Int32 {
    ((error as NSError).userInfo[NSUnderlyingErrorKey] as? NSError).map { Int32($0.code) } ?? 0
  }
}
