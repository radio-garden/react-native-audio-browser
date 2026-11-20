/// Which of a now-playing formatter's failures to log. A formatter whose runtime is gone fails on
/// every render, so only the first failure of each run is logged; a success or a new formatter
/// ends the run.
struct FormatterFailureLog {
  /// A render reports its formatter's outcome against the generation it started in.
  private(set) var generation: UInt = 0
  private var loggedGeneration: UInt?

  mutating func formatterChanged() {
    generation &+= 1
  }

  mutating func succeeded(generation: UInt) {
    if loggedGeneration == generation { loggedGeneration = nil }
  }

  /// The message to log for the formatter's rejection, or nil when its run's first failure is
  /// already logged.
  mutating func failed(_ rejection: Error, generation: UInt) -> String? {
    guard loggedGeneration != generation else { return nil }
    loggedGeneration = generation
    return JSCallbackError(rejection).message
  }
}
