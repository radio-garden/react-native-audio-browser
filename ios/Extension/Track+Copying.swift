import NitroModules

extension Track {
  /// Returns a copy of this Track with only the specified fields changed.
  ///
  /// Uses double-optional (`T??`) so callers can distinguish between:
  /// - Omitted argument → keep existing value
  /// - `.some(nil)` → set field to nil
  /// - `.some(value)` → set field to value
  ///
  /// `title` uses single-optional (`String?`) since it is non-optional on Track.
  func copying(
    id: String?? = nil,
    url: String?? = nil,
    src: String?? = nil,
    artwork: String?? = nil,
    artworkSource: ImageSource?? = nil,
    request: TrackRequest?? = nil,
    artworkCarPlayTinted: Bool?? = nil,
    title: String? = nil,
    subtitle: String?? = nil,
    artist: String?? = nil,
    albumUrl: String?? = nil,
    album: String?? = nil,
    description: String?? = nil,
    genre: String?? = nil,
    duration: Double?? = nil,
    style: TrackStyle?? = nil,
    childrenStyle: TrackStyle?? = nil,
    favorited: Bool?? = nil,
    groupTitle: String?? = nil,
    live: Bool?? = nil,
    imageRow: [ImageRowItem]?? = nil,
  ) -> Track {
    Track(
      id: id ?? self.id,
      url: url ?? self.url,
      src: src ?? self.src,
      artwork: artwork ?? self.artwork,
      artworkSource: artworkSource ?? self.artworkSource,
      request: request ?? self.request,
      artworkCarPlayTinted: artworkCarPlayTinted ?? self.artworkCarPlayTinted,
      title: title ?? self.title,
      subtitle: subtitle ?? self.subtitle,
      artist: artist ?? self.artist,
      albumUrl: albumUrl ?? self.albumUrl,
      album: album ?? self.album,
      description: description ?? self.description,
      genre: genre ?? self.genre,
      duration: duration ?? self.duration,
      style: style ?? self.style,
      childrenStyle: childrenStyle ?? self.childrenStyle,
      favorited: favorited ?? self.favorited,
      groupTitle: groupTitle ?? self.groupTitle,
      live: live ?? self.live,
      imageRow: imageRow ?? self.imageRow,
    )
  }
}
