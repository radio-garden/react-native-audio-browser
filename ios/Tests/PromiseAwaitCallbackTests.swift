import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("Promise awaiting a JS callback")
struct PromiseAwaitCallbackTests {
  struct Rejected: LocalizedError, Equatable {
    var errorDescription: String? { "Error: token expired" }
  }

  @Test func passesARejectionThrough() async {
    await #expect(throws: Rejected()) {
      try await Promise<Int>.rejected(withError: Rejected()).awaitCancellable()
    }
  }

  @Test func passesARejectionThroughWithATimeout() async {
    await #expect(throws: Rejected()) {
      try await Promise<Int>.rejected(withError: Rejected()).awaitOrTimeout()
    }
  }

  @Test func passesTheOuterRejectionOfANestedPromiseThrough() async {
    await #expect(throws: Rejected()) {
      try await Promise<Promise<Int>>.rejected(withError: Rejected()).awaitNested()
    }
  }

  @Test func leavesOutTheErrorNameOfAJSPromisesRejection() async throws {
    let error = await #expect(throws: JSPromiseRejection.self) {
      try await Promise<Int>.rejected(withError: Rejected()).awaitJSPromise()
    }
    #expect(error?.message == "token expired")
  }

  @Test func leavesOutTheErrorNameOfTheInnerRejectionOfANestedPromise() async throws {
    let inner = Promise<Int>.rejected(withError: Rejected())
    let error = await #expect(throws: JSPromiseRejection.self) {
      try await Promise.resolved(withResult: inner).awaitNested()
    }
    #expect(error?.message == "token expired")
  }

  @Test func givesAResolvedNestedPromisesValue() async throws {
    let inner = Promise<Int>.resolved(withResult: 7)
    #expect(try await Promise.resolved(withResult: inner).awaitNested() == 7)
  }
}
