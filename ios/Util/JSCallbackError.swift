import Foundation

/// A JS callback's failure, its message as `localizedDescription`. Nitro's `RuntimeError`, which
/// hands Swift the failure, is a `LocalizedError` only from 0.37.2: before, its message is only its
/// `description`.
struct JSCallbackError: LocalizedError {
  let message: String
  var errorDescription: String? { message }

  init(_ rejection: Error) {
    message = String(describing: rejection)
  }

  /// For the rejection of a promise a JS callback returned, which reaches Swift as `Name: message`.
  init(jsPromiseRejection rejection: Error) {
    message = withoutErrorName(String(describing: rejection))
  }
}

private func withoutErrorName(_ message: String) -> String {
  guard let name = message.prefixMatch(of: #/(?:[A-Za-z_$][A-Za-z0-9_$]*)?Error: /#) else { return message }
  return String(message[name.range.upperBound...])
}
