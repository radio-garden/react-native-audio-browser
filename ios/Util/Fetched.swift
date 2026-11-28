import Foundation

/// A fetched value and how long it may be kept: its response's positive `max-age` in seconds, if
/// any, of which `age` had already passed when it arrived.
struct Fetched<Value> {
  let value: Value
  let lifetime: TimeInterval?
  var age: TimeInterval = 0

  /// Kept for `freshness`'s `max-age`; any other freshness keeps the value with no lifetime.
  init(value: Value, freshness: HttpFreshness) {
    if case let .maxAge(maxAge, age) = freshness {
      self.init(value: value, lifetime: maxAge, age: age)
    } else {
      self.init(value: value, lifetime: nil)
    }
  }

  init(value: Value, lifetime: TimeInterval?, age: TimeInterval = 0) {
    self.value = value
    self.lifetime = lifetime
    self.age = age
  }

  func map<T>(_ transform: (Value) throws -> T) rethrows -> Fetched<T> {
    try Fetched<T>(value: transform(value), lifetime: lifetime, age: age)
  }
}
