#if DEBUG
  import Foundation

  /// Debug builds only (ADR-0047 "Ticket 14 results"): `SIMCTL_CHILD_SIGNALDOCK_AUTORUN=1 xcrun simctl launch ...`
  /// runs the shop flow once without UI driving, so a script can check delivery against Compose. Not in Release.
  enum AutoRun {
    @MainActor
    static func runIfRequested(_ model: AppModel) async {
      guard ProcessInfo.processInfo.environment["SIGNALDOCK_AUTORUN"] == "1" else { return }
      let product = Product.catalog[0]
      await model.view(product)
      await model.add(product)
      await model.startCheckout()
      await model.purchase(succeed: false)
      await model.purchase(succeed: true)
    }
  }
#endif
