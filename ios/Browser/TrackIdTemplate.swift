import Foundation

/// `{id}` substitution in request-config strings, so an artwork config like
/// `{ path: "/artwork/{id}" }` resolves per track. Mirrors Android's `substituteTrackId` and
/// `configContainsIdToken` in `BrowserUrlResolution.kt`; keep the two in sync.
enum TrackIdTemplate {
  static let token = "{id}"

  /// Replaces every token with `id`. A nil or empty `id` returns `value` with the token left
  /// in, on purpose: the request fails visibly and the caller warns via `containsToken`.
  static func substitute(_ value: String?, id: String?) -> String? {
    guard let id, !id.isEmpty else { return value }
    return value?.replacingOccurrences(of: token, with: id)
  }

  /// `substitute` over each value of a query or header map.
  static func substitute(_ values: [String: String]?, id: String?) -> [String: String]? {
    guard let id, !id.isEmpty else { return values }
    return values?.mapValues { $0.replacingOccurrences(of: token, with: id) }
  }

  static func containsToken(_ value: String?) -> Bool {
    value?.contains(token) == true
  }

  static func containsToken(_ values: [String: String]?) -> Bool {
    values?.values.contains { $0.contains(token) } == true
  }
}
