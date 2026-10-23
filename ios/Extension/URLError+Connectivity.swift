import Foundation

extension URLError {
  /// The request failed for want of a connection to the host, so it is worth repeating once one
  /// is back.
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
