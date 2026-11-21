import Foundation

/// The rejection of a promise a JS callback returned, which reaches Swift as `Name: message`, with
/// the name left out: its message as `localizedDescription`.
struct JSCallbackError: LocalizedError {
  let message: String
  var errorDescription: String? { message }

  init(jsPromiseRejection rejection: Error) {
    message = withoutErrorName(rejection.localizedDescription)
  }
}

private func withoutErrorName(_ message: String) -> String {
  guard let name = message.prefixMatch(of: #/(?:[A-Za-z_$][A-Za-z0-9_$]*)?Error: /#) else { return message }
  return String(message[name.range.upperBound...])
}
