import Foundation

extension URLError {
  var isConnectivity: Bool {
    switch code {
    case .timedOut, .networkConnectionLost, .notConnectedToInternet, .cannotConnectToHost, .cannotFindHost,
         .dnsLookupFailed:
      true
    default:
      false
    }
  }
}
