import AVFoundation
import NitroModules

extension TrackMetadata {
  /// Creates TrackMetadata from AVMetadataItem array using common identifiers.
  static func from(items: [AVMetadataItem]) -> TrackMetadata {
    var title: String?
    var artist: String?
    var albumTitle: String?
    var subtitle: String?
    var description: String?
    var artworkUri: String?
    var trackNumber: String?
    var composer: String?
    var conductor: String?
    var genre: String?
    var creationDate: String?
    var creationYear: String?

    for item in items {
      // Try common identifiers first
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

      // Also check commonKey for broader compatibility
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

      // Check ID3-specific identifiers for additional fields
      if item.keySpace == .id3, let key = item.key as? String {
        switch key {
        case "TIT3": // Subtitle
          subtitle = subtitle ?? item.stringValue
        case "TRCK": // Track number
          trackNumber = trackNumber ?? item.stringValue
        case "TCOM": // Composer
          composer = composer ?? item.stringValue
        case "TPE3": // Conductor
          conductor = conductor ?? item.stringValue
        case "TCON": // Content type (genre)
          genre = genre ?? item.stringValue
        case "TDRC": // Recording time (ID3v2.4)
          creationDate = creationDate ?? item.stringValue
        case "TYER": // Year (ID3v2.3)
          creationYear = creationYear ?? item.stringValue
        default:
          break
        }
      }

      // Check iTunes-specific identifiers
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

    // Extract year from creationDate if creationYear not set
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
      url: nil
    )
  }
}
