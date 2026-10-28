import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// A value bound to a statement parameter.
internal enum SQLiteValue {
  case int(Int64)
  case text(String)
  case null

  internal static func int(_ value: Int) -> SQLiteValue { .int(Int64(value)) }
  internal static func optional(_ value: Int64?) -> SQLiteValue { value.map { .int($0) } ?? .null }
  internal static func optional(_ value: Int?) -> SQLiteValue { value.map { .int(Int64($0)) } ?? .null }
  internal static func optional(_ value: String?) -> SQLiteValue { value.map { .text($0) } ?? .null }
}

/// One result row, valid only inside the `query` closure.
internal struct SQLiteRow {
  fileprivate let statement: OpaquePointer

  internal func int64(_ column: Int32) -> Int64 { sqlite3_column_int64(statement, column) }
  internal func int(_ column: Int32) -> Int { Int(sqlite3_column_int64(statement, column)) }
  internal func optionalInt64(_ column: Int32) -> Int64? {
    sqlite3_column_type(statement, column) == SQLITE_NULL ? nil : int64(column)
  }
  internal func optionalInt(_ column: Int32) -> Int? { optionalInt64(column).map { Int($0) } }
  internal func text(_ column: Int32) -> String {
    guard let bytes = sqlite3_column_text(statement, column) else { return "" }
    let count = Int(sqlite3_column_bytes(statement, column))
    return String(decoding: UnsafeBufferPointer(start: bytes, count: count), as: UTF8.self)
  }
  internal func optionalText(_ column: Int32) -> String? {
    sqlite3_column_type(statement, column) == SQLITE_NULL ? nil : text(column)
  }
}

/// A small wrapper of the system SQLite3 C API (ADR-0047 D7).
///
/// One connection, not thread-safe. Only `QueueStore` uses it, and only on its serial executor, so the
/// connection opens in no-mutex mode. Every failure is a `StorageError`.
internal final class SQLiteDatabase {
  private var handle: OpaquePointer?

  /// Opens or creates the database. The open flag gives the database, `-wal`, `-shm`, and `-journal` files
  /// the class `completeUntilFirstUserAuthentication`, also when SQLite creates them later (ADR-0047 amendment).
  internal static let openFlags: Int32 =
    SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX
    | SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION

  /// How long a statement waits for a lock held by another connection: the Android framework SQLite default.
  internal static let busyTimeoutMillis: Int32 = 2_500

  internal init(path: String) throws {
    var opened: OpaquePointer?
    let result = sqlite3_open_v2(path, &opened, Self.openFlags, nil)
    guard result == SQLITE_OK, let opened else {
      let code = opened.map { sqlite3_extended_errcode($0) } ?? result
      sqlite3_close_v2(opened)
      throw StorageError(.sqlite, code)
    }
    sqlite3_extended_result_codes(opened, 1)
    sqlite3_busy_timeout(opened, Self.busyTimeoutMillis)
    handle = opened
  }

  deinit { close() }

  /// Closes the connection. Later calls throw `StorageError.Kind.closed`.
  internal func close() {
    sqlite3_close_v2(handle)
    handle = nil
  }

  private func connection() throws -> OpaquePointer {
    guard let handle else { throw StorageError(.closed) }
    return handle
  }

  private func failure(_ db: OpaquePointer) -> StorageError { StorageError(.sqlite, sqlite3_extended_errcode(db)) }

  /// Runs one or more statements without parameters (for example a migration file).
  internal func executeScript(_ sql: String) throws {
    let db = try connection()
    guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw failure(db) }
  }

  /// Runs one statement and returns the number of rows it changed.
  @discardableResult
  internal func execute(_ sql: String, _ parameters: [SQLiteValue] = []) throws -> Int {
    _ = try query(sql, parameters) { _ in () }
    return Int(sqlite3_changes(try connection()))
  }

  /// Runs one statement and maps each result row.
  internal func query<T>(
    _ sql: String, _ parameters: [SQLiteValue] = [], _ map: (SQLiteRow) throws -> T
  ) throws -> [T] {
    let db = try connection()
    var prepared: OpaquePointer?
    guard sqlite3_prepare_v2(db, sql, -1, &prepared, nil) == SQLITE_OK, let statement = prepared else {
      let error = failure(db)
      sqlite3_finalize(prepared)
      throw error
    }
    defer { sqlite3_finalize(statement) }
    for (offset, value) in parameters.enumerated() {
      let index = Int32(offset + 1)
      let result: Int32
      switch value {
      case .int(let number): result = sqlite3_bind_int64(statement, index, number)
      case .text(let text): result = sqlite3_bind_text(statement, index, text, Int32(text.utf8.count), sqliteTransient)
      case .null: result = sqlite3_bind_null(statement, index)
      }
      guard result == SQLITE_OK else { throw failure(db) }
    }
    var rows: [T] = []
    while true {
      switch sqlite3_step(statement) {
      case SQLITE_ROW: rows.append(try map(SQLiteRow(statement: statement)))
      case SQLITE_DONE: return rows
      default: throw failure(db)
      }
    }
  }

  /// Runs `body` in one write transaction: commit when it returns, roll back when it throws.
  internal func transaction<T>(_ body: () throws -> T) throws -> T {
    try executeScript("BEGIN IMMEDIATE")
    do {
      let result = try body()
      try executeScript("COMMIT")
      return result
    } catch {
      // After a failed COMMIT the transaction may still be open. If it is still open after the ROLLBACK, the
      // connection is unusable: close it, so later calls throw `closed` and do not run inside a stuck transaction.
      try? executeScript("ROLLBACK")
      if let handle, sqlite3_get_autocommit(handle) == 0 { close() }
      throw error
    }
  }

  /// `PRAGMA user_version`: the schema version that the migrations maintain.
  internal func userVersion() throws -> Int { try query("PRAGMA user_version") { $0.int(0) }.first ?? 0 }
}
