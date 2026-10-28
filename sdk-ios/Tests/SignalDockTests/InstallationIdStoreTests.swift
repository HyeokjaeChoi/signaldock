import Foundation
import Testing

@testable import SignalDock

@Suite final class InstallationIdStoreTests {
  let dir: TempDirectory

  init() throws { dir = try TempDirectory() }

  @Test func createsUuidV4AndKeepsItAcrossStoreInstances() throws {
    let logger = RecordingLogger()
    let first = try InstallationIdStore(directory: dir.url).getOrCreate(logger: logger)
    #expect(isUuidV4(first))
    #expect(try InstallationIdStore(directory: dir.url).getOrCreate(logger: logger) == first)
    #expect(logger.warnings.isEmpty)
    let stored = try String(contentsOf: dir.file(InstallationIdStore.fileName), encoding: .utf8)
    #expect(stored == first)
  }

  @Test func concurrentCallersGetTheSameId() async throws {
    let directory = dir.url
    let ids = try await withThrowingTaskGroup(of: String.self) { group in
      for _ in 0..<64 {
        group.addTask { try InstallationIdStore(directory: directory).getOrCreate(logger: RecordingLogger()) }
      }
      return try await group.reduce(into: Set<String>()) { $0.insert($1) }
    }
    #expect(ids.count == 1)
  }

  @Test(
    "a malformed file is replaced and warned about once",
    arguments: [
      "not-a-uuid", "", "   \n",
      "E1C1A2B3-0000-4000-8000-000000000000",  // uppercase: the Android check is lowercase only
      "e1c1a2b3-0000-1000-8000-000000000000",  // version 1
      "e1c1a2b3-0000-4000-c000-000000000000",  // variant c
      "e1c1a2b3-0000-4000-8000-0000000000000",  // one digit too long
    ])
  func malformedFileIsReplaced(content: String) throws {
    try Data(content.utf8).write(to: dir.file(InstallationIdStore.fileName))
    let logger = RecordingLogger()
    let replacement = "11111111-2222-4333-8444-555555555555"
    let id = try InstallationIdStore(directory: dir.url, ids: FixedId(id: replacement)).getOrCreate(logger: logger)
    #expect(id == replacement)
    #expect(logger.warnings.count == 1)
    #expect(try InstallationIdStore(directory: dir.url).getOrCreate(logger: logger) == replacement)
    #expect(logger.warnings.count == 1)
  }

  @Test func fileWithInvalidUtf8IsReplaced() throws {
    try Data([0xFF, 0xFE, 0x00, 0xC3]).write(to: dir.file(InstallationIdStore.fileName))
    let logger = RecordingLogger()
    let id = try InstallationIdStore(directory: dir.url).getOrCreate(logger: logger)
    #expect(isUuidV4(id))
    #expect(logger.warnings.count == 1)
  }

  @Test func valueWithSurroundingWhitespaceIsAccepted() throws {
    let id = "11111111-2222-4333-8444-555555555555"
    try Data("\(id)\n".utf8).write(to: dir.file(InstallationIdStore.fileName))
    #expect(try InstallationIdStore(directory: dir.url).getOrCreate(logger: RecordingLogger()) == id)
  }

  @Test func unreadableFileIsAStorageFailure() throws {
    // A directory where the file should be: it exists, and reading it fails.
    try FileManager.default.createDirectory(
      at: dir.file(InstallationIdStore.fileName), withIntermediateDirectories: false)
    #expect {
      try InstallationIdStore(directory: dir.url).getOrCreate(logger: RecordingLogger())
    } throws: {
      ($0 as? StorageError)?.kind == .file
    }
  }

  @Test func unwritableDirectoryIsAStorageFailure() throws {
    try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.url.path)
    #expect {
      try InstallationIdStore(directory: dir.url).getOrCreate(logger: RecordingLogger())
    } throws: {
      ($0 as? StorageError)?.kind == .file
    }
    #expect(!FileManager.default.fileExists(atPath: dir.file(InstallationIdStore.fileName).path))
  }

  @Test func fileIsExcludedFromBackup() throws {
    _ = try InstallationIdStore(directory: dir.url).getOrCreate(logger: RecordingLogger())
    let values = try dir.file(InstallationIdStore.fileName).resourceValues(forKeys: [.isExcludedFromBackupKey])
    #expect(values.isExcludedFromBackup == true)
  }

  /// DEVICE-ONLY check. On the Simulator the system reports this class for every file, whatever the app sets, so
  /// this test passes there without proving that the SDK set it. It checks the class on a device.
  @Test func fileHasTheFirstUserAuthenticationClass() throws {
    _ = try InstallationIdStore(directory: dir.url).getOrCreate(logger: RecordingLogger())
    let values = try dir.file(InstallationIdStore.fileName).resourceValues(forKeys: [.fileProtectionKey])
    #expect(values.fileProtection == .completeUntilFirstUserAuthentication)
  }

  @Test func storageDirectoryIsCreatedOnce() throws {
    let first = try StorageDirectory.prepare(in: dir.url)
    let second = try StorageDirectory.prepare(in: dir.url)
    #expect(first == second)
    #expect(first.lastPathComponent == "signaldock")
    let values = try first.resourceValues(forKeys: [.isExcludedFromBackupKey])
    #expect(values.isExcludedFromBackup == true)  // the whole directory (owner decision 2026-10-28)
    var isDirectory: ObjCBool = false
    #expect(FileManager.default.fileExists(atPath: first.path, isDirectory: &isDirectory) && isDirectory.boolValue)
  }
}
