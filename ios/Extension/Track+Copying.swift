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
    path: String?? = nil,
    src: String?? = nil,
    artwork: Variant_String_ArtworkVariants?? = nil,
    artworkSource: ImageSource?? = nil,
    request: TrackRequest?? = nil,
    title: String? = nil,
    subtitle: String?? = nil,
    artist: String?? = nil,
    albumPath: String?? = nil,
    album: String?? = nil,
    description: String?? = nil,
    genre: String?? = nil,
    duration: Double?? = nil,
    style: TrackStyle?? = nil,
    disabled: Bool?? = nil,
    favorited: Bool?? = nil,
    live: Bool?? = nil,
  ) -> Track {
    Track(
      id: id ?? self.id,
      path: path ?? self.path,
      src: src ?? self.src,
      artwork: artwork ?? self.artwork,
      artworkSource: artworkSource ?? self.artworkSource,
      request: request ?? self.request,
      title: title ?? self.title,
      subtitle: subtitle ?? self.subtitle,
      artist: artist ?? self.artist,
      albumPath: albumPath ?? self.albumPath,
      album: album ?? self.album,
      description: description ?? self.description,
      genre: genre ?? self.genre,
      duration: duration ?? self.duration,
      style: style ?? self.style,
      disabled: disabled ?? self.disabled,
      favorited: favorited ?? self.favorited,
      live: live ?? self.live,
    )
  }
}
