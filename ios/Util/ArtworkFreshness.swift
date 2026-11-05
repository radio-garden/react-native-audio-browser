import Foundation

enum ArtworkFreshness {
  /// Used when no response states a lifetime; matches Kingfisher's default disk expiry.
  static let defaultLifetime: TimeInterval = 7 * 24 * 60 * 60

  /// The shortest lifetime stated across `responses` (every redirect and the final response):
  /// `max-age` minus `Age`, or 0 for `no-cache` or `no-store`. Responses that state none are
  /// ignored; `defaultLifetime` when none states one.
  static func lifetime(of responses: [HTTPURLResponse]) -> TimeInterval {
    responses.compactMap(lifetime(of:)).min() ?? defaultLifetime
  }

  private static func lifetime(of response: HTTPURLResponse) -> TimeInterval? {
    guard let cacheControl = response.value(forHTTPHeaderField: "Cache-Control") else { return nil }
    var maxAge: TimeInterval?
    for directive in cacheControl.split(separator: ",") {
      let parts = directive.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
      let name = parts[0].trimmingCharacters(in: .whitespaces).lowercased()
      let value = parts.count == 2 ? parts[1].trimmingCharacters(in: .whitespaces).trimmingCharacters(in: ["\""]) : nil
      switch name {
      // `no-cache="field"` restricts reuse of that field only, not of the response.
      case "no-cache" where value == nil, "no-store":
        return 0
      case "max-age":
        maxAge = value.flatMap(deltaSeconds) ?? maxAge
      default:
        break
      }
    }
    guard let maxAge else { return nil }
    let age = response.value(forHTTPHeaderField: "Age").flatMap(deltaSeconds) ?? 0
    return max(0, maxAge - age)
  }

  /// RFC 9111 delta-seconds: digits only (so `inf` or `1e999` are rejected), capped at 2^31.
  private static func deltaSeconds(_ value: String) -> TimeInterval? {
    guard !value.isEmpty, value.allSatisfy({ ("0" ... "9").contains($0) }), let seconds = TimeInterval(value)
    else { return nil }
    return min(seconds, 2_147_483_648)
  }
}
