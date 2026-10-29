// swift-tools-version: 6.0
// "Without the SDK" arm of scripts/measure-ios-sdk.sh (ADR-0047 amendment, ticket 16). Not a product.
import PackageDescription

let package = Package(
  name: "SignalDock",
  platforms: [.iOS(.v15)],
  products: [.library(name: "SignalDock", targets: ["SignalDock"])],
  targets: [.target(name: "SignalDock")],
  swiftLanguageModes: [.v6]
)
