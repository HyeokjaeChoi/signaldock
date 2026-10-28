import Foundation

internal struct BatchHttpRequest: Sendable {
  internal let contractVersion: String
  internal let body: Data
}

/// An HTTP response. `body` is read only for status 200 (`nil` if it is too large); `retryAfter`, `deprecation`, and
/// `sunset` are the raw header values, for any status.
internal struct HttpResult: Sendable, Equatable {
  internal var status: Int
  internal var body: Data?
  internal var retryAfter: String?
  internal var deprecation: String?
  internal var sunset: String?

  internal init(
    status: Int, body: Data? = nil, retryAfter: String? = nil, deprecation: String? = nil, sunset: String? = nil
  ) {
    self.status = status
    self.body = body
    self.retryAfter = retryAfter
    self.deprecation = deprecation
    self.sunset = sunset
  }
}

/// The final result of one request. `failed` has no response: the kind is `.network` or `.timeout`.
internal enum TransportResult: Sendable, Equatable {
  case http(HttpResult)
  case failed(DeliveryErrorKind)
}

/// HTTP boundary of the sender (Q88). `URLSessionBatchTransport` below; fakes in tests. Caller cancellation cancels the
/// request and throws `CancellationError`; the queue is not touched.
internal protocol BatchTransport: Sendable {
  func send(_ request: BatchHttpRequest) async throws(CancellationError) -> TransportResult
}

/// The ACK body is read only for status 200 and only up to this size.
internal let maxAckBytes = 1 << 20

/// `URLSession` transport (ADR-0006 decision 12, ADR-0047 D8). `Authorization: Bearer <key>`, `Content-Type:
/// application/json`, no redirect followed (same-origin included), no cache, no cookies, no credential storage, and no
/// waiting for connectivity: the persisted retry time is the only retry clock.
internal final class URLSessionBatchTransport: BatchTransport {
  internal let session: URLSession
  private let url: URL?
  private let authorization: String
  private let ackLimit: Int

  /// - Parameters:
  ///   - protocolClasses: a test seam. `nil` keeps the system protocols.
  ///   - ackLimit: a test seam. A test uses a small limit, because reading a body byte by byte is very slow on a
  ///     loaded CI runner under a sanitizer (a 1 MiB body took 57 s there).
  internal init(config: SignalDockConfig, protocolClasses: [AnyClass]? = nil, ackLimit: Int = maxAckBytes) {
    self.ackLimit = ackLimit
    authorization = "Bearer \(config.collectionKey)"
    var base = config.endpoint
    while base.hasSuffix("/") { base.removeLast() }
    url = URL(string: base + "/v1/events:batch")
    let configuration = URLSessionConfiguration.ephemeral
    Self.apply(config, to: configuration)
    if let protocolClasses { configuration.protocolClasses = protocolClasses }
    session = URLSession(configuration: configuration, delegate: NoRedirectDelegate(), delegateQueue: nil)
  }

  deinit { session.finishTasksAndInvalidate() }  // the session holds its delegate until then

  /// `URLSession` has two timeouts, not four (ADR-0047 ticket 09 results): `timeoutIntervalForResource` ends the whole
  /// call, so it is `callTimeout`; `timeoutIntervalForRequest` ends a request that makes no progress while it
  /// connects, writes, or reads, so it is the largest of the connect, write, and read timeouts.
  internal static func apply(_ config: SignalDockConfig, to configuration: URLSessionConfiguration) {
    configuration.timeoutIntervalForResource = config.callTimeout
    configuration.timeoutIntervalForRequest = max(config.connectTimeout, config.readTimeout, config.writeTimeout)
    configuration.waitsForConnectivity = false
    configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
    configuration.urlCache = nil
    configuration.httpCookieStorage = nil
    configuration.httpShouldSetCookies = false
    configuration.urlCredentialStorage = nil
  }

  internal func send(_ request: BatchHttpRequest) async throws(CancellationError) -> TransportResult {
    if Task.isCancelled { throw CancellationError() }  // nothing leaves the device for a caller that is gone
    guard let url else { return .failed(.network) }
    var urlRequest = URLRequest(url: url)
    urlRequest.httpMethod = "POST"
    urlRequest.httpBody = request.body
    urlRequest.timeoutInterval = session.configuration.timeoutIntervalForRequest
    urlRequest.setValue(authorization, forHTTPHeaderField: "Authorization")
    urlRequest.setValue(request.contractVersion, forHTTPHeaderField: "SignalDock-Contract-Version")
    urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
    do {
      let (bytes, response) = try await session.bytes(for: urlRequest)
      guard let http = response as? HTTPURLResponse else { return .failed(.network) }
      let body = http.statusCode == 200 ? try await Self.readCapped(bytes, limit: ackLimit) : nil
      return .http(
        HttpResult(
          status: http.statusCode, body: body, retryAfter: http.value(forHTTPHeaderField: "Retry-After"),
          deprecation: http.value(forHTTPHeaderField: "Deprecation"), sunset: http.value(forHTTPHeaderField: "Sunset")))
    } catch {
      if Task.isCancelled { throw CancellationError() }
      // The call timeout and the progress timeout both end as `timedOut`.
      return .failed((error as? URLError)?.code == .timedOut ? .timeout : .network)
    }
  }

  private static func readCapped(_ bytes: URLSession.AsyncBytes, limit: Int) async throws -> Data? {
    var data = Data()
    for try await byte in bytes {
      data.append(byte)
      if data.count > limit { return nil }
    }
    return data
  }
}

/// Answers every redirect with "do not follow", so the 3xx response is the final result (Q123).
private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, Sendable {
  func urlSession(
    _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void
  ) {
    completionHandler(nil)
  }
}
