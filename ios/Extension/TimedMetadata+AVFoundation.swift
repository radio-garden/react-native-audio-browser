import AVFoundation
import NitroModules

extension TimedMetadata {
  /// Creates TimedMetadata from AVMetadataItem array, detecting the metadata format.
  static func from(items: [AVMetadataItem]) -> TimedMetadata? {
    // Check for ICY metadata (Shoutcast/Icecast streams)
    let icyItems = items.filter { $0.keySpace == .icy }
    if !icyItems.isEmpty {
      return fromIcy(items: icyItems)
    }

    // Check for ID3 metadata (MP3)
    let id3Items = items.filter { $0.keySpace == .id3 }
    if !id3Items.isEmpty {
      return fromId3(items: id3Items)
    }

    // Fall back to common metadata identifiers (covers QuickTime, iTunes, etc.)
    return fromCommon(items: items)
  }

  /// Creates TimedMetadata from ICY (Shoutcast/Icecast) metadata.
  static func fromIcy(items: [AVMetadataItem]) -> TimedMetadata? {
    var title: String?

    for item in items {
      if let key = item.key as? String, key == "StreamTitle" {
        title = item.stringValue
      }
    }

    guard let title else { return nil }
    return TimedMetadata(
      title: title,
      artist: nil,
      album: nil,
      date: nil,
      genre: nil
    )
  }

  /// Creates TimedMetadata from ID3 metadata (MP3).
  static func fromId3(items: [AVMetadataItem]) -> TimedMetadata? {
    var title: String?
    var artist: String?
    var album: String?
    var date: String?
    var genre: String?

    for item in items {
      switch item.identifier {
      case .id3MetadataTitleDescription:
        title = item.stringValue
      case .id3MetadataLeadPerformer, .id3MetadataOriginalArtist:
        artist = item.stringValue
      case .id3MetadataAlbumTitle, .id3MetadataOriginalAlbumTitle:
        album = item.stringValue
      case .id3MetadataRecordingTime:
        date = item.stringValue  // TDRC takes precedence (ID3v2.4, more precise)
      case .id3MetadataYear:
        date = date ?? item.stringValue  // TYER only if TDRC not found (ID3v2.3)
      case .id3MetadataContentType:
        genre = item.stringValue
      default:
        break
      }
    }

    guard title != nil || artist != nil || album != nil || date != nil || genre != nil else { return nil }
    return TimedMetadata(
      title: title,
      artist: artist,
      album: album,
      date: date,
      genre: genre
    )
  }

  /// Creates TimedMetadata from common metadata identifiers.
  static func fromCommon(items: [AVMetadataItem]) -> TimedMetadata? {
    var title: String?
    var artist: String?
    var album: String?
    var date: String?
    var genre: String?

    for item in items {
      switch item.identifier {
      case .commonIdentifierTitle:
        title = item.stringValue
      case .commonIdentifierArtist:
        artist = item.stringValue
      case .commonIdentifierAlbumName:
        album = item.stringValue
      case .commonIdentifierCreationDate:
        date = item.stringValue
      default:
        // Check by commonKey for broader compatibility
        if let commonKey = item.commonKey {
          switch commonKey {
          case .commonKeyTitle:
            title = title ?? item.stringValue
          case .commonKeyArtist:
            artist = artist ?? item.stringValue
          case .commonKeyAlbumName:
            album = album ?? item.stringValue
          case .commonKeyCreationDate:
            date = date ?? item.stringValue
          default:
            break
          }
        }
      }
    }

    guard title != nil || artist != nil || album != nil || date != nil || genre != nil else { return nil }
    return TimedMetadata(
      title: title,
      artist: artist,
      album: album,
      date: date,
      genre: genre
    )
  }
}
