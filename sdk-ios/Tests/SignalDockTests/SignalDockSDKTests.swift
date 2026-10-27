import Testing

@testable import SignalDock

@Suite struct SignalDockSDKTests {
  @Test func versionIsSemver() {
    let parts = SignalDockSDK.version.split(separator: ".")
    #expect(parts.count == 3)
    #expect(parts.allSatisfy { Int($0) != nil })
  }
}
