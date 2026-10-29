// Consumer and demo app (ADR-0047 D12). It uses the SDK only from the unzipped release ZIP (a local package at
// `.sdk/SignalDock`, put there by scripts/verify-consumer-ios.sh) and the generated tracking plan.
import SignalDock
import SwiftUI

@main
struct ConsumerApp: App {
  @StateObject private var model = AppModel()

  var body: some Scene {
    WindowGroup {
      RootView().environmentObject(model)
    }
  }
}

/// Endpoint and collection key. Order: launch environment (`SIMCTL_CHILD_SIGNALDOCK_ENDPOINT=... xcrun simctl launch`),
/// then Info.plist (build settings `SIGNALDOCK_ENDPOINT` and `SIGNALDOCK_COLLECTION_KEY`; the idea of Android
/// `-Psignaldock.endpoint`). No key is committed. The Simulator shares the Mac network, so 127.0.0.1 reaches Compose.
enum Settings {
  static let defaultEndpoint = "http://127.0.0.1:8080"

  static func value(_ envName: String, _ plistKey: String) -> String {
    if let env = ProcessInfo.processInfo.environment[envName], !env.isEmpty { return env }
    if let plist = Bundle.main.object(forInfoDictionaryKey: plistKey) as? String, !plist.isEmpty { return plist }
    return ""
  }

  static var endpoint: String {
    let configured = value("SIGNALDOCK_ENDPOINT", "SignalDockEndpoint")
    return configured.isEmpty ? defaultEndpoint : configured
  }
  static var collectionKey: String { value("SIGNALDOCK_COLLECTION_KEY", "SignalDockCollectionKey") }
}

@MainActor
final class AppModel: ObservableObject {
  enum State {
    case starting
    case ready(SignalDockSDK)
    case failed(String)
  }

  @Published var state = State.starting
  @Published var cart: [Product: Int] = [:]
  @Published var message = "Ready."
  @Published var purchasing = false

  var itemCount: Int64 { Int64(cart.values.reduce(0, +)) }
  var totalMinor: Int64 { cart.reduce(0) { $0 + $1.key.priceMinor * Int64($1.value) } }

  init() {
    Task { await start() }
  }

  private func start() async {
    let config = SignalDockConfig(
      endpoint: Settings.endpoint,
      collectionKey: Settings.collectionKey,
      logLevel: .info,
      // The default endpoint is plain HTTP on the Mac (ADR-0035). HTTPS endpoints stay strict.
      allowInsecureEndpoint: Settings.endpoint.lowercased().hasPrefix("http://"))
    do {
      switch try await SignalDockSDK.initialize(config) {
      case .success(let sdk): state = .ready(sdk)
      case .failure(let error): state = .failed("\(error)")
      }
    } catch {
      state = .failed("cancelled")
    }
    #if DEBUG
      // A durability phase owns the run: the demo flow would add events to its counts.
      if ProcessInfo.processInfo.environment["SIGNALDOCK_PHASE"] == nil { await AutoRun.runIfRequested(self) }
      await PhaseRun.runIfRequested(self)
    #endif
  }

  var sdk: SignalDockSDK? {
    if case .ready(let sdk) = state { return sdk }
    return nil
  }

  func report(_ name: String, _ result: TrackResult?) {
    switch result {
    case .success(let id)?: message = "\(name): stored, eventId=\(id)"
    case let other?: message = "\(name): \(other)"
    // `try?` also turns CancellationError into nil.
    case nil: message = "\(name): SDK not ready, or the call was cancelled"
    }
  }

  func view(_ product: Product) async {
    let result = try? await sdk?.productViewed(productId: product.id, priceMinor: product.priceMinor)
    report("product_viewed", result)
  }

  func add(_ product: Product) async {
    cart[product, default: 0] += 1
    let result = try? await sdk?.addToCart(productId: product.id, quantity: 1, priceMinor: product.priceMinor)
    report("add_to_cart", result)
  }

  func startCheckout() async {
    let result = try? await sdk?.checkoutStarted(itemCount: itemCount, totalMinor: totalMinor)
    report("checkout_started", result)
  }

  /// Simulated purchase. No payment happens. The tracking plan has `purchase_completed` only, so a failed
  /// purchase is shown in the UI and sends no event (ADR-0047 "Ticket 14 results").
  func purchase(succeed: Bool) async {
    guard itemCount > 0 else {
      message = "The cart is empty."
      return
    }
    guard !purchasing else { return }
    guard succeed else {
      message = "Simulated payment declined. The cart is kept; no event is sent."
      return
    }
    // One purchase at a time, for the cart as it is now; items added during the call stay in the cart.
    purchasing = true
    defer { purchasing = false }
    let bought = cart
    let result = try? await sdk?.purchaseCompleted(
      orderId: "order-\(UUID().uuidString.prefix(8))", itemCount: itemCount, totalMinor: totalMinor, isSimulated: true)
    if case .success? = result {
      for (product, count) in bought {
        let left = (cart[product] ?? 0) - count
        cart[product] = left > 0 ? left : nil
      }
    }
    report("purchase_completed", result)
  }
}
