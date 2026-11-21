import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("JSCallbackError")
struct JSCallbackErrorTests {
  /// Nitro's `RuntimeError`: an `Error` whose message is its `localizedDescription`.
  struct NitroRuntimeError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
  }

  @Test func leavesOutAJSPromiseRejectionsErrorName() {
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError("Error: token expired")).message == "token expired")
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError("TypeError: bad header")).message == "bad header")
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError("Error: upstream: 503")).message == "upstream: 503")
  }

  @Test func keepsAJSPromiseRejectionsNameThatIsNotAnErrorName() {
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError("Timeout: took 30s")).message == "Timeout: took 30s")
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError("ErrorCode: 7")).message == "ErrorCode: 7")
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError("token expired")).message == "token expired")
  }

  @Test func givesTheMessageAsItsLocalizedDescription() {
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError("Error: token expired")).localizedDescription == "token expired")
  }
}
