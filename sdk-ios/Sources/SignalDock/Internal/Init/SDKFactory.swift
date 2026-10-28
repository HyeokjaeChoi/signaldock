import Foundation

/// The one composition point: objects are connected by constructors (Q87). Delivery joins in ticket 09.
@available(iOSApplicationExtension, unavailable)
internal enum SDKFactory {
  /// Opens the storage and builds the instance. Anything it throws becomes `InitError.storageFailure`; an existing
  /// queue and installation ID are kept after a failure (ADR-0003 item 6).
  ///
  /// - Parameter base: the folder that holds `signaldock/`; the Application Support folder of the app by default.
  internal static func create(
    config: SignalDockConfig,
    base: URL? = nil,
    clock: any MillisClock = SystemMillisClock(),
    ids: any IdGenerator = RandomUuidV4(),
    installationIds: any IdGenerator = RandomUuidV4(),
    limits: QueueLimits = QueueLimits(),
    logger: (any SDKLogger)? = nil
  ) async throws -> SignalDockSDK {
    let logger = logger ?? OSLogSink(level: config.logLevel)
    // Small file work, once. It runs off the cooperative pool, like the SQLite and opt-out actors.
    let files = try await Task.detached(priority: .utility) { () throws -> (URL, String, OptOutStore) in
      let directory: URL
      if let base {
        directory = try StorageDirectory.prepare(in: base)
      } else {
        directory = try StorageDirectory.applicationSupport()
      }
      let installationId = try InstallationIdStore(directory: directory, ids: installationIds)
        .getOrCreate(logger: logger)
      return (directory, installationId, OptOutStore(directory: directory))
    }.value
    let queue = try await QueueStore.open(directory: files.0)
    do {
      _ = try await queue.usage()  // open now, so a storage failure shows during initialization
    } catch {
      await queue.close()  // clean up the failed attempt; the queue and the installation ID stay (ADR-0003)
      throw error
    }
    logger.log(.initialized)
    return SignalDockSDK(
      collector: EventCollector(
        installationId: files.1, sink: queue, clock: clock, ids: ids, limits: limits, logger: logger,
        optOut: files.2),
      queue: queue, logger: logger, optOut: files.2, notice: DeprecationNotice(), config: config)
  }
}
