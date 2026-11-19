import Network
import Testing

@testable import AudioBrowserTestable

@Suite("Connectivity")
struct ConnectivityTests {
  @Test func onlyAPathWithNoRouteIsOffline() {
    #expect(NWPath.Status.satisfied.isOnline)
    #expect(NWPath.Status.requiresConnection.isOnline)
    #expect(!NWPath.Status.unsatisfied.isOnline)
  }
}
