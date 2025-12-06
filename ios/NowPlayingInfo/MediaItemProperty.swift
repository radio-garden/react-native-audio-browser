import Foundation
import MediaPlayer

/**
 Enum representing MPMediaItemProperties.
 Docs for each property is taken from [Apple docs](https://developer.apple.com/documentation/mediaplayer/mpmediaitem/general_media_item_property_keys)
 */
enum MediaItemProperty: NowPlayingInfoKeyValue {
  /**
   The performing artist(s) for a media item—which may vary from the primary artist for the album that a media item belongs to.

   For example, if the album artist is “Joseph Fable,” the artist for one of the songs in the album may be “Joseph Fable featuring Thomas Smithson”. Value is an NSString object.
   */
  case artist(String?)

  /**
   The title (or name) of the media item.

   This property is unrelated to the MPMediaItemPropertyAlbumTitle property. Value is an NSString object.
   */
  case title(String?)

  /**
   The title of an album.

   This property contains the album title, such as “Live On Mars”, as opposed to the title of an individual song on the album, such as “Crater Dance (radio edit)” (which you specify using the MPMediaItemPropertyTitle property). Value is an NSString object.
   */
  case albumTitle(String?)

  /**
   The playback duration of the media item.
   Value is an NSNumber object representing a duration in seconds as an TimeInterval.
   */
  case duration(TimeInterval?)

  /**
   The artwork image for the media item.
   */
  case artwork(MPMediaItemArtwork?)

  var key: String {
    switch self {
    case .artist:
      return MPMediaItemPropertyArtist

    case .title:
      return MPMediaItemPropertyTitle

    case .albumTitle:
      return MPMediaItemPropertyAlbumTitle

    case .duration:
      return MPMediaItemPropertyPlaybackDuration

    case .artwork:
      return MPMediaItemPropertyArtwork
    }
  }

  var value: Any? {
    switch self {
    case let .artist(artist):
      return artist

    case let .title(title):
      return title

    case let .albumTitle(title):
      return title

    case let .duration(duration):
      return duration != nil ? NSNumber(floatLiteral: duration!) : nil

    case let .artwork(artwork):
      return artwork
    }
  }
}
