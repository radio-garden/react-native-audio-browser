import Testing

@testable import AudioBrowserTestable

@Suite("JSCallbackError")
struct JSCallbackErrorTests {
  @Test func givesARejectionsMessageAsItsLocalizedDescription() {
    let error = JSCallbackError(NitroRuntimeError(description: "token expired"))
    #expect(error.localizedDescription == "token expired")
  }

  @Test func keepsASyncThrowsMessageWhole() {
    let error = JSCallbackError(NitroRuntimeError(description: "Error: token expired"))
    #expect(error.localizedDescription == "Error: token expired")
  }

  @Test func leavesOutAJSPromiseRejectionsErrorName() {
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError(description: "Error: token expired")).message == "token expired")
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError(description: "TypeError: bad header")).message == "bad header")
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError(description: "Error: upstream: 503")).message == "upstream: 503")
  }

  @Test func keepsAJSPromiseRejectionsNameThatIsNotAnErrorName() {
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError(description: "Timeout: took 30s")).message == "Timeout: took 30s")
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError(description: "ErrorCode: 7")).message == "ErrorCode: 7")
    #expect(JSCallbackError(jsPromiseRejection: NitroRuntimeError(description: "token expired")).message == "token expired")
  }
}
