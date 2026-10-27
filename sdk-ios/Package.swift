// swift-tools-version: 6.0
// No external dependency (ADR-0047 D7, D13). `scripts/check-repo-rules.sh` rejects any package dependency here.
import PackageDescription

let package = Package(
  name: "SignalDock",
  platforms: [.iOS(.v15)],
  products: [
    .library(name: "SignalDock", targets: ["SignalDock"])
  ],
  targets: [
    .target(name: "SignalDock"),
    .testTarget(name: "SignalDockTests", dependencies: ["SignalDock"]),
  ],
  swiftLanguageModes: [.v6]
)
