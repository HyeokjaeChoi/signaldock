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

  @Test func theConnectionUsesTheAndroidParityBusyTimeout() throws {
    let db = try rawConnection(dir.url)
    #expect(try db.query("PRAGMA busy_timeout") { $0.int(0) } == [2500])
  }

  /// The holder commits as soon as the waiter signals that it is about to write, so the wait is short on
  /// any runner. The 2.5 s value itself is checked in the test above, not by the clock. The waiter raises its own
  /// timeout, because a sanitizer runner can delay the holder's COMMIT past 2.5 s and fail the write with BUSY.
  @Test func aSecondConnectionWaitsForTheWriteLockInsteadOfFailing() async throws {
    let holder = try rawConnection(dir.url)
    try holder.executeScript("CREATE TABLE seed (a INTEGER)")
    try holder.executeScript("BEGIN IMMEDIATE")
    let path = dir.file("queue.db").path
    let (signals, signal) = AsyncStream<Void>.makeStream()
    // The waiter blocks in SQLite's busy handler, so it runs on a Dispatch thread, never on the cooperative pool:
    // a blocked pool thread would delay the holder's COMMIT and every other test that waits for a thread.
    let waiter = Task { () -> Bool in
      await withCheckedContinuation { (done: CheckedContinuation<Bool, Never>) in
        DispatchQueue.global().async {
          // Ends the stream if the open throws, so the test fails instead of waiting forever.
          defer { signal.finish() }
          guard let other = try? SQLiteDatabase(path: path) else { return done.resume(returning: false) }
          _ = try? other.execute("PRAGMA busy_timeout = 10000")
          signal.yield()
          done.resume(returning: (try? other.execute("CREATE TABLE waited (a INTEGER)")) != nil)
        }
      }
    }
    for await _ in signals { break }
    // A short pause lets the waiter reach the lock; the result is the same if it has not yet.
    try await Task.sleep(nanoseconds: 50_000_000)
    try holder.executeScript("COMMIT")
    #expect(await waiter.value)
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
