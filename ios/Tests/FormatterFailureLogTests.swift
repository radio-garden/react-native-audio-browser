import Testing

@testable import AudioBrowserTestable

@Suite("FormatterFailureLog")
struct FormatterFailureLogTests {
  let failure = NitroRuntimeError(description: "token expired")

  @Test func logsOnlyTheFirstFailureOfARun() {
    var log = FormatterFailureLog()
    #expect(log.failed(failure, generation: log.generation) != nil)
    #expect(log.failed(failure, generation: log.generation) == nil)
  }

  @Test func aSuccessEndsTheRun() {
    var log = FormatterFailureLog()
    _ = log.failed(failure, generation: log.generation)
    log.succeeded(generation: log.generation)
    #expect(log.failed(failure, generation: log.generation) != nil)
  }

  @Test func aNewFormatterStartsARun() {
    var log = FormatterFailureLog()
    _ = log.failed(failure, generation: log.generation)
    log.formatterChanged()
    #expect(log.failed(failure, generation: log.generation) != nil)
  }

  @Test func theOldFormattersSuccessLeavesTheNewOnesRunOpen() {
    var log = FormatterFailureLog()
    let old = log.generation
    log.formatterChanged()
    _ = log.failed(failure, generation: log.generation)
    log.succeeded(generation: old)
    #expect(log.failed(failure, generation: log.generation) == nil)
  }

  @Test func givesANitroErrorsMessage() {
    var log = FormatterFailureLog()
    #expect(log.failed(failure, generation: log.generation) == "token expired")
  }

  /// The formatter is a plain JS function: its message never carries a promise rejection's
  /// `Name: `, so a message that starts like one is the message.
  @Test func keepsTheMessageWhole() {
    var log = FormatterFailureLog()
    #expect(log.failed(NitroRuntimeError(description: "TypeError: bad header"), generation: log.generation) == "TypeError: bad header")
  }
}
