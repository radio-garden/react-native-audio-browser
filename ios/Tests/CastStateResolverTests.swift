import Testing

@testable import AudioBrowserTestable

/// Truth table for `CastStateResolver`: connected > connecting > notConnected >
/// noDevices (mirrors the Android `CastStateResolverTest`).
@Suite("CastStateResolver")
struct CastStateResolverTests {
  @Test func connectedWinsOverEverything() {
    #expect(
      CastStateResolver.resolve(connected: true, connecting: true, hasDevices: true) == .connected)
    #expect(
      CastStateResolver.resolve(connected: true, connecting: false, hasDevices: false) == .connected)
  }

  @Test func connectingWinsOverDevicesWhenNotConnected() {
    #expect(
      CastStateResolver.resolve(connected: false, connecting: true, hasDevices: true) == .connecting)
    #expect(
      CastStateResolver.resolve(connected: false, connecting: true, hasDevices: false) == .connecting)
  }

  @Test func devicesAvailableButIdleIsNotConnected() {
    #expect(
      CastStateResolver.resolve(connected: false, connecting: false, hasDevices: true)
        == .notConnected)
  }

  @Test func nothingAvailableIsNoDevices() {
    #expect(
      CastStateResolver.resolve(connected: false, connecting: false, hasDevices: false)
        == .noDevices)
  }
}
