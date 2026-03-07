import NitroModules

extension Track {
  /// Returns a copy of this Track with only the specified fields changed.
  ///
  /// Uses double-optional (`T??`) so callers can distinguish between:
  /// - Omitted argument → keep existing value
  /// - `.some(nil)` → set field to nil
  /// - `.some(value)` → set field to value
  func copying(
    url: String?? = nil,
    artworkSource: ImageSource?? = nil,
    favorited: Bool?? = nil,
    imageRow: [ImageRowItem]?? = nil
  ) -> Track {
    Track(
      url: if let url { url } else { self.url },
      src: src,
      artwork: artwork,
      artworkSource: if let artworkSource { artworkSource } else { self.artworkSource },
      artworkCarPlayTinted: artworkCarPlayTinted,
      title: title,
      subtitle: subtitle,
      artist: artist,
      album: album,
      description: description,
      genre: genre,
      duration: duration,
      style: style,
      childrenStyle: childrenStyle,
      favorited: if let favorited { favorited } else { self.favorited },
      groupTitle: groupTitle,
      live: live,
      imageRow: if let imageRow { imageRow } else { self.imageRow }
    )
  }
}
