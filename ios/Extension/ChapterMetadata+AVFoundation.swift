import AVFoundation
import NitroModules

extension ChapterMetadata {
  /// Creates an array of ChapterMetadata from AVTimedMetadataGroup array.
  static func from(groups: [AVTimedMetadataGroup]) -> [ChapterMetadata] {
    groups.map { group in
      let startTime = CMTimeGetSeconds(group.timeRange.start)
      let endTime = CMTimeGetSeconds(group.timeRange.start) + CMTimeGetSeconds(group.timeRange.duration)

      // Extract metadata from chapter items
      // TODO: Artwork extraction missing. Requires converting binary data
      // to a URL (base64 data URL or temp file).
      // See also: Android implementation in MetadataAdapter.kt
      var title: String?
      var url: String?

      for item in group.items {
        if let commonKey = item.commonKey {
          switch commonKey {
          case .commonKeyTitle:
            title = item.stringValue
          default:
            break
          }
        }
      }

      return ChapterMetadata(
        startTime: startTime,
        endTime: endTime,
        title: title,
        url: url
      )
    }
  }
}
