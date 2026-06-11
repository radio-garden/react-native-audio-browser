import Foundation
import Testing

@testable import AudioBrowserTestable

// MARK: - Helpers

private let allStates: [PlaybackState] = [
  .none, .ready, .playing, .paused, .stopped, .loading, .buffering, .error, .ended,
]

private let dummyError = TrackPlayerError.PlaybackError.playbackFailed

// MARK: - Unconditional Events

@Suite("Unconditional events — always produce their target state")
struct UnconditionalEventTests {
  @Test func stopped_alwaysTransitionsToStopped() {
    for state in allStates {
      #expect(nextPlaybackState(from: state, on: .stopped) == .stopped)
    }
  }

  @Test func trackLoading_alwaysTransitionsToLoading() {
    for state in allStates {
      #expect(nextPlaybackState(from: state, on: .trackLoading) == .loading)
    }
  }

  @Test func trackUnloaded_alwaysTransitionsToNone() {
    for state in allStates {
      #expect(nextPlaybackState(from: state, on: .trackUnloaded) == PlaybackState.none)
    }
  }

  @Test func trackEndedNaturally_alwaysTransitionsToEnded() {
    for state in allStates {
      #expect(nextPlaybackState(from: state, on: .trackEndedNaturally) == .ended)
    }
  }

  @Test func avPlayerWaiting_alwaysTransitionsToBuffering() {
    for state in allStates {
      #expect(nextPlaybackState(from: state, on: .avPlayerWaiting) == .buffering)
    }
  }

  @Test func avPlayerPlaying_alwaysTransitionsToPlaying() {
    for state in allStates {
      #expect(nextPlaybackState(from: state, on: .avPlayerPlaying) == .playing)
    }
  }

  @Test func audioFrameDecoded_alwaysTransitionsToPlaying() {
    for state in allStates {
      #expect(nextPlaybackState(from: state, on: .audioFrameDecoded) == .playing)
    }
  }

  @Test func errorOccurred_alwaysTransitionsToError() {
    for state in allStates {
      #expect(nextPlaybackState(from: state, on: .errorOccurred(dummyError)) == .error)
    }
  }
}

// MARK: - loadSeekCompleted

@Suite("loadSeekCompleted — only transitions from .loading")
struct LoadSeekCompletedTests {
  @Test func fromLoading_transitionsToReady() {
    #expect(nextPlaybackState(from: .loading, on: .loadSeekCompleted) == .ready)
  }

  @Test func fromOtherStates_isSuppressed() {
    for state in allStates where state != .loading {
      #expect(
        nextPlaybackState(from: state, on: .loadSeekCompleted) == nil,
        "Expected nil from \(state), got non-nil",
      )
    }
  }
}

// MARK: - avPlayerPaused

@Suite("avPlayerPaused — conditional on current state and hasAsset")
struct AVPlayerPausedTests {
  @Test func fromStopped_isSuppressed() {
    #expect(nextPlaybackState(from: .stopped, on: .avPlayerPaused(hasAsset: true)) == nil)
    #expect(nextPlaybackState(from: .stopped, on: .avPlayerPaused(hasAsset: false)) == nil)
  }

  @Test func fromError_hasAssetTrue_isSuppressed() {
    #expect(nextPlaybackState(from: .error, on: .avPlayerPaused(hasAsset: true)) == nil)
  }

  @Test func hasAssetFalse_transitionsToNone() {
    // hasAsset: false → .none from any non-stopped state
    let nonStoppedStates = allStates.filter { $0 != .stopped }
    for state in nonStoppedStates {
      #expect(
        nextPlaybackState(from: state, on: .avPlayerPaused(hasAsset: false)) == PlaybackState.none,
        "Expected .none from \(state) with hasAsset: false",
      )
    }
  }

  @Test func hasAssetTrue_transitionsToPaused() {
    // hasAsset: true → .paused from states that aren't stopped or error
    let validStates = allStates.filter { $0 != .stopped && $0 != .error }
    for state in validStates {
      #expect(
        nextPlaybackState(from: state, on: .avPlayerPaused(hasAsset: true)) == .paused,
        "Expected .paused from \(state) with hasAsset: true",
      )
    }
  }
}

// MARK: - bufferingSufficient

@Suite("bufferingSufficient — suppressed from .playing")
struct BufferingSufficientTests {
  @Test func fromPlaying_isSuppressed() {
    #expect(nextPlaybackState(from: .playing, on: .bufferingSufficient) == nil)
  }

  @Test func fromOtherStates_transitionsToReady() {
    for state in allStates where state != .playing {
      #expect(
        nextPlaybackState(from: state, on: .bufferingSufficient) == .ready,
        "Expected .ready from \(state)",
      )
    }
  }
}
