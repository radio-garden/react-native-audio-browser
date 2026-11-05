import AVFoundation

#if canImport(NitroModules)
  import NitroModules
#endif

extension TimedMetadata {
  /// Reads the ICY items if there are any, else the ID3 items, else the common identifiers;
  /// nil when the chosen set yields no field. `icyCharset` re-decodes an ICY title AVFoundation
  /// decoded as Latin-1 and never applies to ID3 or common metadata.
  static func from(items: [AVMetadataItem], icyCharset: String? = nil) -> TimedMetadata? {
    let icyItems = items.filter { $0.keySpace == .icy }
    if !icyItems.isEmpty {
      return fromIcy(items: icyItems, icyCharset: icyCharset)
    }

    let id3Items = items.filter { $0.keySpace == .id3 }
    if !id3Items.isEmpty {
      return fromId3(items: id3Items)
    }

    return fromCommon(items: items)
  }

  static func fromIcy(items: [AVMetadataItem], icyCharset: String? = nil) -> TimedMetadata? {
    var title: String?

    for item in items {
      if let key = item.key as? String, key == "StreamTitle" {
        title = item.stringValue
      }
    }

    guard let title else { return nil }
    return TimedMetadata(
      title: icyCharset.map { title.redecodingIcyTitle(charset: $0) } ?? title,
      artist: nil,
      album: nil,
      date: nil,
      genre: nil,
    )
  }

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
      // TDRC (ID3v2.4) wins over TYER (ID3v2.3) whichever comes first.
      case .id3MetadataRecordingTime:
        date = item.stringValue
      case .id3MetadataYear:
        date = date ?? item.stringValue
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
      genre: genre,
    )
  }

  static func fromCommon(items: [AVMetadataItem]) -> TimedMetadata? {
    var title: String?
    var artist: String?
    var album: String?
    var date: String?
    let genre: String? = nil

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
      genre: genre,
    )
  }
}
