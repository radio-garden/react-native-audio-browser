@testable import AudioBrowserTestable
import Testing

@Suite("ErrorStatus.after(event:from:to:)")
struct ErrorStatusTests {
  private let retrying = ErrorStatus.retrying(.hostUnreachable)

  @Test func anErrorEventIsATerminalReport() {
    let status = ErrorStatus.none.after(event: .errorOccurred(.playbackFailed), from: .playing, to: .error)
    #expect(status.error == .playbackFailed)
    #expect(!status.isRetrying)
  }

  @Test func aRepeatedTerminalReportDiffersFromTheLast() {
    let first = ErrorStatus.none.after(event: .errorOccurred(.playbackFailed), from: .playing, to: .error)
    let repeated = first.after(event: .errorOccurred(.playbackFailed), from: .error, to: .error)
    #expect(repeated.error == .playbackFailed)
    #expect(repeated != first)
  }

  @Test func anErrorEventReplacesARetryingError() {
    let status = retrying.after(event: .errorOccurred(.playbackFailed), from: .loading, to: .error)
    #expect(status.error == .playbackFailed)
    #expect(!status.isRetrying)
  }

  @Test func leavingErrorClearsIt() {
    let terminal = ErrorStatus.none.after(event: .errorOccurred(.playbackFailed), from: .playing, to: .error)
    #expect(terminal.after(event: .trackLoading, from: .error, to: .loading) == .none)
  }

  @Test(arguments: [PlaybackState.loading, .buffering, .paused])
  func aRetryingErrorRidesAlongWhereItShows(_ state: PlaybackState) {
    #expect(retrying.after(event: .trackLoading, from: .loading, to: state) == retrying)
  }

  @Test(arguments: [PlaybackState.ready, .playing, .stopped, .none, .ended])
  func aRetryingErrorClearsWhereItDoesNotShow(_ state: PlaybackState) {
    #expect(retrying.after(event: .avPlayerPlaying, from: .loading, to: state) == .none)
  }

  @Test func otherwiseTheStatusHolds() {
    #expect(ErrorStatus.none.after(event: .avPlayerPlaying, from: .ready, to: .playing) == .none)
  }

  @Test(arguments: [PlaybackState.none, .loading, .ready, .buffering, .playing, .paused, .stopped, .ended, .error])
  func onlyLoadingBufferingAndPausedCarryAnAdvisoryError(_ state: PlaybackState) {
    #expect(state.carriesAdvisoryError == [.loading, .buffering, .paused].contains(state))
  }
}
