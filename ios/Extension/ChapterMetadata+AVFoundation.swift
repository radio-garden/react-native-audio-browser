import AVFoundation

#if canImport(NitroModules)
  import NitroModules
#endif

extension ChapterMetadata {
  static func from(groups: [AVTimedMetadataGroup]) -> [ChapterMetadata] {
    groups.map { group in
      let startTime = CMTimeGetSeconds(group.timeRange.start)
      let endTime = CMTimeGetSeconds(group.timeRange.start) + CMTimeGetSeconds(group.timeRange.duration)

      var title: String?
      // TODO: chapter artwork is not extracted; it needs its binary data turned into a URL (a
      // base64 data URL or a temp file). Android has the same gap: MetadataAdapter.kt
      let url: String? = nil

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
        url: url,
      )
    }
  }
}
