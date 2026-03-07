import AVFoundation
import Testing

@testable import AudioBrowserTestable

// MARK: - Test Spy

@MainActor
private final class MediaLoaderDelegateSpy: MediaLoaderDelegate {
  var preparedItems: [AVPlayerItem] = []
  var unplayableCount = 0
  var retryableErrors: [Error] = []
  var playbackErrors: [TrackPlayerError.PlaybackError] = []
  var commonMetadata: [[AVMetadataItem]] = []
  var chapterMetadata: [[AVTimedMetadataGroup]] = []
  var timedMetadata: [[AVTimedMetadataGroup]] = []

  func mediaLoaderDidPrepareItem(_ item: AVPlayerItem) {
    preparedItems.append(item)
  }

  func mediaLoaderDidFailWithUnplayableTrack() {
    unplayableCount += 1
  }

  func mediaLoaderDidFailWithRetryableError(_ error: Error) {
    retryableErrors.append(error)
  }

  func mediaLoaderDidFailWithError(_ error: TrackPlayerError.PlaybackError) {
    playbackErrors.append(error)
  }

  func mediaLoaderDidReceiveCommonMetadata(_ items: [AVMetadataItem]) {
    commonMetadata.append(items)
  }

  func mediaLoaderDidReceiveChapterMetadata(_ groups: [AVTimedMetadataGroup]) {
    chapterMetadata.append(groups)
  }

  func mediaLoaderDidReceiveTimedMetadata(_ groups: [AVTimedMetadataGroup]) {
    timedMetadata.append(groups)
  }
}

@MainActor
private func makeLoader() -> (MediaLoader, MediaLoaderDelegateSpy) {
  let loader = MediaLoader()
  let spy = MediaLoaderDelegateSpy()
  loader.delegate = spy
  return (loader, spy)
}

// MARK: - Initial State

@Suite("initial state")
@MainActor
struct MediaLoaderInitialStateTests {
  @Test func assetIsNil() {
    let (loader, _) = makeLoader()
    #expect(loader.asset == nil)
  }

  @Test func bufferDurationIsZero() {
    let (loader, _) = makeLoader()
    #expect(loader.bufferDuration == 0)
  }
}

// MARK: - resolveAndLoad

@Suite("resolveAndLoad")
@MainActor
struct ResolveAndLoadTests {
  @Test func invalidUrl_callsDelegateWithError() {
    let (loader, spy) = makeLoader()
    loader.resolveAndLoad(src: "")
    #expect(spy.playbackErrors.count == 1)
    if case .invalidSourceUrl(let url) = spy.playbackErrors.first {
      #expect(url == "")
    } else {
      Issue.record("expected .invalidSourceUrl, got \(String(describing: spy.playbackErrors.first))")
    }
  }

  @Test func validHttpUrl_createsAsset() {
    let (loader, _) = makeLoader()
    loader.resolveAndLoad(src: "https://example.com/audio.mp3")
    #expect(loader.asset != nil)
    #expect(loader.asset?.url.absoluteString == "https://example.com/audio.mp3")
  }

  @Test func fileUrl_convertsCorrectly() {
    let (loader, _) = makeLoader()
    loader.resolveAndLoad(src: "file:///tmp/audio.mp3")
    #expect(loader.asset != nil)
    #expect(loader.asset?.url.isFileURL == true)
    #expect(loader.asset?.url.path == "/tmp/audio.mp3")
  }

  @Test func withResolver_callsResolver() async {
    let (loader, _) = makeLoader()
    var resolverCalled = false
    loader.mediaUrlResolver = { src in
      resolverCalled = true
      return MediaResolvedUrl(url: src, headers: nil, userAgent: nil)
    }
    loader.resolveAndLoad(src: "https://example.com/audio.mp3")

    // Allow the resolver task to run
    await Task.yield()

    #expect(resolverCalled == true)
  }

  @Test func withResolver_returningInvalidUrl_callsErrorDelegate() async {
    let (loader, spy) = makeLoader()
    loader.mediaUrlResolver = { _ in
      MediaResolvedUrl(url: "", headers: nil, userAgent: nil)
    }
    loader.resolveAndLoad(src: "https://example.com/audio.mp3")

    // The resolver runs in a Task that awaits the resolver then does MainActor.run,
    // so we need multiple yields for the full chain to settle.
    for _ in 0..<10 {
      await Task.yield()
      if !spy.playbackErrors.isEmpty { break }
    }

    #expect(spy.playbackErrors.count == 1)
    if case .invalidSourceUrl(let url) = spy.playbackErrors.first {
      #expect(url == "")
    } else {
      Issue.record("expected .invalidSourceUrl")
    }
  }

  @Test func cancelsPreviousResolverTask() async {
    let (loader, _) = makeLoader()
    var callCount = 0
    loader.mediaUrlResolver = { src in
      callCount += 1
      // Simulate slow resolution
      try? await Task.sleep(for: .milliseconds(100))
      return MediaResolvedUrl(url: src, headers: nil, userAgent: nil)
    }

    // Start first resolve
    loader.resolveAndLoad(src: "https://example.com/first.mp3")
    // Immediately start second — should cancel first
    loader.resolveAndLoad(src: "https://example.com/second.mp3")

    // Wait for both to settle
    try? await Task.sleep(for: .milliseconds(200))

    // Only the second resolver should have run (first was cancelled before calling resolver)
    #expect(callCount == 1)
    // The asset should be from the second call
    #expect(loader.asset?.url.absoluteString == "https://example.com/second.mp3")
  }
}

// MARK: - cancelAll

@Suite("cancelAll")
@MainActor
struct CancelAllTests {
  @Test func preventsDelegateCallbacksAfterResolveAndLoad() async {
    let (loader, spy) = makeLoader()
    loader.mediaUrlResolver = { src in
      try? await Task.sleep(for: .milliseconds(50))
      return MediaResolvedUrl(url: src, headers: nil, userAgent: nil)
    }
    loader.resolveAndLoad(src: "https://example.com/audio.mp3")
    loader.cancelAll()

    // Wait for what would have been the callback
    try? await Task.sleep(for: .milliseconds(100))

    #expect(spy.preparedItems.isEmpty)
    #expect(spy.playbackErrors.isEmpty)
  }
}

// MARK: - clearAsset

@Suite("clearAsset")
@MainActor
struct ClearAssetTests {
  @Test func nilsOutAsset() {
    let (loader, _) = makeLoader()
    loader.resolveAndLoad(src: "https://example.com/audio.mp3")
    #expect(loader.asset != nil)
    loader.clearAsset()
    #expect(loader.asset == nil)
  }

  @Test func noopWhenAssetIsNil() {
    let (loader, _) = makeLoader()
    // Should not crash
    loader.clearAsset()
    #expect(loader.asset == nil)
  }
}

// MARK: - Async loading

@Suite("async loading")
@MainActor
struct AsyncLoadingTests {
  @Test func nonExistentLocalFile_eventuallyCallsRetryableError() async {
    let (loader, spy) = makeLoader()
    loader.resolveAndLoad(src: "file:///nonexistent/path.mp3")

    // Poll for delegate callback — asset loading is async
    for _ in 0..<50 {
      if !spy.retryableErrors.isEmpty || !spy.playbackErrors.isEmpty || !spy.preparedItems.isEmpty {
        break
      }
      try? await Task.sleep(for: .milliseconds(100))
    }

    // AVFoundation will fail deterministically for a non-existent local file
    let hasError = !spy.retryableErrors.isEmpty || spy.unplayableCount > 0
    #expect(hasError, "expected a retryable error or unplayable callback for non-existent file")
  }
}
