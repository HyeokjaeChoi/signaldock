import Foundation
import Testing

@testable import SignalDock

@Suite final class MigratorTests {
  let dir: TempDirectory
  let migrations: URL

  init() throws {
    dir = try TempDirectory()
    migrations = dir.file("migrations")
    try FileManager.default.createDirectory(at: migrations, withIntermediateDirectories: true)
  }

  private func write(_ name: String, _ sql: String) throws {
    try Data(sql.utf8).write(to: migrations.appendingPathComponent(name))
  }

  private func database() throws -> SQLiteDatabase { try SQLiteDatabase(path: dir.file("test.db").path) }

  @Test func bundledMigrationsExistAndStartAtVersion1() throws {
    let bundled = try #require(Migrator.bundledDirectory)
    let names = try FileManager.default.contentsOfDirectory(atPath: bundled.path).sorted()
    #expect(names.first?.hasPrefix("V1__") == true)
    #expect(names.allSatisfy { $0.hasSuffix(".sql") })
  }

  @Test func appliesFilesInVersionOrderAndRecordsTheVersion() throws {
    try write("V10__third.sql", "ALTER TABLE t ADD COLUMN c INTEGER;")  // 10 sorts after 2, not after 1
    try write("V2__second.sql", "ALTER TABLE t ADD COLUMN b INTEGER;")
    try write("V1__first.sql", "CREATE TABLE t (a INTEGER);")
    for n in 3...9 { try write("V\(n)__x.sql", "ALTER TABLE t ADD COLUMN c\(n) INTEGER;") }
    let db = try database()
    try Migrator.migrate(db, directory: migrations)
    #expect(try db.userVersion() == 10)
    #expect(try db.query("PRAGMA table_info(t)") { $0.text(1) }.prefix(3) == ["a", "b", "c3"])
  }

  @Test func rerunningAppliesNothingAndOnlyNewFilesRun() throws {
    try write("V1__first.sql", "CREATE TABLE t (a INTEGER); INSERT INTO t VALUES (1);")
    let db = try database()
    try Migrator.migrate(db, directory: migrations)
    try Migrator.migrate(db, directory: migrations)  // would fail on CREATE TABLE if it ran again
    try write("V2__second.sql", "INSERT INTO t VALUES (2);")
    try Migrator.migrate(db, directory: migrations)
    #expect(try db.userVersion() == 2)
    #expect(try db.query("SELECT a FROM t ORDER BY a") { $0.int(0) } == [1, 2])
  }

  @Test func aFailingFileRollsBackAndKeepsThePreviousVersion() throws {
    try write("V1__first.sql", "CREATE TABLE t (a INTEGER);")
    try write("V2__broken.sql", "CREATE TABLE u (a INTEGER); INSERT INTO nope VALUES (1);")
    let db = try database()
    #expect {
      try Migrator.migrate(db, directory: migrations)
    } throws: {
      ($0 as? StorageError)?.kind == .sqlite
    }
    #expect(try db.userVersion() == 1)
    #expect(try db.query("SELECT name FROM sqlite_master WHERE name = 'u'") { $0.text(0) }.isEmpty)
  }

  @Test func aNewerDatabaseIsAStorageFailureAndIsNotTouched() throws {
    try write("V1__first.sql", "CREATE TABLE t (a INTEGER);")
    let db = try database()
    try db.executeScript("PRAGMA user_version = 5; CREATE TABLE keep (a INTEGER);")
    #expect(throws: StorageError(.schema)) { try Migrator.migrate(db, directory: migrations) }
    #expect(try db.userVersion() == 5)
    #expect(try db.query("SELECT name FROM sqlite_master WHERE name = 'keep'") { $0.text(0) } == ["keep"])
  }

  @Test("a bad migration folder is a storage failure", arguments: ["gap", "duplicate", "name", "empty"])
  func badFolder(kind: String) throws {
    switch kind {
    case "gap":
      try write("V1__a.sql", "SELECT 1;")
      try write("V3__c.sql", "SELECT 1;")
    case "duplicate":
      try write("V1__a.sql", "SELECT 1;")
      try write("V1__b.sql", "SELECT 1;")
    case "name":
      try write("first.sql", "SELECT 1;")
    default:
      break  // an empty folder is valid: version 0, nothing to apply
    }
    let db = try database()
    if kind == "empty" {
      try Migrator.migrate(db, directory: migrations)
      #expect(try db.userVersion() == 0)
    } else {
      #expect(throws: StorageError(.schema)) { try Migrator.migrate(db, directory: migrations) }
    }
  }

  @Test func aMissingFolderIsAStorageFailure() throws {
    let db = try database()
    #expect(throws: StorageError(.schema)) { try Migrator.migrate(db, directory: dir.file("missing")) }
    #expect(throws: StorageError(.schema)) { try Migrator.migrate(db, directory: nil) }
  }
}
