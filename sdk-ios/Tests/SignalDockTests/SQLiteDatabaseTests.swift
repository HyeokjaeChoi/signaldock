import Foundation
import SQLite3
import Testing

@testable import SignalDock

@Suite final class SQLiteDatabaseTests {
  let dir: TempDirectory

  init() throws { dir = try TempDirectory() }

  /// These check the flags the SDK passes. They run on the Simulator and prove the request, not the effect:
  /// the effect (the class of the files) can only be read on a device.
  @Test func openFlagsRequestTheFirstUserAuthenticationClass() {
    #expect(
      SQLiteDatabase.openFlags & SQLITE_OPEN_FILEPROTECTION_MASK
        == SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION)
    #expect(SQLiteDatabase.openFlags & SQLITE_OPEN_CREATE != 0)
    #expect(SQLiteDatabase.openFlags & SQLITE_OPEN_READWRITE != 0)
  }

  @Test func installationIdIsWrittenWithTheFirstUserAuthenticationClass() {
    #expect(InstallationIdStore.writeOptions.contains(.completeFileProtectionUntilFirstUserAuthentication))
  }

  @Test func aSecondConnectionWaitsForTheWriteLockInsteadOfFailing() async throws {
    let holder = try rawConnection(dir.url)
    try holder.executeScript("CREATE TABLE seed (a INTEGER)")
    try holder.executeScript("BEGIN IMMEDIATE")
    let path = dir.file("queue.db").path
    let waiter = Task.detached { () -> Bool in
      let other = try SQLiteDatabase(path: path)
      return (try? other.execute("CREATE TABLE waited (a INTEGER)")) != nil
    }
    // The result does not depend on this delay: without a busy timeout the waiter fails at once; with it
    // the waiter succeeds for any release time below 2.5 s.
    try await Task.sleep(nanoseconds: 300_000_000)
    try holder.executeScript("COMMIT")
    #expect(try await waiter.value)
  }

  @Test func aFailedCommitRollsBackAndLeavesTheConnectionUsable() throws {
    let db = try rawConnection(dir.url)
    try db.executeScript(
      """
      PRAGMA foreign_keys = ON;
      CREATE TABLE parent (id INTEGER PRIMARY KEY);
      CREATE TABLE child (p INTEGER REFERENCES parent(id) DEFERRABLE INITIALLY DEFERRED);
      """)
    // The violation shows only at COMMIT, which fails and leaves the transaction open.
    #expect {
      try db.transaction { try db.execute("INSERT INTO child VALUES (42)") }
    } throws: {
      ($0 as? StorageError)?.kind == .sqlite
    }
    #expect(try db.query("SELECT COUNT(*) FROM child") { $0.int(0) } == [0])
    _ = try db.transaction { try db.execute("INSERT INTO parent VALUES (1)") }
    #expect(try db.query("SELECT COUNT(*) FROM parent") { $0.int(0) } == [1])
  }
}
