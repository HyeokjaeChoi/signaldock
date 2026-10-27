import Foundation

/// The SDK directory, Application Support `signaldock/` (ADR-0047 D6).
internal enum StorageDirectory {
  /// Creates `signaldock/` in `base` when it is missing. The files in it get their class when they are made.
  internal static func prepare(in base: URL) throws -> URL {
    let directory = base.appendingPathComponent("signaldock", isDirectory: true)
    do {
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    } catch {
      throw StorageError(.file, InstallationIdStore.errnoOf(error))
    }
    return directory
  }

  /// `prepare(in:)` for the Application Support folder of the app.
  internal static func applicationSupport() throws -> URL {
    let base: URL
    do {
      base = try FileManager.default.url(
        for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    } catch {
      throw StorageError(.file, InstallationIdStore.errnoOf(error))
    }
    return try prepare(in: base)
  }
}
