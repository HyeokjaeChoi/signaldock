import Foundation
import Testing

@testable import SignalDock

/// The generated tracking plan functions (ADR-0021, ADR-0047 D10). `TrackingPlan.swift` in this folder is a symbolic
/// link to `tracking-plan/generated/swift/TrackingPlan.swift`, so this target compiles the generated file as it is.
/// Each test calls a generated function on a real SDK and reads the stored event, like `SdkSignalDockClientTest`
/// for Kotlin.
@Suite struct TrackingPlanTests {
  @Test func requiredAndOptionalProperties() async throws {
    let h = try await Harness.make()
    let full = try await h.sdk.productViewed(productId: "P-001", priceMinor: 1_999)
    let short = try await h.sdk.productViewed(productId: "P-002")
    for result in [full, short] {
      if case .success = result {} else { Issue.record("expected success, got \(result)") }
    }
    let events = try await h.storedEvents()
    #expect(events.count == 2)
    #expect(events[0]["name"] as? String == EventNames.productViewed)
    #expect(events[0]["properties"] as? NSDictionary == ["product_id": "P-001", "price_minor": 1_999])
    // An optional property that is nil is left out, not sent as null.
    #expect(events[1]["properties"] as? NSDictionary == ["product_id": "P-002"])
  }

  @Test func everyPlanEventIsStoredWithItsNameAndTypedProperties() async throws {
    let h = try await Harness.make()
    _ = try await h.sdk.addToCart(productId: "P-001", quantity: 2, priceMinor: 500)
    _ = try await h.sdk.checkoutStarted(itemCount: 2, totalMinor: 1_000)
    _ = try await h.sdk.purchaseCompleted(orderId: "O-1", itemCount: 2, totalMinor: 1_000, isSimulated: true)
    let events = try await h.storedEvents()
    #expect(
      events.map { $0["name"] as? String } == [
        EventNames.addToCart, EventNames.checkoutStarted, EventNames.purchaseCompleted,
      ])
    #expect(
      events[0]["properties"] as? NSDictionary == ["product_id": "P-001", "quantity": 2, "price_minor": 500])
    #expect(events[1]["properties"] as? NSDictionary == ["item_count": 2, "total_minor": 1_000])
    #expect(
      events[2]["properties"] as? NSDictionary
        == ["order_id": "O-1", "item_count": 2, "total_minor": 1_000, "is_simulated": true])
  }

  @Test func theSdkStillValidatesGeneratedCalls() async throws {
    let h = try await Harness.make()
    // 2^53 is out of the contract's integer range; the generated function passes it on and `track` reports it.
    let result = try await h.sdk.checkoutStarted(itemCount: 1, totalMinor: 9_007_199_254_740_992)
    #expect(result == .invalid(reason: .numberOutOfRange, field: "total_minor"))
  }
}
