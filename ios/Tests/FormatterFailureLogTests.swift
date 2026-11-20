import Foundation
import Testing

@testable import AudioBrowserTestable

@Suite("FormatterFailureLog")
struct FormatterFailureLogTests {
  struct Failure: LocalizedError {
    var errorDescription: String? { "token expired" }
  }

  @Test func logsOnlyTheFirstFailureOfARun() {
    var log = FormatterFailureLog()
    #expect(log.failed(Failure(), generation: log.generation) != nil)
    #expect(log.failed(Failure(), generation: log.generation) == nil)
  }

  @Test func aSuccessEndsTheRun() {
    var log = FormatterFailureLog()
    _ = log.failed(Failure(), generation: log.generation)
    log.succeeded(generation: log.generation)
    #expect(log.failed(Failure(), generation: log.generation) != nil)
  }

  @Test func aNewFormatterStartsARun() {
    var log = FormatterFailureLog()
    _ = log.failed(Failure(), generation: log.generation)
    log.formatterChanged()
    #expect(log.failed(Failure(), generation: log.generation) != nil)
  }

  @Test func theOldFormattersSuccessLeavesTheNewOnesRunOpen() {
    var log = FormatterFailureLog()
    let old = log.generation
    log.formatterChanged()
    _ = log.failed(Failure(), generation: log.generation)
    log.succeeded(generation: old)
    #expect(log.failed(Failure(), generation: log.generation) == nil)
  }

  @Test func givesTheErrorsMessage() {
    var log = FormatterFailureLog()
    #expect(log.failed(Failure(), generation: log.generation) == "token expired")
  }
}
