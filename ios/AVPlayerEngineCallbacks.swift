import AVFoundation
import MediaPlayer
import NitroModules

/// What the AVPlayerEngine reports to HybridAudioBrowser. The `playerDid` prefix keeps the
/// methods from colliding with HybridAudioBrowser's JS callback properties.
@MainActor
protocol AVPlayerEngineCallbacks: RemoteCommandCallbacks {
  func playerDidReceiveCommonMetadata(_ metadata: [AVMetadataItem])
  func playerDidReceiveChapterMetadata(_ metadata: [AVTimedMetadataGroup])
  func playerDidChangeNowPlaying(_ metadata: NowPlayingMetadata)
}
