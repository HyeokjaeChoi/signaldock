import Foundation
import Testing

@testable import SignalDock

/// Open and read failures (ADR-0017, ADR-0047 amendment) and the Data Protection class of the queue files.
@Suite final class QueueStoreFailureTests {
  let dir: TempDirectory

  init() throws { dir = try TempDirectory() }

  private var databasePath: String { dir.file("queue.db").path }

  // MARK: Open failure is an init failure

  @Test func aFileThatIsNotADatabaseFailsTheOpenAndStaysUntouched() async throws {
    let garbage = Data("this is not a database; it is long enough to be read as a header, but not a header".utf8)
    try garbage.write(to: dir.file("queue.db"))
    // SQLITE_NOTADB (26). The open fails: there is no instance and no later lazy open.
    await #expect(throws: StorageError(.sqlite, 26)) { _ = try await QueueStore.open(directory: dir.url) }
    #expect(try Data(contentsOf: dir.file("queue.db")) == garbage)  // the SDK does not delete or repair it
  }

  @Test func anEmptyFileIsANewDatabase() async throws {
    try Data().write(to: dir.file("queue.db"))
    let store = try await QueueStore.open(directory: dir.url)
    #expect(try await store.usage().count == 0)
  }

  @Test func aDatabaseWithADamagedPageFailsReadsAsStorageErrors() async throws {
    let store = try await QueueStore.open(directory: dir.url)
    for n in 1...20 { _ = try await store.addWithinLimits(makeEvent(n), limits: .init()) }
    await store.close()  // closing the last connection checkpoints the WAL into the main file
    // Page 2 is the root page of `events`. Overwrite it.
    let handle = try FileHandle(forUpdating: dir.file("queue.db"))
    try handle.seek(toOffset: 4_096)
    try handle.write(contentsOf: Data(repeating: 0xFF, count: 4_096))
    try handle.close()

    let damaged = try await QueueStore.open(directory: dir.url)  // the header page is fine: the open works
    await #expect {
      _ = try await damaged.usage()
    } throws: {
      guard let error = $0 as? StorageError else { return false }
      return error.kind == .sqlite && error.code & 0xFF == 11  // SQLITE_CORRUPT
    }
    await #expect(throws: StorageError.self) { _ = try await damaged.addWithinLimits(makeEvent(99), limits: .init()) }
    await #expect(throws: StorageError.self) { _ = try await damaged.diagnosticsSnapshot() }
  }

  @Test func aMissingDirectoryFailsTheOpen() async throws {
    // SQLITE_CANTOPEN (14).
    await #expect(throws: StorageError(.sqlite, 14)) { _ = try await QueueStore.open(directory: dir.file("missing")) }
  }

  @Test func anUnwritableDirectoryFailsTheOpen() async throws {
    try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.url.path)
    await #expect(throws: StorageError(.sqlite, 14)) { _ = try await QueueStore.open(directory: dir.url) }
  }

  @Test func aDatabaseFileWithoutPermissionFailsTheOpen() async throws {
    let store = try await QueueStore.open(directory: dir.url)
    await store.close()
    #expect(chmod(databasePath, 0o000) == 0)
    // The same kind of failure as the open before the first unlock: the system refuses the file.
    await #expect(throws: StorageError(.sqlite, 14)) { _ = try await QueueStore.open(directory: dir.url) }
    chmod(databasePath, 0o600)
    #expect(try await QueueStore.open(directory: dir.url).usage().count == 0)  // nothing was lost
  }

  @Test func aFailedMigrationFailsTheOpenAndKeepsTheStoredEvents() async throws {
    let store = try await QueueStore.open(directory: dir.url)
    _ = try await store.addWithinLimits(makeEvent(1), limits: .init())
    await store.close()
    let broken = dir.file("migrations")
    try FileManager.default.createDirectory(at: broken, withIntermediateDirectories: true)
    try Data("CREATE TABLE x (a INTEGER); SELECT * FROM nope;".utf8).write(
      to: broken.appendingPathComponent("V1__a.sql"))
    try Data("CREATE TABLE y (a INTEGER); SELECT * FROM nope;".utf8).write(
      to: broken.appendingPathComponent("V2__b.sql"))
    // The stored database is at version 1, so V2 runs and fails.
    await #expect { _ = try await QueueStore.open(directory: dir.url, migrations: broken) } throws: {
      ($0 as? StorageError)?.kind == .sqlite
    }
    #expect(try await QueueStore.open(directory: dir.url).oldest(limit: 10).map(\.eventId) == ["event-1"])
  }

  // MARK: Data Protection class

  /// On the Simulator the system reports this class for every file, whatever the app sets (checked on iOS 27.0:
  /// a file set to `complete` still reads back `completeUntilFirstUserAuthentication`). These tests pass there
  /// without proving that the open flag worked; they check the class on a device or a runtime that stores it.
  private func protectionClass(_ name: String) throws -> URLFileProtection? {
    try dir.file(name).resourceValues(forKeys: [.fileProtectionKey]).fileProtection
  }

  @Test func databaseAndSideFilesHaveTheFirstUserAuthenticationClass() async throws {
    let store = try await QueueStore.open(directory: dir.url)
    _ = try await store.addWithinLimits(makeEvent(1), limits: .init())
    for name in ["queue.db", "queue.db-wal", "queue.db-shm"] {
      #expect(try protectionClass(name) == .completeUntilFirstUserAuthentication, "\(name)")
    }
    await store.close()
  }

  @Test func aSideFileThatSqliteMakesAgainHasTheClass() async throws {
    let first = try await QueueStore.open(directory: dir.url)
    _ = try await first.addWithinLimits(makeEvent(1), limits: .init())
    await first.close()
    // After a close the side files stay (empty) on this SQLite, so remove them: SQLite must make them again.
    for suffix in ["-wal", "-shm"] { try FileManager.default.removeItem(atPath: databasePath + suffix) }
    let second = try await QueueStore.open(directory: dir.url)
    _ = try await second.addWithinLimits(makeEvent(2), limits: .init())
    for suffix in ["-wal", "-shm"] {
      #expect(FileManager.default.fileExists(atPath: databasePath + suffix), "\(suffix) is a new file")
      #expect(try protectionClass("queue.db" + suffix) == .completeUntilFirstUserAuthentication, "\(suffix)")
    }
    await second.close()
  }

  @Test func theRollbackJournalHasTheClass() throws {
    // `-journal` exists only outside WAL mode. A connection from `SQLiteDatabase` has the same open flag.
    let db = try rawConnection(dir.url)
    try db.executeScript("PRAGMA journal_mode = DELETE; CREATE TABLE t (a INTEGER);")
    try db.executeScript("BEGIN; INSERT INTO t VALUES (1);")
    #expect(FileManager.default.fileExists(atPath: databasePath + "-journal"))
    #expect(try protectionClass("queue.db-journal") == .completeUntilFirstUserAuthentication)
    try db.executeScript("ROLLBACK")
  }
}
