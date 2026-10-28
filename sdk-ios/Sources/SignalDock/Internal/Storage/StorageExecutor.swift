import Dispatch

/// A serial executor on a dedicated queue, so blocking SQLite calls stay off the cooperative thread pool
/// (ADR-0047 D9).
///
/// This is the iOS 15 form: `enqueue(_: UnownedJob)` and `UnownedJob.runSynchronously(on:)`.
/// `ExecutorJob` needs iOS 17. The class is plain `Sendable`: its only state is an immutable `DispatchQueue`.
internal final class StorageExecutor: SerialExecutor {
  private let queue: DispatchQueue

  internal init(label: String = "local.signaldock.storage") {
    queue = DispatchQueue(label: label, qos: .utility)
  }

  /// The default of this requirement needs iOS 17, so the iOS 15 form implements it.
  internal func asUnownedSerialExecutor() -> UnownedSerialExecutor { UnownedSerialExecutor(ordinary: self) }

  internal func enqueue(_ job: UnownedJob) {
    let executor = asUnownedSerialExecutor()
    queue.async { job.runSynchronously(on: executor) }
  }
}
