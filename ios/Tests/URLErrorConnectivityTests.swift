import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("URLError.isConnectivity")
struct URLErrorConnectivityTests {
  @Test(arguments: [
    URLError.Code.timedOut, .networkConnectionLost, .notConnectedToInternet, .cannotConnectToHost,
    .cannotFindHost, .dnsLookupFailed,
  ])
  func aMissingConnectionIsWorthRepeating(_ code: URLError.Code) {
    #expect(URLError(code).isConnectivity)
  }

  @Test(arguments: [URLError.Code.badServerResponse, .fileDoesNotExist, .badURL, .cancelled, .userAuthenticationRequired])
  func anyOtherFailureIsNot(_ code: URLError.Code) {
    #expect(!URLError(code).isConnectivity)
  }
}
