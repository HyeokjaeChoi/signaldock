import Foundation

/// Applies the versioned SQL files `V<n>__<name>.sql` of the `Migrations` resource folder (ADR-0047 D7).
///
/// `PRAGMA user_version` holds the applied version. Each file runs in one transaction together with the
/// version update, so a failed file leaves the database at the previous version. There is no destructive
/// fallback: a database with a newer version than the SDK knows (a downgrade) is a storage failure.
/// A released file is frozen (`scripts/check-repo-rules.sh`); a change needs a new file.
internal enum Migrator {
  /// The migration folder of this package.
  internal static var bundledDirectory: URL? { Bundle.module.url(forResource: "Migrations", withExtension: nil) }

  /// Brings `database` to the newest version in `directory`.
  internal static func migrate(_ database: SQLiteDatabase, directory: URL? = bundledDirectory) throws {
    guard let directory else { throw StorageError(.schema) }
    let migrations = try load(directory)
    let current = try database.userVersion()
    guard current <= (migrations.last?.version ?? 0) else { throw StorageError(.schema) }
    for migration in migrations where migration.version > current {
      try database.transaction {
        try database.executeScript(migration.sql)
        try database.executeScript("PRAGMA user_version = \(migration.version)")
      }
    }
  }

  private static func load(_ directory: URL) throws -> [(version: Int, sql: String)] {
    let names: [String]
    do { names = try FileManager.default.contentsOfDirectory(atPath: directory.path) } catch {
      throw StorageError(.schema)
    }
    var found: [(version: Int, sql: String)] = []
    for name in names where name.hasSuffix(".sql") {
      // `V<n>__<name>.sql`; the number is the schema version.
      guard name.hasPrefix("V"), let version = Int(name.dropFirst().prefix { $0.isNumber }), version > 0 else {
        throw StorageError(.schema)
      }
      guard let sql = try? String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8) else {
        throw StorageError(.schema)
      }
      found.append((version, sql))
    }
    found.sort { $0.version < $1.version }
    // Versions are 1, 2, 3, ... with no gap and no duplicate.
    guard found.enumerated().allSatisfy({ $0.offset + 1 == $0.element.version }) else { throw StorageError(.schema) }
    return found
  }
}
