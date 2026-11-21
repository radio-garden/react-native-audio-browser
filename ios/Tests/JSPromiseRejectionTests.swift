import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("JSPromiseRejection")
struct JSPromiseRejectionTests {
  /// Nitro's `RuntimeError`: an `Error` whose message is its `localizedDescription`.
  struct NitroRuntimeError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
  }

  @Test func leavesOutAJSPromiseRejectionsErrorName() {
    #expect(JSPromiseRejection(NitroRuntimeError("Error: token expired")).message == "token expired")
    #expect(JSPromiseRejection(NitroRuntimeError("TypeError: bad header")).message == "bad header")
    #expect(JSPromiseRejection(NitroRuntimeError("Error: upstream: 503")).message == "upstream: 503")
  }

  @Test func keepsAJSPromiseRejectionsNameThatIsNotAnErrorName() {
    #expect(JSPromiseRejection(NitroRuntimeError("Timeout: took 30s")).message == "Timeout: took 30s")
    #expect(JSPromiseRejection(NitroRuntimeError("ErrorCode: 7")).message == "ErrorCode: 7")
    #expect(JSPromiseRejection(NitroRuntimeError("token expired")).message == "token expired")
  }

  @Test func givesTheMessageAsItsLocalizedDescription() {
    #expect(JSPromiseRejection(NitroRuntimeError("Error: token expired")).localizedDescription == "token expired")
  }

  /// Nitro hands a Swift error back to JS as `String(describing:)`.
  @Test func describesItselfAsItsMessage() {
    #expect(String(describing: JSPromiseRejection(NitroRuntimeError("Error: token expired"))) == "token expired")
  }
}
