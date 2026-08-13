import Foundation

/// `{id}` template-token substitution for request-config string values (path, query values,
/// header values), used by artwork URL resolution so a `nowPlayingArtwork` like
/// `{ path: "/artwork/{id}" }` resolves per track.
///
/// A track without a non-blank id leaves the token LITERALLY in place — the request then
/// visibly 404s, which is intended (garbage in, garbage out with a self-describing error
/// trail; the caller logs a single warning when `containsToken` still matches after
/// substitution). Mirrors Android's `substituteTrackId` / `configContainsIdToken` in
/// `BrowserUrlResolution.kt` — keep the two in sync.
enum TrackIdTemplate {
  static let token = "{id}"

  /// Replaces the token when `id` is non-blank; otherwise returns the value unchanged
  /// (token left literal).
  static func substitute(_ value: String?, id: String?) -> String? {
    guard let id, !id.isEmpty else { return value }
    return value?.replacingOccurrences(of: token, with: id)
  }

  /// Dictionary-value variant of `substitute` (query / header values).
  static func substitute(_ values: [String: String]?, id: String?) -> [String: String]? {
    guard let id, !id.isEmpty else { return values }
    return values?.mapValues { $0.replacingOccurrences(of: token, with: id) }
  }

  /// Whether the value still carries the token (an unfilled `{id}`).
  static func containsToken(_ value: String?) -> Bool {
    value?.contains(token) == true
  }

  /// Whether any dictionary value still carries the token.
  static func containsToken(_ values: [String: String]?) -> Bool {
    values?.values.contains { $0.contains(token) } == true
  }
}
