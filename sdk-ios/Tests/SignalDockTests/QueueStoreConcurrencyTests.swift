import Foundation
import Testing

@testable import SignalDock

/// Concurrent `track` and ACK from many tasks. The TSan run of `scripts/verify-ios.sh` runs these too.
@Suite final class QueueStoreConcurrencyTests {
  let dir: TempDirectory

  init() throws { dir = try TempDirectory() }

  @Test func manyTasksAddWithinTheLimitAtomically() async throws {
    let store = try await QueueStore.open(directory: dir.url)
    let limits = QueueLimits(maxEvents: 400)
    // 20 tasks each add 25 events: 500 attempts for 400 places. The limit check and the insert are one
    // transaction, so exactly 400 are added and the queue never goes over.
    let outcomes = try await withThrowingTaskGroup(of: [(String, AddOutcome)].self) { group in
      for task in 0..<20 {
        group.addTask {
          var results: [(String, AddOutcome)] = []
          for i in 0..<25 {
            let row = makeEvent(task * 100 + i)
            results.append((row.eventId, try await store.addWithinLimits(row, limits: limits)))
          }
          return results
        }
      }
      return try await group.reduce(into: []) { $0 += $1 }
    }
    let added = outcomes.filter { $0.1 == .added }.map(\.0)
    #expect(added.count == 400)
    #expect(outcomes.count == 500)
    #expect(try await store.usage().count == 400)
    #expect(Set(try await store.oldest(limit: 1_000).map(\.eventId)) == Set(added))
  }

  @Test func manyTasksApplyAcksWhileOthersTrackAndRead() async throws {
    let store = try await QueueStore.open(directory: dir.url)
    for n in 0..<400 { _ = try await store.addWithinLimits(makeEvent(n), limits: .init()) }
    let ids = try await store.oldest(limit: 400).map(\.eventId)
    try await withThrowingTaskGroup(of: Void.self) { group in
      // 20 ACKs, each settles 20 events and rejects 2 of them.
      for task in 0..<20 {
        let own = Array(ids[(task * 20)..<(task * 20 + 20)])
        group.addTask {
          let rejected = own.prefix(2).map { RejectionRow(eventId: $0, code: "BAD", rejectedAtMillis: 5) }
          try await store.applyAck(
            removeIds: own, rejections: Array(rejected), state: RetryStateRow(failureCount: task))
        }
      }
      // Meanwhile new events arrive and diagnostics are read.
      for task in 0..<10 {
        group.addTask {
          for i in 0..<10 { _ = try await store.addWithinLimits(makeEvent(1_000 + task * 10 + i), limits: .init()) }
        }
        group.addTask { _ = try await store.diagnosticsSnapshot() }
      }
      try await group.waitForAll()
    }
    let usage = try await store.usage()
    #expect(usage.count == 100)  // 400 settled, 100 new
    #expect(try await store.recentRejections().count == 40)
    #expect(try await store.droppedCounts() == [DroppedCountRow(reason: "server_rejected", count: 40)])
    #expect(try await store.retryState() != nil)
  }

  @Test func concurrentDroppedCountsAddUp() async throws {
    let store = try await QueueStore.open(directory: dir.url)
    try await withThrowingTaskGroup(of: Void.self) { group in
      for _ in 0..<50 { group.addTask { try await store.addDropped(reason: "queue_full", count: 2) } }
      try await group.waitForAll()
    }
    #expect(try await store.droppedCounts() == [DroppedCountRow(reason: "queue_full", count: 100)])
  }
}
