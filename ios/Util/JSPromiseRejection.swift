import Foundation

/// A rejected JS promise's error, its `Name: ` prefix removed when `Name` is a JS error name: its
/// message as `localizedDescription`.
struct JSPromiseRejection: LocalizedError {
  let message: String
  var errorDescription: String? { message }

  init(_ rejection: Error) {
    message = withoutErrorName(rejection.localizedDescription)
  }
}

private func withoutErrorName(_ message: String) -> String {
  guard let name = message.prefixMatch(of: #/(?:[A-Za-z_$][A-Za-z0-9_$]*)?Error: /#) else { return message }
  return String(message[name.range.upperBound...])
}
