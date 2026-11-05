import AVFoundation

#if canImport(NitroModules)
  import NitroModules
#endif

extension TrackMetadata {
  /// Reads common, ID3 and iTunes metadata items. A common identifier wins over the other key
  /// spaces; among those, the first item to set a field keeps it. `creationYear` falls back to the
  /// first four characters of `creationDate`.
  static func from(items: [AVMetadataItem]) -> TrackMetadata {
    var title: String?
    var artist: String?
    var albumTitle: String?
    var subtitle: String?
    var description: String?
    let artworkUri: String? = nil
    var trackNumber: String?
    var composer: String?
    var conductor: String?
    var genre: String?
    var creationDate: String?
    var creationYear: String?

    for item in items {
      switch item.identifier {
      case .commonIdentifierTitle:
        title = item.stringValue
      case .commonIdentifierArtist:
        artist = item.stringValue
      case .commonIdentifierAlbumName:
        albumTitle = item.stringValue
      case .commonIdentifierDescription:
        description = item.stringValue
      case .commonIdentifierCreationDate:
        creationDate = item.stringValue
      default:
        break
      }

      if let commonKey = item.commonKey {
        switch commonKey {
        case .commonKeyTitle:
          title = title ?? item.stringValue
        case .commonKeyArtist:
          artist = artist ?? item.stringValue
        case .commonKeyAlbumName:
          albumTitle = albumTitle ?? item.stringValue
        case .commonKeySubject:
          subtitle = subtitle ?? item.stringValue
        case .commonKeyDescription:
          description = description ?? item.stringValue
        case .commonKeyCreationDate:
          creationDate = creationDate ?? item.stringValue
        case .commonKeyAuthor:
          composer = composer ?? item.stringValue
        default:
          break
        }
      }

      if item.keySpace == .id3, let key = item.key as? String {
        switch key {
        case "TIT3":
          subtitle = subtitle ?? item.stringValue
        case "TRCK":
          trackNumber = trackNumber ?? item.stringValue
        case "TCOM":
          composer = composer ?? item.stringValue
        case "TPE3":
          conductor = conductor ?? item.stringValue
        case "TCON":
          genre = genre ?? item.stringValue
        case "TDRC": // ID3v2.4 recording time; v2.3 has only TYER, the year
          creationDate = creationDate ?? item.stringValue
        case "TYER":
          creationYear = creationYear ?? item.stringValue
        default:
          break
        }
      }

      switch item.identifier {
      case .iTunesMetadataSongName:
        title = title ?? item.stringValue
      case .iTunesMetadataArtist:
        artist = artist ?? item.stringValue
      case .iTunesMetadataAlbum:
        albumTitle = albumTitle ?? item.stringValue
      case .iTunesMetadataComposer:
        composer = composer ?? item.stringValue
      case .iTunesMetadataDescription:
        description = description ?? item.stringValue
      case .iTunesMetadataUserGenre:
        genre = genre ?? item.stringValue
      case .iTunesMetadataTrackNumber:
        trackNumber = trackNumber ?? item.stringValue
      case .iTunesMetadataReleaseDate:
        creationDate = creationDate ?? item.stringValue
      default:
        break
      }
    }

    if creationYear == nil, let date = creationDate, date.count >= 4 {
      creationYear = String(date.prefix(4))
    }

    return TrackMetadata(
      title: title,
      artist: artist,
      albumTitle: albumTitle,
      subtitle: subtitle,
      description: description,
      artworkUri: artworkUri,
      trackNumber: trackNumber,
      composer: composer,
      conductor: conductor,
      genre: genre,
      compilation: nil,
      station: nil,
      mediaType: nil,
      creationDate: creationDate,
      creationYear: creationYear,
      url: nil,
    )
  }
}
