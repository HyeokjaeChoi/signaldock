import Foundation
import Testing

@testable import SignalDock

/// The Android `QueueDao` rules (ADR-0003, ADR-0006, ADR-0036), one test per rule.
@Suite final class QueueStoreTests {
  let dir: TempDirectory

  init() throws { dir = try TempDirectory() }

  private func open() async throws -> QueueStore { try await QueueStore.open(directory: dir.url) }

  // MARK: Schema

  @Test func firstOpenCreatesTheAndroidSchemaAtVersion1() async throws {
    let store = try await open()
    await store.close()
    let raw = try rawConnection(dir.url)
    #expect(try raw.userVersion() == 1)
    let expected: [String: [String]] = [
      "events": ["seq", "eventId", "installationId", "contractVersion", "payloadJson", "sizeBytes", "createdAtMillis"],
      "rejections": ["id", "eventId", "code", "rejectedAtMillis"],
      "retry_state": [
        "id", "failureCount", "nextAttemptAtMillis", "waitMillis", "lastAckAtMillis", "lastAckAccepted",
        "lastAckDuplicate", "lastAckRejected", "lastErrorKind", "lastErrorHttpStatus", "lastErrorAtMillis", "hold",
        "maxBatchEvents",
      ],
      "dropped_counts": ["reason", "count"],
    ]
    for (table, columns) in expected {
      #expect(try raw.query("PRAGMA table_info(\(table))") { $0.text(1) } == columns, "\(table)")
    }
    let tables = try raw.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'") {
      $0.text(0)
    }
    #expect(Set(tables) == Set(expected.keys))
    let unique = try raw.query("SELECT sql FROM sqlite_master WHERE name = 'index_events_eventId'") { $0.text(0) }
    #expect(unique.first?.contains("UNIQUE") == true)
  }

  @Test func databaseUsesWal() async throws {
    let store = try await open()
    _ = try await store.addWithinLimits(makeEvent(1), limits: QueueLimits())
    let mode = try rawConnection(dir.url).query("PRAGMA journal_mode") { $0.text(0) }
    #expect(mode == ["wal"])
    await store.close()
  }

  @Test func closedStoreThrowsStorageError() async throws {
    let store = try await open()
    await store.close()
    await #expect(throws: StorageError(.closed)) { _ = try await store.usage() }
    await #expect(throws: StorageError(.closed)) { _ = try await store.addWithinLimits(makeEvent(1), limits: .init()) }
  }

  // MARK: Limits

  @Test func eventCountLimitRejectsTheNewEventAndKeepsTheOldOnes() async throws {
    let store = try await open()
    let limits = QueueLimits(maxEvents: 3)
    for n in 1...3 { #expect(try await store.addWithinLimits(makeEvent(n), limits: limits) == .added) }
    #expect(try await store.addWithinLimits(makeEvent(4), limits: limits) == .queueFull)
    #expect(try await store.oldest(limit: 10).map(\.eventId) == ["event-1", "event-2", "event-3"])
  }

  @Test func byteLimitAcceptsExactlyTheLimitAndRejectsOneMore() async throws {
    let store = try await open()
    let limits = QueueLimits(maxQueueBytes: 300)
    #expect(try await store.addWithinLimits(makeEvent(1, size: 100), limits: limits) == .added)
    #expect(try await store.addWithinLimits(makeEvent(2, size: 200), limits: limits) == .added)  // 300 = limit
    #expect(try await store.addWithinLimits(makeEvent(3, size: 1), limits: limits) == .queueFull)  // 301
    #expect(try await store.usage() == QueueUsage(count: 2, bytes: 300, oldestCreatedAtMillis: 1_001))
  }

  @Test func defaultLimitsAreTheAndroidLimits() {
    let limits = QueueLimits()
    #expect(limits.maxEvents == 10_000)
    #expect(limits.maxQueueBytes == 16 * 1024 * 1024)
    #expect(limits.maxEventBytes == 16 * 1024)
  }

  @Test func duplicateEventIdThrowsAndStoresNothing() async throws {
    let store = try await open()
    _ = try await store.addWithinLimits(makeEvent(1), limits: .init())
    await #expect {
      _ = try await store.addWithinLimits(makeEvent(1), limits: .init())
    } throws: {
      ($0 as? StorageError)?.kind == .sqlite
    }
    #expect(try await store.usage().count == 1)
  }

  @Test func usageOfAnEmptyQueue() async throws {
    #expect(try await open().usage() == QueueUsage(count: 0, bytes: 0, oldestCreatedAtMillis: nil))
  }

  // MARK: Reads and deletes

  @Test func oldestIsInInsertionOrderAndLimited() async throws {
    let store = try await open()
    for n in [3, 1, 2] { _ = try await store.addWithinLimits(makeEvent(n), limits: .init()) }
    #expect(try await store.oldest(limit: 2).map(\.eventId) == ["event-3", "event-1"])
  }

  @Test func oldestOfGroupFiltersByContractVersionAndInstallationId() async throws {
    let store = try await open()
    let rows = [
      makeEvent(1, installationId: "a", contractVersion: "1"), makeEvent(2, installationId: "b", contractVersion: "1"),
      makeEvent(3, installationId: "a", contractVersion: "2"), makeEvent(4, installationId: "a", contractVersion: "1"),
    ]
    for row in rows { _ = try await store.addWithinLimits(row, limits: .init()) }
    let group = try await store.oldestOfGroup(contractVersion: "1", installationId: "a", limit: 10)
    #expect(group.map(\.eventId) == ["event-1", "event-4"])
    #expect(try await store.oldestOfGroup(contractVersion: "1", installationId: "a", limit: 1).count == 1)
  }

  @Test func rowsKeepEveryFieldAndTheExactPayload() async throws {
    let store = try await open()
    let payload = "{\"name\":\"caf\u{E9} \u{1F600}\",\"q\":\"a'b\\\"c\",\"nul\":\"\\u0000\"}"
    var row = makeEvent(1)
    row.payloadJson = payload
    row.sizeBytes = payload.utf8.count
    _ = try await store.addWithinLimits(row, limits: .init())
    let stored = try await store.oldest(limit: 1)[0]
    #expect(stored.payloadJson == payload)
    #expect(stored.seq > 0)
    row.seq = stored.seq
    #expect(stored == row)
  }

  @Test func deleteAllReturnsTheCount() async throws {
    let store = try await open()
    for n in 1...4 { _ = try await store.addWithinLimits(makeEvent(n), limits: .init()) }
    #expect(try await store.deleteAll() == 4)
    #expect(try await store.deleteAll() == 0)
    #expect(try await store.usage().count == 0)
  }

  @Test func deleteByIdsRemovesOnlyThoseIdsAlsoBeyondOneChunk() async throws {
    let store = try await open()
    for n in 1...700 { _ = try await store.addWithinLimits(makeEvent(n), limits: .init()) }
    let ids = (1...600).map { "event-\($0)" } + ["unknown"]
    #expect(try await store.deleteByIds(ids) == 600)
    #expect(try await store.oldest(limit: 10).first?.eventId == "event-601")
    #expect(try await store.usage().count == 100)
    #expect(try await store.deleteByIds([]) == 0)
  }

  // MARK: Rejections and ACK

  @Test func permanentRejectionStoresNoPayloadRemovesTheEventAndKeepsTheLast100() async throws {
    let store = try await open()
    for n in 1...105 {
      var row = makeEvent(n)
      row.payloadJson = "{\"secret_name\":\"secret_value\"}"
      _ = try await store.addWithinLimits(row, limits: .init())
    }
    let rows = try await store.oldest(limit: 200)
    let rejections = rows.prefix(103).enumerated().map {
      RejectionRow(eventId: $1.eventId, code: "C\($0)", rejectedAtMillis: 1_000 + Int64($0))
    }
    try await store.rejectPermanently(rejections)
    #expect(try await store.usage().count == 2)
    let kept = try await store.recentRejections()
    #expect(kept.count == 100)
    #expect(kept.first?.code == "C102")  // newest first
    #expect(kept.last?.code == "C3")
    #expect(kept.first?.eventId == rows[102].eventId)
    #expect(!"\(kept)".contains("secret"))
  }

  @Test func ackIsAppliedInOneTransaction() async throws {
    let store = try await open()
    for n in 1...5 { _ = try await store.addWithinLimits(makeEvent(n), limits: .init()) }
    let state = RetryStateRow(failureCount: 0, lastAckAtMillis: 9_000, lastAckAccepted: 3, lastAckRejected: 1)
    try await store.applyAck(
      removeIds: ["event-1", "event-2", "event-3", "event-4"],
      rejections: [RejectionRow(eventId: "event-4", code: "BAD", rejectedAtMillis: 9_000)], state: state)
    #expect(try await store.oldest(limit: 10).map(\.eventId) == ["event-5"])
    #expect(try await store.recentRejections().map(\.code) == ["BAD"])
    #expect(try await store.droppedCounts() == [DroppedCountRow(reason: "server_rejected", count: 1)])
    #expect(try await store.retryState() == state)
  }

  @Test func failedAckLeavesNoPartialChange() async throws {
    let store = try await open()
    for n in 1...3 { _ = try await store.addWithinLimits(makeEvent(n), limits: .init()) }
    // The last statement of the ACK transaction (the retry state) fails: everything before it must roll back.
    let raw = try rawConnection(dir.url)
    try raw.executeScript(
      "CREATE TRIGGER fail_retry BEFORE INSERT ON retry_state BEGIN SELECT RAISE(ABORT, 'planted'); END")
    await #expect {
      try await store.applyAck(
        removeIds: ["event-1", "event-2"],
        rejections: [RejectionRow(eventId: "event-2", code: "BAD", rejectedAtMillis: 1)], state: RetryStateRow())
    } throws: {
      ($0 as? StorageError)?.kind == .sqlite
    }
    #expect(try await store.usage().count == 3)
    #expect(try await store.recentRejections().isEmpty)
    #expect(try await store.droppedCounts().isEmpty)
    #expect(try await store.retryState() == nil)
    // The connection is usable after the rollback.
    try raw.executeScript("DROP TRIGGER fail_retry")
    try await store.applyAck(removeIds: ["event-1"], rejections: [], state: RetryStateRow(failureCount: 2))
    #expect(try await store.usage().count == 2)
    #expect(try await store.retryState()?.failureCount == 2)
  }

  // MARK: Dropped counts

  @Test func droppedCountsAddSaturateAndSubtract() async throws {
    let store = try await open()
    try await store.addDropped(reason: "queue_full", count: 2)
    try await store.addDropped(reason: "queue_full", count: 3)
    try await store.addDropped(reason: "storage_error", count: 1)
    #expect(
      try await store.droppedCounts() == [
        DroppedCountRow(reason: "queue_full", count: 5), DroppedCountRow(reason: "storage_error", count: 1),
      ])
    // Saturation: the counter stops at the maximum and never overflows.
    try await store.addDropped(reason: "invalid_event", count: maxDroppedCount - 1)
    try await store.addDropped(reason: "invalid_event", count: Int.max)
    #expect(try await store.droppedCounts().first { $0.reason == "invalid_event" }?.count == maxDroppedCount)
    // Subtract what a report carried; drops counted meanwhile stay; a counter at zero disappears.
    try await store.subtractDropped([
      DroppedCountRow(reason: "queue_full", count: 4), DroppedCountRow(reason: "storage_error", count: 1),
      DroppedCountRow(reason: "invalid_event", count: 10),
    ])
    let left = try await store.droppedCounts()
    #expect(
      left == [
        DroppedCountRow(reason: "invalid_event", count: maxDroppedCount - 10),
        DroppedCountRow(reason: "queue_full", count: 1),
      ])
  }

  // MARK: Retry state

  @Test func retryStateRoundTripsEveryFieldAndNullsAndSurvivesReopen() async throws {
    let store = try await open()
    #expect(try await store.retryState() == nil)
    let full = RetryStateRow(
      failureCount: 3, nextAttemptAtMillis: 9_000, waitMillis: 4_000, lastAckAtMillis: 7_000, lastAckAccepted: 2,
      lastAckDuplicate: 1, lastAckRejected: 4, lastErrorKind: "TIMEOUT", lastErrorHttpStatus: 429,
      lastErrorAtMillis: 8_000, hold: true, maxBatchEvents: 25)
    try await store.saveRetryState(full)
    #expect(try await store.retryState() == full)
    await store.close()
    let again = try await open()
    #expect(try await again.retryState() == full)
    try await again.saveRetryState(RetryStateRow())  // replaces the row: nulls and defaults
    #expect(try await again.retryState() == RetryStateRow())
    #expect(try rawConnection(dir.url).query("SELECT COUNT(*) FROM retry_state") { $0.int(0) } == [1])
  }

  // MARK: Persistence

  @Test func threeHundredEventsSurviveReopeningWithTheSameContent() async throws {
    let store = try await open()
    for n in 1...300 { _ = try await store.addWithinLimits(makeEvent(n), limits: .init()) }
    let before = try await store.oldest(limit: 2_000)
    await store.close()
    let again = try await open()
    #expect(try await again.oldest(limit: 2_000) == before)
    #expect(try await again.usage().count == 300)
  }

  @Test func diagnosticsSnapshotIsOneConsistentSnapshot() async throws {
    let store = try await open()
    for n in 1...50 { _ = try await store.addWithinLimits(makeEvent(n), limits: .init()) }
    let ids = try await store.oldest(limit: 100).map(\.eventId)
    let writer = Task {
      for (i, id) in ids.enumerated() {
        try await store.rejectPermanently([RejectionRow(eventId: id, code: "C", rejectedAtMillis: Int64(i))])
      }
    }
    // Each rejection moves one item from the queue to the rejections in one transaction, so the two always add
    // up to 50 in a single snapshot. Separate reads could see 51.
    var snapshots = 0
    for _ in 0..<100_000 {
      let snapshot = try await store.diagnosticsSnapshot()
      #expect(snapshot.usage.count + snapshot.rejections.count == 50)
      snapshots += 1
      if snapshot.rejections.count == 50 { break }
    }
    try await writer.value
    #expect(snapshots > 0)
  }

  // MARK: Executor

  @Test func storageCallsRunOnTheDedicatedSerialQueue() async throws {
    let store = try await open()
    let label = await store.currentQueueLabel()
    #expect(label == "local.signaldock.storage")
    #expect(await !store.isOnMainThread())
  }
}

extension QueueStore {
  fileprivate func currentQueueLabel() -> String { String(cString: __dispatch_queue_get_label(nil)) }
  fileprivate func isOnMainThread() -> Bool { Thread.isMainThread }
}
