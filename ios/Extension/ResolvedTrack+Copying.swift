#if canImport(NitroModules)
  import NitroModules
#endif

extension ResolvedTrack {
  /// Returns a copy with the given fields replaced. An omitted argument keeps the field;
  /// `.some(nil)` clears it. `path` and `title` are required, so they can only be replaced.
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
    icyCharset: String?? = nil,
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
      icyCharset: icyCharset ?? self.icyCharset,
    )
  }

  /// A copy with each section track `transform` returns a track for replaced; nil when it
  /// replaces none.
  func replacingEachTrack(_ transform: (Track) -> Track?) -> ResolvedTrack? {
    guard let sections else { return nil }
    var replaced = false
    let updated = sections.map { section in
      section.copying(children: section.children.map { track in
        guard let replacement = transform(track) else { return track }
        replaced = true
        return replacement
      })
    }
    return replaced ? copying(sections: updated) : nil
  }
}
