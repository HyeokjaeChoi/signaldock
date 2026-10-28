import Foundation
import SQLite3

/// The queue on SQLite (ADR-0003, ADR-0047 D7, D9). Same tables, limits, and transaction boundaries as the
/// Android `QueueDao`.
///
/// An actor on a dedicated serial executor owns the connection, so SQLite never runs on the cooperative pool.
/// Each method is synchronous inside the actor and has no `await`, so no other call runs between its
/// statements; each write is also one SQLite transaction, so a crash leaves all of it or none of it.
/// Every method throws `StorageError` and nothing else.
internal actor QueueStore {
  private let executor = StorageExecutor()
  private var database: SQLiteDatabase?

  internal nonisolated var unownedExecutor: UnownedSerialExecutor { executor.asUnownedSerialExecutor() }

  private init() {}

  /// Opens `queue.db` in `directory` (which must exist) and applies the migrations.
  ///
  /// Opening is eager, never lazy (ADR-0047 amendment). A failure, for example before the first unlock after a
  /// restart, throws `StorageError`, and the caller reports `InitError.StorageFailure` with no instance.
  internal static func open(
    directory: URL, migrations: URL? = Migrator.bundledDirectory
  ) async throws -> QueueStore {
    let store = QueueStore()
    try await store.openDatabase(path: directory.appendingPathComponent("queue.db").path, migrations: migrations)
    return store
  }

  private func openDatabase(path: String, migrations: URL?) throws {
    let opened = try SQLiteDatabase(path: path)
    do {
      // WAL like the Android Room queue. `-wal` and `-shm` get the file class from the open flag.
      let mode = try opened.query("PRAGMA journal_mode = WAL") { $0.text(0) }
      guard mode.first?.lowercased() == "wal" else { throw StorageError(.sqlite, SQLITE_ERROR) }
      try Migrator.migrate(opened, directory: migrations)
    } catch {
      opened.close()  // a failed attempt keeps the files and releases the connection
      throw error
    }
    database = opened
  }

  /// Closes the connection. Later calls throw `StorageError.Kind.closed`.
  internal func close() {
    database?.close()
    database = nil
  }

  private func connection() throws -> SQLiteDatabase {
    guard let database else { throw StorageError(.closed) }
    return database
  }

  private func write<T>(_ body: (SQLiteDatabase) throws -> T) throws -> T {
    let db = try connection()
    return try db.transaction { try body(db) }
  }

  // MARK: Events

  /// Limit check and insert in one transaction (spec section 4: atomic). A duplicate `eventId` throws.
  internal func addWithinLimits(_ event: EventRow, limits: QueueLimits) throws -> AddOutcome {
    try write { db in
      let used = try Self.usage(db)
      if used.count + 1 > limits.maxEvents || used.bytes + Int64(event.sizeBytes) > limits.maxQueueBytes {
        return .queueFull
      }
      try db.execute(
        """
        INSERT INTO events (eventId, installationId, contractVersion, payloadJson, sizeBytes, createdAtMillis)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        [
          .text(event.eventId), .text(event.installationId), .text(event.contractVersion),
          .text(event.payloadJson), .int(event.sizeBytes), .int(event.createdAtMillis),
        ])
      return .added
    }
  }

  internal func usage() throws -> QueueUsage { try Self.usage(connection()) }

  /// Oldest events, in insertion order.
  internal func oldest(limit: Int) throws -> [EventRow] {
    try connection().query("\(Self.eventColumns) ORDER BY seq LIMIT ?", [.int(limit)], Self.event)
  }

  /// Oldest events of one batch group. A request has one contract header and one installationId.
  internal func oldestOfGroup(contractVersion: String, installationId: String, limit: Int) throws -> [EventRow] {
    try connection().query(
      "\(Self.eventColumns) WHERE contractVersion = ? AND installationId = ? ORDER BY seq LIMIT ?",
      [.text(contractVersion), .text(installationId), .int(limit)], Self.event)
  }

  /// Host-requested clear (ADR-0034). Returns how many events were deleted.
  internal func deleteAll() throws -> Int { try write { try $0.execute("DELETE FROM events") } }

  internal func deleteByIds(_ ids: [String]) throws -> Int { try write { try Self.deleteEvents($0, ids) } }

  // MARK: Rejections and ACK

  /// Stores diagnostics and removes the queue entries in one transaction.
  internal func rejectPermanently(_ rejections: [RejectionRow]) throws {
    try write { db in
      try Self.insertRejections(db, rejections)
      try Self.deleteEvents(db, rejections.map(\.eventId))
    }
  }

  /// ACK application (architecture "Collection, delivery, and ACK"): queue removal, permanent rejection
  /// diagnostics, the dropped count, and the retry state reset commit together or not at all.
  /// `removeIds` holds every event of the batch that the valid ACK settles, rejected ones included.
  internal func applyAck(removeIds: [String], rejections: [RejectionRow], state: RetryStateRow) throws {
    try write { db in
      if !rejections.isEmpty {
        try Self.insertRejections(db, rejections)
        try Self.addDropped(db, reason: serverRejectedReason, count: rejections.count)  // same commit (ADR-0036)
      }
      try Self.deleteEvents(db, removeIds)
      try Self.saveRetryState(db, state)
    }
  }

  internal func recentRejections() throws -> [RejectionRow] { try Self.recentRejections(connection()) }

  // MARK: Dropped counts

  /// Counters above zero, ordered by reason.
  internal func droppedCounts() throws -> [DroppedCountRow] {
    try connection().query("SELECT reason, count FROM dropped_counts WHERE count > 0 ORDER BY reason") {
      DroppedCountRow(reason: $0.text(0), count: $0.int(1))
    }
  }

  /// Adds `count` to a counter. It stops at `maxDroppedCount` and never overflows.
  internal func addDropped(reason: String, count: Int) throws {
    try write { try Self.addDropped($0, reason: reason, count: count) }
  }

  /// Removes counts that a report carried. Drops counted during the HTTP call stay. A counter that was
  /// saturated while the report was in flight loses only what was sent.
  internal func subtractDropped(_ sent: [DroppedCountRow]) throws {
    try write { db in
      for row in sent {
        let left = try Self.droppedCount(db, row.reason) - row.count
        if left > 0 {
          try Self.putDropped(db, DroppedCountRow(reason: row.reason, count: left))
        } else {
          try db.execute("DELETE FROM dropped_counts WHERE reason = ?", [.text(row.reason)])
        }
      }
    }
  }

  // MARK: Retry state and diagnostics

  internal func retryState() throws -> RetryStateRow? { try Self.retryState(connection()) }

  internal func saveRetryState(_ state: RetryStateRow) throws { try write { try Self.saveRetryState($0, state) } }

  /// Usage, retry state, and rejections in one transaction: one consistent snapshot.
  internal func diagnosticsSnapshot() throws -> DiagnosticsSnapshot {
    try write { db in
      DiagnosticsSnapshot(
        usage: try Self.usage(db), retry: try Self.retryState(db), rejections: try Self.recentRejections(db))
    }
  }

  // MARK: SQL

  private static let eventColumns =
    "SELECT seq, eventId, installationId, contractVersion, payloadJson, sizeBytes, createdAtMillis FROM events"

  private static func event(_ row: SQLiteRow) -> EventRow {
    EventRow(
      seq: row.int64(0), eventId: row.text(1), installationId: row.text(2), contractVersion: row.text(3),
      payloadJson: row.text(4), sizeBytes: row.int(5), createdAtMillis: row.int64(6))
  }

  private static func usage(_ db: SQLiteDatabase) throws -> QueueUsage {
    let rows = try db.query(
      "SELECT COUNT(*), COALESCE(SUM(sizeBytes), 0), MIN(createdAtMillis) FROM events"
    ) { QueueUsage(count: $0.int(0), bytes: $0.int64(1), oldestCreatedAtMillis: $0.optionalInt64(2)) }
    return rows[0]
  }

  /// SQLite allows at least 999 parameters per statement; a batch stays well below, but chunk anyway.
  @discardableResult
  private static func deleteEvents(_ db: SQLiteDatabase, _ ids: [String]) throws -> Int {
    var deleted = 0
    for start in stride(from: 0, to: ids.count, by: 500) {
      let chunk = ids[start..<min(start + 500, ids.count)]
      let marks = Array(repeating: "?", count: chunk.count).joined(separator: ", ")
      deleted += try db.execute("DELETE FROM events WHERE eventId IN (\(marks))", chunk.map { .text($0) })
    }
    return deleted
  }

  private static func insertRejections(_ db: SQLiteDatabase, _ rejections: [RejectionRow]) throws {
    for r in rejections {
      try db.execute(
        "INSERT INTO rejections (eventId, code, rejectedAtMillis) VALUES (?, ?, ?)",
        [.text(r.eventId), .text(r.code), .int(r.rejectedAtMillis)])
    }
    try db.execute(
      "DELETE FROM rejections WHERE id NOT IN (SELECT id FROM rejections ORDER BY id DESC LIMIT ?)",
      [.int(maxRejections)])
  }

  private static func recentRejections(_ db: SQLiteDatabase) throws -> [RejectionRow] {
    try db.query("SELECT id, eventId, code, rejectedAtMillis FROM rejections ORDER BY id DESC") {
      RejectionRow(id: $0.int64(0), eventId: $0.text(1), code: $0.text(2), rejectedAtMillis: $0.int64(3))
    }
  }

  private static func droppedCount(_ db: SQLiteDatabase, _ reason: String) throws -> Int {
    try db.query("SELECT count FROM dropped_counts WHERE reason = ?", [.text(reason)]) { $0.int(0) }.first ?? 0
  }

  private static func putDropped(_ db: SQLiteDatabase, _ row: DroppedCountRow) throws {
    try db.execute(
      "INSERT OR REPLACE INTO dropped_counts (reason, count) VALUES (?, ?)", [.text(row.reason), .int(row.count)])
  }

  private static func addDropped(_ db: SQLiteDatabase, reason: String, count: Int) throws {
    let now = try droppedCount(db, reason)
    let total = count >= maxDroppedCount - now ? maxDroppedCount : now + count
    try putDropped(db, DroppedCountRow(reason: reason, count: total))
  }

  private static func retryState(_ db: SQLiteDatabase) throws -> RetryStateRow? {
    try db.query(
      """
      SELECT failureCount, nextAttemptAtMillis, waitMillis, lastAckAtMillis, lastAckAccepted, lastAckDuplicate,
             lastAckRejected, lastErrorKind, lastErrorHttpStatus, lastErrorAtMillis, hold, maxBatchEvents
      FROM retry_state WHERE id = 1
      """
    ) {
      RetryStateRow(
        failureCount: $0.int(0), nextAttemptAtMillis: $0.optionalInt64(1), waitMillis: $0.optionalInt64(2),
        lastAckAtMillis: $0.optionalInt64(3), lastAckAccepted: $0.int(4), lastAckDuplicate: $0.int(5),
        lastAckRejected: $0.int(6), lastErrorKind: $0.optionalText(7), lastErrorHttpStatus: $0.optionalInt(8),
        lastErrorAtMillis: $0.optionalInt64(9), hold: $0.int(10) != 0, maxBatchEvents: $0.optionalInt(11))
    }.first
  }

  private static func saveRetryState(_ db: SQLiteDatabase, _ s: RetryStateRow) throws {
    try db.execute(
      """
      INSERT OR REPLACE INTO retry_state (id, failureCount, nextAttemptAtMillis, waitMillis, lastAckAtMillis,
        lastAckAccepted, lastAckDuplicate, lastAckRejected, lastErrorKind, lastErrorHttpStatus, lastErrorAtMillis,
        hold, maxBatchEvents)
      VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      [
        .int(s.failureCount), .optional(s.nextAttemptAtMillis), .optional(s.waitMillis),
        .optional(s.lastAckAtMillis), .int(s.lastAckAccepted), .int(s.lastAckDuplicate), .int(s.lastAckRejected),
        .optional(s.lastErrorKind), .optional(s.lastErrorHttpStatus), .optional(s.lastErrorAtMillis),
        .int(s.hold ? 1 : 0), .optional(s.maxBatchEvents),
      ])
  }
}
