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
  /// `path` and `title` use single-optional since they are non-optional on ResolvedTrack.
  func copying(
    path: String? = nil,
    style: SectionStyle?? = nil,
    sections: [Section]?? = nil,
    children: [Track]?? = nil,
    carPlaySiriListButton: CarPlaySiriListButtonPosition?? = nil,
    id: String?? = nil,
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
    disabled: Bool?? = nil,
    favorited: Bool?? = nil,
    live: Bool?? = nil,
  ) -> ResolvedTrack {
    ResolvedTrack(
      path: path ?? self.path,
      style: style ?? self.style,
      sections: sections ?? self.sections,
      children: children ?? self.children,
      carPlaySiriListButton: carPlaySiriListButton ?? self.carPlaySiriListButton,
      id: id ?? self.id,
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
      disabled: disabled ?? self.disabled,
      favorited: favorited ?? self.favorited,
      live: live ?? self.live,
    )
  }
}
