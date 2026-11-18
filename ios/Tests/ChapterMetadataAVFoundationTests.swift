import AVFoundation
import CoreMedia
import Foundation
import Testing

@testable import AudioBrowserTestable

// MARK: - Helpers

private func makeTimedMetadataGroup(
  start: Double,
  duration: Double,
  title: String? = nil,
) -> AVTimedMetadataGroup {
  let timeRange = CMTimeRange(
    start: CMTime(seconds: start, preferredTimescale: 1000),
    duration: CMTime(seconds: duration, preferredTimescale: 1000),
  )

  var items: [AVMetadataItem] = []
  if let title {
    let item = AVMutableMetadataItem()
    item.key = AVMetadataKey.commonKeyTitle as NSString
    item.keySpace = .common
    item.value = title as NSString
    items.append(item)
  }

  return AVTimedMetadataGroup(items: items, timeRange: timeRange)
}

// MARK: - Tests

@Suite("ChapterMetadata.from")
struct ChapterMetadataTests {
  @Test func timeRangeExtraction_startAndEndTime() {
    let group = makeTimedMetadataGroup(start: 10.0, duration: 30.0, title: "Chapter 1")

    let chapters = ChapterMetadata.from(groups: [group])

    #expect(chapters.count == 1)
    #expect(chapters[0].startTime == 10.0)
    #expect(chapters[0].endTime == 40.0)
    #expect(chapters[0].title == "Chapter 1")
  }

  @Test func titleFromCommonKeyTitle() {
    let group = makeTimedMetadataGroup(start: 0.0, duration: 5.0, title: "Intro")

    let chapters = ChapterMetadata.from(groups: [group])
    #expect(chapters[0].title == "Intro")
  }

  @Test func multipleChapters_orderingPreserved() {
    let groups = [
      makeTimedMetadataGroup(start: 0.0, duration: 60.0, title: "Chapter 1"),
      makeTimedMetadataGroup(start: 60.0, duration: 120.0, title: "Chapter 2"),
      makeTimedMetadataGroup(start: 180.0, duration: 60.0, title: "Chapter 3"),
    ]

    let chapters = ChapterMetadata.from(groups: groups)

    #expect(chapters.count == 3)
    #expect(chapters[0].title == "Chapter 1")
    #expect(chapters[0].startTime == 0.0)
    #expect(chapters[1].title == "Chapter 2")
    #expect(chapters[1].startTime == 60.0)
    #expect(chapters[2].title == "Chapter 3")
    #expect(chapters[2].startTime == 180.0)
    #expect(chapters[2].endTime == 240.0)
  }

  @Test func missingTitle_returnsNil() {
    let group = makeTimedMetadataGroup(start: 5.0, duration: 10.0, title: nil)

    let chapters = ChapterMetadata.from(groups: [group])

    #expect(chapters.count == 1)
    #expect(chapters[0].title == nil)
    #expect(chapters[0].startTime == 5.0)
    #expect(chapters[0].endTime == 15.0)
  }

  @Test func emptyGroups_returnsEmptyArray() {
    let chapters = ChapterMetadata.from(groups: [])
    #expect(chapters.isEmpty)
  }
}
