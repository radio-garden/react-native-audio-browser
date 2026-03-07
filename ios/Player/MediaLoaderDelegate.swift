import AVFoundation

@MainActor protocol MediaLoaderDelegate: AnyObject {
  func mediaLoaderDidPrepareItem(_ item: AVPlayerItem)
  func mediaLoaderDidFailWithUnplayableTrack()
  func mediaLoaderDidFailWithRetryableError(_ error: Error)
  func mediaLoaderDidFailWithError(_ error: TrackPlayerError.PlaybackError)
  func mediaLoaderDidReceiveCommonMetadata(_ items: [AVMetadataItem])
  func mediaLoaderDidReceiveChapterMetadata(_ groups: [AVTimedMetadataGroup])
  func mediaLoaderDidReceiveTimedMetadata(_ groups: [AVTimedMetadataGroup])
}
