import Foundation

/// What a response's `Cache-Control` and `Age` say about reusing it.
enum HttpFreshness: Equatable {
  /// No valid `max-age`, and no `no-cache` or `no-store`.
  case unstated
  /// `no-cache`, `no-store`, or `max-age=0`: reuse only after asking again.
  case revalidate
  /// A positive `max-age` in seconds, and the `Age` the response already had.
  case maxAge(TimeInterval, age: TimeInterval)

  /// Used when no response states a lifetime; matches Kingfisher's default disk expiry.
  static let defaultLifetime: TimeInterval = 7 * 24 * 60 * 60

  /// The shortest time across `responses` (every redirect and the final response) that they may
  /// still be reused: `max-age` minus `Age`, or 0 to revalidate. Responses that state nothing are
  /// ignored; `defaultLifetime` when none states anything.
  static func lifetime(of responses: [HTTPURLResponse]) -> TimeInterval {
    responses.compactMap { HttpFreshness($0).remaining }.min() ?? defaultLifetime
  }

  init(_ response: HTTPURLResponse) {
    guard let cacheControl = response.value(forHTTPHeaderField: "Cache-Control") else {
      self = .unstated
      return
    }
    var maxAge: TimeInterval?
    for directive in cacheControl.split(separator: ",") {
      let parts = directive.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
      let name = parts[0].trimmingCharacters(in: .whitespaces).lowercased()
      let value = parts.count == 2 ? parts[1].trimmingCharacters(in: .whitespaces).trimmingCharacters(in: ["\""]) : nil
      switch name {
      // `no-cache="field"` restricts reuse of that field only, not of the response.
      case "no-cache" where value == nil, "no-store":
        self = .revalidate
        return
      case "max-age":
        // Of duplicates, the first valid one.
        maxAge = maxAge ?? value.flatMap(Self.deltaSeconds)
      default:
        break
      }
    }
    guard let maxAge else {
      self = .unstated
      return
    }
    guard maxAge > 0 else {
      self = .revalidate
      return
    }
    // Of repeated Age lines, joined with ", ", the first.
    let age = response.value(forHTTPHeaderField: "Age")
      .flatMap { $0.split(separator: ",").first.map { $0.trimmingCharacters(in: .whitespaces) } }
      .flatMap(Self.deltaSeconds) ?? 0
    self = .maxAge(maxAge, age: age)
  }

  /// Seconds the response may still be reused; nil when it states nothing.
  var remaining: TimeInterval? {
    switch self {
    case .unstated: nil
    case .revalidate: 0
    case let .maxAge(maxAge, age): max(0, maxAge - age)
    }
  }

  /// RFC 9111 delta-seconds: digits only (so `inf` or `1e999` are rejected), capped at 2^31.
  private static func deltaSeconds(_ value: String) -> TimeInterval? {
    guard !value.isEmpty, value.allSatisfy({ ("0" ... "9").contains($0) }), let seconds = TimeInterval(value)
    else { return nil }
    return min(seconds, 2_147_483_648)
  }
}
