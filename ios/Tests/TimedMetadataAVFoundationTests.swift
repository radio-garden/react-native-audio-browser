import AVFoundation
import Foundation
import Testing

@testable import AudioBrowserTestable

// MARK: - ICY Metadata

@Suite("TimedMetadata.from — ICY (Shoutcast/Icecast)")
struct TimedMetadataICYTests {
  @Test func icyStreamTitle_extractsTitle() {
    let item = AVMutableMetadataItem()
    item.key = "StreamTitle" as NSString
    item.keySpace = .icy
    item.value = "Live Radio Show" as NSString

    let result = TimedMetadata.from(items: [item])

    #expect(result?.title == "Live Radio Show")
    #expect(result?.artist == nil)
  }

  private func icyTitle(_ value: String) -> AVMetadataItem {
    let item = AVMutableMetadataItem()
    item.key = "StreamTitle" as NSString
    item.keySpace = .icy
    item.value = value as NSString
    return item
  }

  // "Алия" as windows-1251 bytes, decoded as Latin-1 the way AVFoundation does when it guesses wrong.
  private let latin1Shaped = "\u{C0}\u{EB}\u{E8}\u{FF}"

  @Test func icyCharset_redecodesLatin1ShapedTitle() {
    let result = TimedMetadata.from(items: [icyTitle(latin1Shaped)], icyCharset: "windows-1251")
    #expect(result?.title == "Алия")
  }

  @Test func icyCharset_leavesAlreadyDecodedTitle() {
    let result = TimedMetadata.from(items: [icyTitle("Алия")], icyCharset: "windows-1251")
    #expect(result?.title == "Алия")
  }

  @Test func icyCharset_leavesAscii() {
    let result = TimedMetadata.from(items: [icyTitle("Plain Title")], icyCharset: "windows-1251")
    #expect(result?.title == "Plain Title")
  }

  @Test func icyCharset_ignoresUnknownCharset() {
    let result = TimedMetadata.from(items: [icyTitle(latin1Shaped)], icyCharset: "no-such-charset")
    #expect(result?.title == latin1Shaped)
  }

  @Test func icyCharset_absent_keepsTitle() {
    let result = TimedMetadata.from(items: [icyTitle(latin1Shaped)])
    #expect(result?.title == latin1Shaped)
  }

  @Test func icyCharset_neverTouchesId3() {
    let items = [makeMetadataItem(identifier: .id3MetadataTitleDescription, value: latin1Shaped)]
    let result = TimedMetadata.from(items: items, icyCharset: "windows-1251")
    #expect(result?.title == latin1Shaped)
  }

  @Test func icyWithoutStreamTitle_returnsNil() {
    let item = AVMutableMetadataItem()
    item.key = "StreamUrl" as NSString
    item.keySpace = .icy
    item.value = "http://example.com" as NSString

    let result = TimedMetadata.from(items: [item])
    #expect(result == nil)
  }
}

// MARK: - ID3 Metadata

@Suite("TimedMetadata.from — ID3")
struct TimedMetadataID3Tests {
  @Test func id3Fields_extractTitleArtistAlbumDateGenre() {
    let items = [
      makeMetadataItem(identifier: .id3MetadataTitleDescription, value: "ID3 Title"),
      makeMetadataItem(identifier: .id3MetadataLeadPerformer, value: "ID3 Artist"),
      makeMetadataItem(identifier: .id3MetadataAlbumTitle, value: "ID3 Album"),
      makeMetadataItem(identifier: .id3MetadataRecordingTime, value: "2024-01-01"),
      makeMetadataItem(identifier: .id3MetadataContentType, value: "Jazz"),
    ]

    let result = TimedMetadata.from(items: items)

    #expect(result?.title == "ID3 Title")
    #expect(result?.artist == "ID3 Artist")
    #expect(result?.album == "ID3 Album")
    #expect(result?.date == "2024-01-01")
    #expect(result?.genre == "Jazz")
  }

  @Test func id3RecordingTime_takesPrecedenceOverYear() {
    let items = [
      makeMetadataItem(identifier: .id3MetadataRecordingTime, value: "2024-06-15"),
      makeMetadataItem(identifier: .id3MetadataYear, value: "2023"),
    ]

    let result = TimedMetadata.from(items: items)
    #expect(result?.date == "2024-06-15")
  }

  @Test func id3YearFallback_whenNoRecordingTime() {
    let items = [
      makeMetadataItem(identifier: .id3MetadataYear, value: "2023"),
    ]

    let result = TimedMetadata.from(items: items)
    #expect(result?.date == "2023")
  }

  @Test func id3AllEmpty_returnsNil() {
    // Items exist but don't match any known ID3 identifiers
    let item = makeMetadataItem(identifier: .id3MetadataEncodedBy, value: "encoder")

    let result = TimedMetadata.from(items: [item])
    // The item has id3 keySpace, so it enters fromId3 which doesn't match .id3MetadataEncodedBy
    #expect(result == nil)
  }
}

// MARK: - Common Metadata

@Suite("TimedMetadata.from — Common identifiers")
struct TimedMetadataCommonTests {
  @Test func commonIdentifiers_extractFields() {
    let items = [
      makeMetadataItem(identifier: .commonIdentifierTitle, value: "Common Title"),
      makeMetadataItem(identifier: .commonIdentifierArtist, value: "Common Artist"),
      makeMetadataItem(identifier: .commonIdentifierAlbumName, value: "Common Album"),
      makeMetadataItem(identifier: .commonIdentifierCreationDate, value: "2024"),
    ]

    let result = TimedMetadata.from(items: items)

    #expect(result?.title == "Common Title")
    #expect(result?.artist == "Common Artist")
    #expect(result?.album == "Common Album")
    #expect(result?.date == "2024")
  }

  @Test func commonKeyFallback_whenIdentifierDoesNotMatch() {
    let items = [
      makeMetadataItem(commonKey: .commonKeyTitle, value: "Key Title"),
      makeMetadataItem(commonKey: .commonKeyArtist, value: "Key Artist"),
      makeMetadataItem(commonKey: .commonKeyAlbumName, value: "Key Album"),
      makeMetadataItem(commonKey: .commonKeyCreationDate, value: "2023"),
    ]

    let result = TimedMetadata.from(items: items)

    #expect(result?.title == "Key Title")
    #expect(result?.artist == "Key Artist")
    #expect(result?.album == "Key Album")
    #expect(result?.date == "2023")
  }

  @Test func commonAllEmpty_returnsNil() {
    let result = TimedMetadata.from(items: [])
    #expect(result == nil)
  }
}

// MARK: - Format Detection Priority

@Suite("TimedMetadata.from — format priority")
struct TimedMetadataFormatPriorityTests {
  @Test func icyTakesPriorityOverID3() {
    let icyItem = AVMutableMetadataItem()
    icyItem.key = "StreamTitle" as NSString
    icyItem.keySpace = .icy
    icyItem.value = "ICY Title" as NSString

    let id3Item = makeMetadataItem(identifier: .id3MetadataTitleDescription, value: "ID3 Title")

    let result = TimedMetadata.from(items: [icyItem, id3Item])

    #expect(result?.title == "ICY Title")
  }

  @Test func id3TakesPriorityOverCommon() {
    let id3Item = makeMetadataItem(identifier: .id3MetadataTitleDescription, value: "ID3 Title")
    let commonItem = makeMetadataItem(identifier: .commonIdentifierTitle, value: "Common Title")

    let result = TimedMetadata.from(items: [id3Item, commonItem])

    #expect(result?.title == "ID3 Title")
  }
}
