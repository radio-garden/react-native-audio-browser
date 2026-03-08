#if canImport(NitroModules)
import NitroModules
#endif

extension ResolvedTrack {
  /// Returns a copy of this ResolvedTrack with only the specified fields changed.
  ///
  /// Uses double-optional (`T??`) so callers can distinguish between:
  /// - Omitted argument → keep existing value
  /// - `.some(nil)` → set field to nil
  /// - `.some(value)` → set field to value
  ///
  /// `url` and `title` use single-optional since they are non-optional on ResolvedTrack.
  func copying(
    url: String? = nil,
    children: [Track]?? = nil,
    carPlaySiriListButton: CarPlaySiriListButtonPosition?? = nil,
    src: String?? = nil,
    artwork: String?? = nil,
    artworkSource: ImageSource?? = nil,
    artworkCarPlayTinted: Bool?? = nil,
    title: String? = nil,
    subtitle: String?? = nil,
    artist: String?? = nil,
    album: String?? = nil,
    description: String?? = nil,
    genre: String?? = nil,
    duration: Double?? = nil,
    style: TrackStyle?? = nil,
    childrenStyle: TrackStyle?? = nil,
    favorited: Bool?? = nil,
    groupTitle: String?? = nil,
    live: Bool?? = nil,
    imageRow: [ImageRowItem]?? = nil
  ) -> ResolvedTrack {
    ResolvedTrack(
      url: url ?? self.url,
      children: children ?? self.children,
      carPlaySiriListButton: carPlaySiriListButton ?? self.carPlaySiriListButton,
      src: src ?? self.src,
      artwork: artwork ?? self.artwork,
      artworkSource: artworkSource ?? self.artworkSource,
      artworkCarPlayTinted: artworkCarPlayTinted ?? self.artworkCarPlayTinted,
      title: title ?? self.title,
      subtitle: subtitle ?? self.subtitle,
      artist: artist ?? self.artist,
      album: album ?? self.album,
      description: description ?? self.description,
      genre: genre ?? self.genre,
      duration: duration ?? self.duration,
      style: style ?? self.style,
      childrenStyle: childrenStyle ?? self.childrenStyle,
      favorited: favorited ?? self.favorited,
      groupTitle: groupTitle ?? self.groupTitle,
      live: live ?? self.live,
      imageRow: imageRow ?? self.imageRow
    )
  }
}
