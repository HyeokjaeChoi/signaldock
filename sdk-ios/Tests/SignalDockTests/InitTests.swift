import Foundation
import Testing

@testable import SignalDock

/// `initialize`: configuration checks (ADR-0035), shared work, caller-only cancellation, and storage failures
/// (ADR-0017), against the Android `InitContractTest`, `LifecycleTest`, and `CrashSafetyTest`.
@Suite struct InitTests {
  static let valid = SignalDockConfig(endpoint: "https://signals.example.com", collectionKey: "key")

  /// Counts builds; `gate` holds them back. The sdk comes from the real factory on a temporary directory.
  final class Builder: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    private var failures: [StorageError]
    let gate: Gate?
    let dir: TempDirectory

    init(gate: Gate? = nil, failures: [StorageError] = []) throws {
      self.gate = gate
      self.failures = failures
      dir = try TempDirectory()
    }

    var builds: Int {
      lock.lock()
      defer { lock.unlock() }
      return count
    }

    func build(_ config: SignalDockConfig) async throws -> SignalDockSDK {
      let failure: StorageError? = {
        lock.lock()
        defer { lock.unlock() }
        count += 1
        return failures.isEmpty ? nil : failures.removeFirst()
      }()
      await gate?.wait()
      if let failure { throw failure }
      return try await SDKFactory.create(config: config, base: dir.url, logger: RecordingLogger())
    }
  }

  private func coordinator(_ builder: Builder) -> InitCoordinator {
    InitCoordinator { try await builder.build($0) }
  }

  // MARK: Results

  @Test func successGivesTheInstanceAndALaterSameConfigReusesIt() async throws {
    let builder = try Builder()
    let init1 = coordinator(builder)
    guard case .success(let first) = try await init1.initialize(Self.valid) else { Issue.record("success"); return }
    guard case .success(let second) = try await init1.initialize(Self.valid) else { Issue.record("reuse"); return }
    #expect(first === second)
    #expect(builder.builds == 1)
  }

  @Test func aDifferentConfigAfterSuccessIsAConflictAndKeepsTheInstance() async throws {
    let builder = try Builder()
    let init1 = coordinator(builder)
    guard case .success(let first) = try await init1.initialize(Self.valid) else { Issue.record("success"); return }
    var other = Self.valid
    other.logLevel = .debug
    #expect(try await init1.initialize(other).error == .configurationConflict)
    guard case .success(let again) = try await init1.initialize(Self.valid) else { Issue.record("kept"); return }
    #expect(again === first)
  }

  @available(iOS 16.0, *)
  @Test(.timeLimit(.minutes(5))) func sameConfigCallsShareOneAttemptAndADifferentOneConflictsWhileItRuns() async throws
  {
    let gate = Gate()
    let builder = try Builder(gate: gate)
    let init1 = coordinator(builder)
    async let a = init1.initialize(Self.valid)
    async let b = init1.initialize(Self.valid)
    // Both callers are waiting for the one attempt before it is released.
    while await init1.waiterCount < 2 { try await Task.sleep(nanoseconds: 2_000_000) }
    var other = Self.valid
    other.collectionKey = "other"
    #expect(try await init1.initialize(other).error == .configurationConflict)
    await gate.open()
    guard case .success(let first) = try await a, case .success(let second) = try await b else {
      Issue.record("both succeed")
      return
    }
    #expect(first === second)
    #expect(builder.builds == 1)
  }

  @available(iOS 16.0, *)
  @Test(.timeLimit(.minutes(5))) func cancellingACallerCancelsOnlyItsOwnWait() async throws {
    let gate = Gate()
    let builder = try Builder(gate: gate)
    let init1 = coordinator(builder)
    let cancelled = Task { () async -> Bool in
      do {
        _ = try await init1.initialize(Self.valid)
        return false
      } catch {
        return true  // CancellationError
      }
    }
    let kept = Task { try await init1.initialize(Self.valid) }
    while await init1.waiterCount < 2 { try await Task.sleep(nanoseconds: 2_000_000) }
    cancelled.cancel()
    #expect(await cancelled.value, "the cancelled caller leaves with CancellationError while the attempt runs")
    #expect(await init1.waiterCount == 1)
    await gate.open()
    guard case .success = try await kept.value else { Issue.record("the other caller still gets the result"); return }
    #expect(builder.builds == 1)
  }

  @available(iOS 16.0, *)
  @Test(.timeLimit(.minutes(5))) func theAttemptContinuesWhenEveryWaiterLeaves() async throws {
    let gate = Gate()
    let builder = try Builder(gate: gate)
    let init1 = coordinator(builder)
    let only = Task { () async -> Bool in
      do {
        _ = try await init1.initialize(Self.valid)
        return false
      } catch { return true }
    }
    while await init1.waiterCount < 1 { try await Task.sleep(nanoseconds: 2_000_000) }
    only.cancel()
    #expect(await only.value)
    await gate.open()
    // The attempt was not cancelled: the next call finds the instance or the finished attempt, and builds nothing new.
    guard case .success = try await init1.initialize(Self.valid) else { Issue.record("success"); return }
    #expect(builder.builds == 1)
  }

  @Test func aCallerCancelledBeforeItWaitsGetsCancellationButStartsTheAttempt() async throws {
    let builder = try Builder()
    let init1 = coordinator(builder)
    guard case .failure = await runCancelled({ try await init1.initialize(Self.valid) }) else {
      Issue.record("expected CancellationError")
      return
    }
    guard case .success = try await init1.initialize(Self.valid) else { Issue.record("success"); return }
    #expect(builder.builds == 1)
  }

  @Test func aStorageFailureIsAResultAndIsNotRepeatedAutomatically() async throws {
    let builder = try Builder(failures: [StorageError(.sqlite, 14)])
    let init1 = coordinator(builder)
    let first = try await init1.initialize(Self.valid)
    #expect(first.error == .storageFailure(message: "StorageError(sqlite, 14)"))
    #expect(builder.builds == 1)  // no automatic retry
    guard case .success = try await init1.initialize(Self.valid) else { Issue.record("retry works"); return }
    #expect(builder.builds == 2)
  }

  @Test func anyErrorFromTheBuildIsAStorageFailureWithATypeName() async throws {
    let init1 = InitCoordinator { _ in throw OtherError() }
    #expect(try await init1.initialize(Self.valid).error == .storageFailure(message: "OtherError"))
  }

  @Test func anInvalidConfigurationCreatesNoStateAndDoesNotStartAnAttempt() async throws {
    let builder = try Builder()
    let init1 = coordinator(builder)
    let bad = SignalDockConfig(endpoint: "http://signals.example.com", collectionKey: "key")
    guard case .failure(.invalidConfiguration) = try await init1.initialize(bad) else {
      Issue.record("invalid")
      return
    }
    #expect(builder.builds == 0)
    guard case .success = try await init1.initialize(Self.valid) else { Issue.record("a valid call works"); return }
  }

  // MARK: The real factory

  @Test func theFactoryOpensStorageAndKeepsTheInstallationIdAcrossInstances() async throws {
    let dir = try TempDirectory()
    let ids = SequenceIds()
    let first = try await SDKFactory.create(config: Self.valid, base: dir.url, ids: ids, logger: RecordingLogger())
    _ = try await first.track("e")
    await first.queue.close()
    let second = try await SDKFactory.create(config: Self.valid, base: dir.url, ids: ids, logger: RecordingLogger())
    #expect(first.collector.installationId == second.collector.installationId)
    #expect(isUuidV4(second.collector.installationId))
    #expect(try await second.diagnostics().queuedEvents == 1)  // the queue is kept
  }

  @Test func theFactoryLogsInitializedAtDebugOnly() async throws {
    let logger = RecordingLogger()
    _ = try await SDKFactory.create(config: Self.valid, base: try TempDirectory().url, logger: logger)
    #expect(logger.messages == [.initialized])
    #expect(LogMessage.initialized.level == .debug)  // a WARN default config does not write it
  }

  @Test func aBaseThatIsAFileFailsWithoutAnInstance() async throws {
    let dir = try TempDirectory()
    let file = dir.file("not-a-directory")
    try Data("x".utf8).write(to: file)
    let init1 = InitCoordinator { try await SDKFactory.create(config: $0, base: file, logger: RecordingLogger()) }
    guard case .failure(.storageFailure(let message)) = try await init1.initialize(Self.valid) else {
      Issue.record("storage failure")
      return
    }
    #expect(message.hasPrefix("StorageError(file, "))
  }

  @Test func aDatabaseThatIsNotASQLiteFileFailsAndStaysUntouched() async throws {
    let dir = try TempDirectory()
    let directory = dir.url.appendingPathComponent("signaldock", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let garbage = Data("this is not a database; it is long enough to be read as a header, but not a header".utf8)
    try garbage.write(to: directory.appendingPathComponent("queue.db"))
    let base = dir.url
    let init1 = InitCoordinator { try await SDKFactory.create(config: $0, base: base, logger: RecordingLogger()) }
    #expect(try await init1.initialize(Self.valid).error == .storageFailure(message: "StorageError(sqlite, 26)"))
    #expect(try Data(contentsOf: directory.appendingPathComponent("queue.db")) == garbage)
    // The installation ID was made before the queue failed, and it stays (ADR-0003 item 6).
    #expect(FileManager.default.fileExists(atPath: directory.appendingPathComponent("installation-id").path))
  }

  @Test func anUnreadableDatabaseBeforeFirstUnlockLooksLikeAnOpenFailureAndGivesNoInstance() async throws {
    // The open failure before the first unlock cannot be made on the Simulator (ticket 07 results). A file the
    // process may not open is the same kind of failure: SQLITE_CANTOPEN, no instance, then a retry works.
    let dir = try TempDirectory()
    let first = try await SDKFactory.create(config: Self.valid, base: dir.url, logger: RecordingLogger())
    await first.queue.close()
    let database = dir.url.appendingPathComponent("signaldock/queue.db").path
    #expect(chmod(database, 0o000) == 0)
    let base = dir.url
    let init1 = InitCoordinator { try await SDKFactory.create(config: $0, base: base, logger: RecordingLogger()) }
    #expect(try await init1.initialize(Self.valid).error == .storageFailure(message: "StorageError(sqlite, 14)"))
    chmod(database, 0o600)
    guard case .success = try await init1.initialize(Self.valid) else { Issue.record("works after the unlock"); return }
  }

  // MARK: Configuration (ADR-0035)

  @Test func httpsIsRequiredUnlessTheHostAllowsHttp() {
    let http = SignalDockConfig(endpoint: "http://localhost:8080", collectionKey: "k")
    #expect(
      validateConfig(http)
        == "endpoint uses http; use https, or set allowInsecureEndpoint = true for development")
    var allowed = http
    allowed.allowInsecureEndpoint = true
    #expect(validateConfig(allowed) == nil)
    #expect(validateConfig(SignalDockConfig(endpoint: "HTTPS://Example.com", collectionKey: "k")) == nil)
    #expect(validateConfig(SignalDockConfig(endpoint: "HTTP://localhost", collectionKey: "k")) != nil)
  }

  @Test func otherSchemesAndMissingHostsAreRejectedEvenWithTheInsecureFlag() {
    for endpoint in ["ftp://example.com", "example.com", "https://", "https:///path", "file:///tmp/x", "/relative", ""]
    {
      let config = SignalDockConfig(endpoint: endpoint, collectionKey: "k", allowInsecureEndpoint: true)
      #expect(validateConfig(config) != nil, Comment(rawValue: endpoint))
    }
    #expect(
      validateConfig(SignalDockConfig(endpoint: "ftp://example.com", collectionKey: "k", allowInsecureEndpoint: true))
        == "endpoint must be an absolute https URL with a host")
  }

  @Test func aMalformedEndpointIsNotAValidURI() {
    #expect(
      validateConfig(SignalDockConfig(endpoint: "https://exa mple.com", collectionKey: "k"))
        == "endpoint is not a valid URI")
  }

  @Test func aBlankCollectionKeyIsRejectedFirstAndTheMessageNeverHoldsTheKey() {
    // U+001C..U+001F are whitespace for Kotlin `isBlank()` (Java), not for Unicode White_Space.
    for key in ["", " ", " \t\n", "\u{1C}", "\u{1D}\u{1E}\u{1F}", "\u{A0}", "\u{3000}"] {
      #expect(
        validateConfig(SignalDockConfig(endpoint: "https://e.com", collectionKey: key)) == "collectionKey is blank")
    }
    // not all blank
    #expect(validateConfig(SignalDockConfig(endpoint: "https://e.com", collectionKey: "\u{1C}k")) == nil)
    let secret = "super-secret-collection-key"
    let messages = [
      validateConfig(SignalDockConfig(endpoint: "http://e.com", collectionKey: secret)),
      validateConfig(SignalDockConfig(endpoint: "nonsense", collectionKey: secret)),
      validateConfig(SignalDockConfig(endpoint: "https://e.com", collectionKey: secret, callTimeout: 0)),
    ]
    #expect(messages.allSatisfy { $0 != nil && !$0!.contains(secret) })
  }

  @Test func timeoutsMustBePositiveFiniteAndFitInThirtyOneBitsOfMilliseconds() {
    func check(_ seconds: TimeInterval) -> String? {
      validateConfig(SignalDockConfig(endpoint: "https://e.com", collectionKey: "k", writeTimeout: seconds))
    }
    #expect(check(0.001) == nil)  // 1 ms
    #expect(check(2_147_483.647) == nil)  // Int32.max ms
    for bad in [0, -1, 0.0009, .nan, .infinity, -.infinity, 2_147_483.648, 1e12] {
      #expect(
        check(bad) == "writeTimeout must be positive, finite and at most 2147483647 ms", Comment(rawValue: "\(bad)"))
    }
    for name in ["callTimeout", "connectTimeout", "readTimeout"] {
      var config = SignalDockConfig(endpoint: "https://e.com", collectionKey: "k")
      switch name {
      case "callTimeout": config.callTimeout = 0
      case "connectTimeout": config.connectTimeout = 0
      default: config.readTimeout = 0
      }
      #expect(validateConfig(config)?.hasPrefix(name) == true)
    }
  }

  @Test func theDefaultsAreTheAndroidDefaults() {
    let config = SignalDockConfig(endpoint: "https://e.com", collectionKey: "k")
    #expect(config.logLevel == .warn)
    #expect(config.callTimeout == 30)
    #expect(config.connectTimeout == 10)
    #expect(config.readTimeout == 10)
    #expect(config.writeTimeout == 10)
    #expect(config.allowInsecureEndpoint == false)
  }

  @Test func theCollectionKeyIsLeftOutOfEveryTextForm() {
    let config = SignalDockConfig(endpoint: "https://e.com", collectionKey: "super-secret-collection-key")
    var dumped = ""
    dump(config, to: &dumped)
    for text in ["\(config)", config.description, config.debugDescription, String(reflecting: config), dumped] {
      #expect(!text.contains("super-secret-collection-key"))
      #expect(text.contains("***"))
    }
    #expect(config.description == "SignalDockConfig(endpoint=https://e.com, collectionKey=***, logLevel=warn)")
  }
}
