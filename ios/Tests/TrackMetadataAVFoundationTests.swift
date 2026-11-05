import AVFoundation
import Foundation
import Testing

@testable import AudioBrowserTestable

// MARK: - Common Identifiers

@Suite("TrackMetadata.from — common identifiers")
struct TrackMetadataCommonIdentifierTests {
  @Test func commonIdentifiers_extractTitleArtistAlbumDescriptionDate() {
    let items = [
      makeMetadataItem(identifier: .commonIdentifierTitle, value: "My Title"),
      makeMetadataItem(identifier: .commonIdentifierArtist, value: "My Artist"),
      makeMetadataItem(identifier: .commonIdentifierAlbumName, value: "My Album"),
      makeMetadataItem(identifier: .commonIdentifierDescription, value: "My Description"),
      makeMetadataItem(identifier: .commonIdentifierCreationDate, value: "2024-01-15"),
    ]

    let metadata = TrackMetadata.from(items: items)

    #expect(metadata.title == "My Title")
    #expect(metadata.artist == "My Artist")
    #expect(metadata.albumTitle == "My Album")
    #expect(metadata.description == "My Description")
    #expect(metadata.creationDate == "2024-01-15")
    #expect(metadata.creationYear == "2024")
  }

  @Test func commonKeyFallback_whenIdentifierDoesNotMatch() {
    let items = [
      makeMetadataItem(commonKey: .commonKeyTitle, value: "Fallback Title"),
      makeMetadataItem(commonKey: .commonKeyArtist, value: "Fallback Artist"),
      makeMetadataItem(commonKey: .commonKeyAlbumName, value: "Fallback Album"),
      makeMetadataItem(commonKey: .commonKeySubject, value: "Fallback Subtitle"),
      makeMetadataItem(commonKey: .commonKeyDescription, value: "Fallback Description"),
      makeMetadataItem(commonKey: .commonKeyCreationDate, value: "2023-06-01"),
      makeMetadataItem(commonKey: .commonKeyAuthor, value: "Fallback Composer"),
    ]

    let metadata = TrackMetadata.from(items: items)

    #expect(metadata.title == "Fallback Title")
    #expect(metadata.artist == "Fallback Artist")
    #expect(metadata.albumTitle == "Fallback Album")
    #expect(metadata.subtitle == "Fallback Subtitle")
    #expect(metadata.description == "Fallback Description")
    #expect(metadata.creationDate == "2023-06-01")
    #expect(metadata.composer == "Fallback Composer")
  }

  @Test func identifierWinsOverCommonKey() {
    // .quickTimeMetadataTitle reaches title only through its commonKey fallback.
    let items = [
      makeMetadataItem(identifier: .commonIdentifierTitle, value: "Identifier Title"),
      makeMetadataItem(identifier: .quickTimeMetadataTitle, value: "QuickTime Title"),
    ]

    let metadata = TrackMetadata.from(items: items)
    #expect(metadata.title == "Identifier Title")
  }
}

// MARK: - ID3 Keys

@Suite("TrackMetadata.from — ID3-specific keys")
struct TrackMetadataID3Tests {
  @Test func id3Keys_extractSubtitleTrackNumberComposerConductorGenre() {
    let items = [
      makeMetadataItem(keySpace: .id3, key: "TIT3", value: "ID3 Subtitle"),
      makeMetadataItem(keySpace: .id3, key: "TRCK", value: "5"),
      makeMetadataItem(keySpace: .id3, key: "TCOM", value: "ID3 Composer"),
      makeMetadataItem(keySpace: .id3, key: "TPE3", value: "ID3 Conductor"),
      makeMetadataItem(keySpace: .id3, key: "TCON", value: "Rock"),
    ]

    let metadata = TrackMetadata.from(items: items)

    #expect(metadata.subtitle == "ID3 Subtitle")
    #expect(metadata.trackNumber == "5")
    #expect(metadata.composer == "ID3 Composer")
    #expect(metadata.conductor == "ID3 Conductor")
    #expect(metadata.genre == "Rock")
  }

  @Test func id3RecordingTime_TDRC_setsCreationDate() {
    let items = [
      makeMetadataItem(keySpace: .id3, key: "TDRC", value: "2024-03-15"),
    ]

    let metadata = TrackMetadata.from(items: items)
    #expect(metadata.creationDate == "2024-03-15")
    #expect(metadata.creationYear == "2024")
  }

  @Test func id3Year_TYER_setsCreationYear() {
    let items = [
      makeMetadataItem(keySpace: .id3, key: "TYER", value: "1999"),
    ]

    let metadata = TrackMetadata.from(items: items)
    #expect(metadata.creationYear == "1999")
  }
}

// MARK: - iTunes Identifiers

@Suite("TrackMetadata.from — iTunes identifiers")
struct TrackMetadataITunesTests {
  @Test func iTunesIdentifiers_extractFields() {
    let items = [
      makeMetadataItem(identifier: .iTunesMetadataSongName, value: "iTunes Title"),
      makeMetadataItem(identifier: .iTunesMetadataArtist, value: "iTunes Artist"),
      makeMetadataItem(identifier: .iTunesMetadataAlbum, value: "iTunes Album"),
      makeMetadataItem(identifier: .iTunesMetadataComposer, value: "iTunes Composer"),
      makeMetadataItem(identifier: .iTunesMetadataDescription, value: "iTunes Description"),
      makeMetadataItem(identifier: .iTunesMetadataUserGenre, value: "iTunes Genre"),
      makeMetadataItem(identifier: .iTunesMetadataTrackNumber, value: "7"),
      makeMetadataItem(identifier: .iTunesMetadataReleaseDate, value: "2024-06-01"),
    ]

    let metadata = TrackMetadata.from(items: items)

    #expect(metadata.title == "iTunes Title")
    #expect(metadata.artist == "iTunes Artist")
    #expect(metadata.albumTitle == "iTunes Album")
    #expect(metadata.composer == "iTunes Composer")
    #expect(metadata.description == "iTunes Description")
    #expect(metadata.genre == "iTunes Genre")
    #expect(metadata.trackNumber == "7")
    #expect(metadata.creationDate == "2024-06-01")
  }
}

// MARK: - Year Extraction & Edge Cases

@Suite("TrackMetadata.from — year extraction and edge cases")
struct TrackMetadataEdgeCaseTests {
  @Test func yearExtractedFromCreationDate() {
    let items = [
      makeMetadataItem(identifier: .commonIdentifierCreationDate, value: "2024-12-25T10:00:00Z"),
    ]

    let metadata = TrackMetadata.from(items: items)
    #expect(metadata.creationYear == "2024")
  }

  @Test func shortCreationDate_noYearExtracted() {
    let items = [
      makeMetadataItem(identifier: .commonIdentifierCreationDate, value: "24"),
    ]

    let metadata = TrackMetadata.from(items: items)
    #expect(metadata.creationYear == nil)
  }

  @Test func emptyInput_allFieldsNil() {
    let metadata = TrackMetadata.from(items: [])

    #expect(metadata.title == nil)
    #expect(metadata.artist == nil)
    #expect(metadata.albumTitle == nil)
    #expect(metadata.subtitle == nil)
    #expect(metadata.description == nil)
    #expect(metadata.artworkUri == nil)
    #expect(metadata.trackNumber == nil)
    #expect(metadata.composer == nil)
    #expect(metadata.conductor == nil)
    #expect(metadata.genre == nil)
    #expect(metadata.creationDate == nil)
    #expect(metadata.creationYear == nil)
  }
}
