import Foundation
import Testing

@testable import SignalDock

/// What the stub answers.
enum StubAction: Sendable {
  case respond(status: Int, headers: [String: String] = [:], body: Data = Data())
  case fail(URLError.Code)
  /// A redirect response to `location`; the delegate decides whether it is followed.
  case redirect(status: Int, location: String)
  /// The request is in flight; the answer never comes, until the task is cancelled.
  case hang(entered: Gate)
}

/// A request as the stub saw it.
struct StubRequest: Sendable {
  let url: URL
  let method: String
  let headers: [String: String]
  let body: Data
}

/// Routes by host, so every test has its own host and tests can run in parallel. `@unchecked Sendable`: `handlers` and
/// `seen` are read and written only under `lock`.
final class StubRegistry: @unchecked Sendable {
  static let shared = StubRegistry()
  private let lock = NSLock()
  private var handlers: [String: @Sendable (StubRequest) -> StubAction] = [:]
  private var seen: [String: [StubRequest]] = [:]

  func register(host: String, _ handler: @escaping @Sendable (StubRequest) -> StubAction) {
    lock.lock()
    defer { lock.unlock() }
    handlers[host] = handler
  }

  func action(for request: StubRequest) -> StubAction {
    lock.lock()
    defer { lock.unlock() }
    let host = request.url.host ?? ""
    seen[host, default: []].append(request)
    return handlers[host]?(request) ?? .fail(.cannotFindHost)
  }

  func requests(host: String) -> [StubRequest] {
    lock.lock()
    defer { lock.unlock() }
    return seen[host] ?? []
  }
}

/// `URLProtocol` that answers from `StubRegistry`, so the transport tests use no network.
final class StubURLProtocol: URLProtocol {
  override class func canInit(with request: URLRequest) -> Bool { true }

  override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

  override func startLoading() {
    guard let url = request.url else { return }
    var headers: [String: String] = [:]
    for (key, value) in request.allHTTPHeaderFields ?? [:] { headers[key] = value }
    let stub = StubRequest(
      url: url, method: request.httpMethod ?? "", headers: headers, body: Self.body(of: request))
    switch StubRegistry.shared.action(for: stub) {
    case .respond(let status, let headers, let body):
      respond(url, status, headers, body)
    case .fail(let code):
      client?.urlProtocol(self, didFailWithError: URLError(code))
    case .redirect(let status, let location):
      let response = HTTPURLResponse(
        url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Location": location])!
      var next = URLRequest(url: URL(string: location)!)
      next.httpMethod = request.httpMethod
      client?.urlProtocol(self, wasRedirectedTo: next, redirectResponse: response)
      // A custom protocol must also hand over the redirect response: when the delegate declines the redirect, that
      // response is the result. When the redirect is followed, the system stops this load and starts the next one.
      client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
      client?.urlProtocolDidFinishLoading(self)
    case .hang(let entered):
      Task { await entered.open() }
    }
  }

  override func stopLoading() {}

  private func respond(_ url: URL, _ status: Int, _ headers: [String: String], _ body: Data) {
    let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: body)
    client?.urlProtocolDidFinishLoading(self)
  }

  private static func body(of request: URLRequest) -> Data {
    if let body = request.httpBody { return body }
    guard let stream = request.httpBodyStream else { return Data() }
    stream.open()
    defer { stream.close() }
    var data = Data()
    var buffer = [UInt8](repeating: 0, count: 16 * 1024)
    while stream.hasBytesAvailable {
      let count = stream.read(&buffer, maxLength: buffer.count)
      if count <= 0 { break }
      data.append(buffer, count: count)
    }
    return data
  }
}

/// `URLSessionBatchTransport` against the stub (ADR-0006 decision 12, Android `TransportTest`).
@Suite struct TransportTests {
  /// A transport for a new host that the test controls.
  private func make(
    endpoint: String = "https://@HOST@", callTimeout: TimeInterval = 30, connectTimeout: TimeInterval = 10,
    readTimeout: TimeInterval = 10, writeTimeout: TimeInterval = 10, ackLimit: Int = maxAckBytes,
    _ handler: @escaping @Sendable (StubRequest) -> StubAction
  ) -> (URLSessionBatchTransport, String) {
    let host = "t-\(UUID().uuidString.lowercased()).test"
    StubRegistry.shared.register(host: host, handler)
    let config = SignalDockConfig(
      endpoint: endpoint.replacingOccurrences(of: "@HOST@", with: host), collectionKey: "key-123",
      callTimeout: callTimeout, connectTimeout: connectTimeout, readTimeout: readTimeout, writeTimeout: writeTimeout)
    return (URLSessionBatchTransport(config: config, protocolClasses: [StubURLProtocol.self], ackLimit: ackLimit), host)
  }

  private let request = BatchHttpRequest(contractVersion: "1", body: Data(#"{"events":[],"installationId":"i"}"#.utf8))

  @Test func theRequestCarriesTheBearerKeyTheContractHeaderTheJsonTypeAndTheExactBody() async throws {
    let (transport, host) = make { _ in .respond(status: 200) }
    _ = try await transport.send(request)
    let seen = try #require(StubRegistry.shared.requests(host: host).first)
    #expect(seen.method == "POST")
    #expect(seen.url.path == "/v1/events:batch")
    #expect(seen.headers["Authorization"] == "Bearer key-123")
    #expect(seen.headers["SignalDock-Contract-Version"] == "1")
    #expect(seen.headers["Content-Type"] == "application/json")  // exactly: no charset
    #expect(seen.body == request.body)
  }

  @Test func aBasePathInTheEndpointIsKeptAndATrailingSlashIsNot() async throws {
    let (transport, host) = make(endpoint: "https://@HOST@/api/ingest//") { _ in .respond(status: 200) }
    _ = try await transport.send(request)
    #expect(StubRegistry.shared.requests(host: host).first?.url.path == "/api/ingest/v1/events:batch")
  }

  @Test func statusHeadersAndTheBodyOfA200ComeBack() async throws {
    let ack = Data(ackJson(ids: ["a"]).utf8)
    let (transport, _) = make(callTimeout: 5, readTimeout: 5) { _ in
      .respond(
        status: 200,
        headers: ["Retry-After": "5", "Deprecation": "@1788220800", "Sunset": "Wed, 11 Nov 2026 23:59:59 GMT"],
        body: ack)
    }
    let result = try await transport.send(request)
    #expect(
      result
        == .http(
          HttpResult(
            status: 200, body: ack, retryAfter: "5", deprecation: "@1788220800", sunset: "Wed, 11 Nov 2026 23:59:59 GMT"
          )))
  }

  @Test func theHeadersAreReadForAnyStatusAndTheBodyOnlyFor200() async throws {
    for status in [201, 400, 401, 413, 429, 503] {
      let (transport, _) = make { _ in
        .respond(
          status: status, headers: ["retry-after": "9", "DEPRECATION": "@1", "sunset": "x"], body: Data("body".utf8))
      }
      let result = try await transport.send(request)
      #expect(result == .http(HttpResult(status: status, body: nil, retryAfter: "9", deprecation: "@1", sunset: "x")))
    }
  }

  @Test func aRedirectIsNotFollowedAndTheRedirectResponseIsTheResult() async throws {
    let (transport, host) = make { _ in .redirect(status: 307, location: "https://other.test/v1/events:batch") }
    let result = try await transport.send(request)
    guard case .http(let http) = result else { Issue.record("expected an HTTP result, got \(result)"); return }
    #expect(http.status == 307)
    #expect(StubRegistry.shared.requests(host: host).count == 1)
    #expect(StubRegistry.shared.requests(host: "other.test").isEmpty, "the redirect target saw nothing")
  }

  @Test func aSameOriginRedirectIsNotFollowedEither() async throws {
    let box = HostBox()
    let (transport, host) = make { r in
      r.url.path == "/v1/events:batch"
        ? .redirect(status: 302, location: "https://\(box.host)/elsewhere") : .respond(status: 200)
    }
    box.host = host
    let result = try await transport.send(request)
    guard case .http(let http) = result else { Issue.record("expected an HTTP result"); return }
    #expect(http.status == 302)
    #expect(StubRegistry.shared.requests(host: host).count == 1)
  }

  @Test func aTimeoutIsATimeoutAndOtherErrorsAreNetworkFailures() async throws {
    let codes: [(URLError.Code, DeliveryErrorKind)] = [
      (.timedOut, .timeout), (.notConnectedToInternet, .network), (.cannotConnectToHost, .network),
      (.networkConnectionLost, .network), (.secureConnectionFailed, .network), (.cannotFindHost, .network),
      (.dnsLookupFailed, .network), (.badServerResponse, .network),
    ]
    for (code, kind) in codes {
      let (transport, _) = make { _ in .fail(code) }
      #expect(try await transport.send(request) == .failed(kind), "\(code)")
    }
  }

  /// The limit is a small test value: a 1 MiB body takes about a minute to read on a loaded CI runner under ASan.
  @Test func anAckBodyOverTheLimitIsNotReadAndOneOfExactlyTheLimitIs() async throws {
    #expect(maxAckBytes == 1 << 20, "the contract limit is 1 MiB")
    let limit = 4096
    let (big, _) = make(ackLimit: limit) { _ in .respond(status: 200, body: Data(count: limit + 1)) }
    #expect(try await big.send(request) == .http(HttpResult(status: 200, body: nil)))
    let (exact, _) = make(ackLimit: limit) { _ in .respond(status: 200, body: Data(count: limit)) }
    guard case .http(let http) = try await exact.send(request) else { Issue.record("expected HTTP"); return }
    #expect(http.body?.count == limit)
  }

  @Test func cancellingTheTaskDuringTheRequestThrowsCancellationError() async throws {
    let entered = Gate()
    let (transport, _) = make(callTimeout: 5, readTimeout: 5) { _ in .hang(entered: entered) }
    let body = request
    let task = Task { try await transport.send(body) }
    await entered.wait()  // the request is in flight
    task.cancel()
    await #expect(throws: CancellationError.self) { try await task.value }
  }

  @Test func aRequestInACancelledTaskThrowsAndSendsNothing() async throws {
    let (transport, host) = make { _ in .respond(status: 200) }
    let body = request
    let outcome = await runCancelled { try await transport.send(body) }
    #expect(outcome.isFailure)
    #expect(StubRegistry.shared.requests(host: host).isEmpty)
  }

  // MARK: Session settings (Q119 to Q123)

  @Test func theDefaultsAreA30SecondCallTimeoutAndTenSecondProgressTimeouts() {
    let (transport, _) = make { _ in .respond(status: 200) }
    let c = transport.session.configuration
    #expect(c.timeoutIntervalForResource == 30)
    #expect(c.timeoutIntervalForRequest == 10)
  }

  @Test func hostTimeoutsReachTheSession() {
    let (transport, _) = make(callTimeout: 45, connectTimeout: 3, readTimeout: 20, writeTimeout: 7) { _ in
      .respond(status: 200)
    }
    let c = transport.session.configuration
    #expect(c.timeoutIntervalForResource == 45, "the call timeout ends the whole call")
    #expect(c.timeoutIntervalForRequest == 20, "the progress timeout is the largest of connect, read, and write")
  }

  @Test func theSessionHasNoCacheNoCookiesNoCredentialsAndDoesNotWaitForConnectivity() {
    let (transport, _) = make { _ in .respond(status: 200) }
    let c = transport.session.configuration
    #expect(c.urlCache == nil && c.httpCookieStorage == nil && c.urlCredentialStorage == nil)
    #expect(!c.httpShouldSetCookies && !c.waitsForConnectivity)
    #expect(c.requestCachePolicy == .reloadIgnoringLocalAndRemoteCacheData)
  }

  // MARK: Sender and transport together

  @Test func endToEndAValidAckEmptiesTheQueueAndA401HoldsIt() async throws {
    let tracked = Flag()
    let (transport, _) = make { r in
      let ids =
        (try? JSONSerialization.jsonObject(with: r.body) as? [String: Any])
        .flatMap { $0["events"] as? [[String: Any]] }?.compactMap { $0["eventId"] as? String } ?? []
      return tracked.unauthorized ? .respond(status: 401) : .respond(status: 200, body: Data(ackJson(ids: ids).utf8))
    }
    let dir = try TempDirectory()
    let queue = try await QueueStore.open(directory: dir.url)
    for n in 1...3 { _ = try await queue.addWithinLimits(sendEvent(n), limits: QueueLimits()) }
    let clock = TestClock()
    let sender = Sender(
      queue: queue, transport: transport, clock: clock, jitter: ScriptedJitter(), logger: RecordingLogger(),
      notice: DeprecationNotice())
    tracked.unauthorized = true
    #expect(try await sender.run().end == .waiting(untilMillis: clock.nowMillis() + 30 * 60_000))
    #expect(try await queue.usage().count == 3)
    #expect(try await queue.retryState()?.hold == true)
    tracked.unauthorized = false
    clock.advance(by: 30 * 60_000)
    #expect(try await sender.run().end == .queueEmpty)
    #expect(try await queue.usage().count == 0)
  }
}

/// A host name that the stub handler reads after the transport exists. `@unchecked Sendable`: written once, before the
/// first request, under `lock`.
final class HostBox: @unchecked Sendable {
  private let lock = NSLock()
  private var value = ""

  var host: String {
    get {
      lock.lock()
      defer { lock.unlock() }
      return value
    }
    set {
      lock.lock()
      defer { lock.unlock() }
      value = newValue
    }
  }
}

/// A flag that a stub handler reads and the test changes. `@unchecked Sendable`: guarded by `lock`.
final class Flag: @unchecked Sendable {
  private let lock = NSLock()
  private var flag = false

  var unauthorized: Bool {
    get {
      lock.lock()
      defer { lock.unlock() }
      return flag
    }
    set {
      lock.lock()
      defer { lock.unlock() }
      flag = newValue
    }
  }
}
