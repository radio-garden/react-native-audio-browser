import Foundation

/// A fetched value and the positive lifetime in seconds its response states, if any.
struct Fetched<Value> {
  let value: Value
  let lifetime: TimeInterval?

  func map<T>(_ transform: (Value) throws -> T) rethrows -> Fetched<T> {
    try Fetched<T>(value: transform(value), lifetime: lifetime)
  }
}
