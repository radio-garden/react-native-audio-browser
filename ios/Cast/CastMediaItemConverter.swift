import Foundation

#if AUDIOBROWSER_ENABLE_CAST

  import GoogleCast

  /// Converts our `Track` to the Cast SDK's media types and back.
  ///
  /// Forward (`mediaInformation` / `queueItem`): builds a `GCKMediaInformation`
  /// carrying the **receiver-fetchable** media URL (already resolved with
  /// `target: .cast`), display metadata, an artwork URL, and — crucially — the
  /// app's **stable Track identity** stashed in `customData`. Stashing the whole
  /// Track lets a cold relaunch read the mirrored queue back off the receiver
  /// and rehydrate real `Track`s (ADR-0003: full-queue rehydration), and lets
  /// `CastReSign` re-resolve exactly one item's stale URL.
  ///
  /// Reverse (`track(from:)`): rebuilds a `Track` from `customData`. The stored
  /// `src` is the app's *original* (unsigned) `src` so re-resolution mints a
  /// fresh signed URL; never the expiring signed URL we loaded.
  enum CastMediaItemConverter {
    /// Keys used inside a `GCKMediaInformation.customData` JSON dictionary.
    ///
    /// iOS writes these **flat at the root** of `customData`. This is deliberately
    /// NOT wire-compatible with the Android converter, which nests the same
    /// identity under an `audiobrowserTrack` envelope and carries a different field
    /// subset. That's fine: rehydration is same-device cold-relaunch-while-casting
    /// (a receiver session is only ever re-read by the platform that wrote it), so
    /// the schema only needs to be self-consistent per platform — there is no
    /// cross-platform handoff of a Cast session.
    enum CustomDataKey {
      static let id = "id"
      static let url = "url"
      static let src = "src"
      static let title = "title"
      static let subtitle = "subtitle"
      static let artist = "artist"
      static let album = "album"
      static let albumUrl = "albumUrl"
      static let description = "description"
      static let genre = "genre"
      static let artwork = "artwork"
      static let duration = "duration"
      static let live = "live"
      static let groupTitle = "groupTitle"
    }

    /// Builds the `customData` dictionary holding the stable Track identity.
    /// Stores the app-declared (unsigned) `src` so re-resolution / rehydration
    /// re-signs from scratch.
    static func customData(for track: Track) -> [String: Any] {
      var data: [String: Any] = [:]
      data[CustomDataKey.id] = track.id
      data[CustomDataKey.url] = track.url
      data[CustomDataKey.src] = track.src
      data[CustomDataKey.title] = track.title
      data[CustomDataKey.subtitle] = track.subtitle
      data[CustomDataKey.artist] = track.artist
      data[CustomDataKey.album] = track.album
      data[CustomDataKey.albumUrl] = track.albumUrl
      data[CustomDataKey.description] = track.description
      data[CustomDataKey.genre] = track.genre
      data[CustomDataKey.artwork] = track.artwork
      data[CustomDataKey.duration] = track.duration
      data[CustomDataKey.live] = track.live
      data[CustomDataKey.groupTitle] = track.groupTitle
      // Drop nil values — JSON can't carry them and a missing key reads as nil.
      return data.compactMapValues { $0 }
    }

    /// Builds a `GCKMediaInformation` for a track.
    ///
    /// - Parameters:
    ///   - track: the source Track (for metadata + customData identity).
    ///   - mediaUrl: the **already `target:.cast`-resolved** playback URL.
    ///   - artworkUrl: the **already `target:.cast`-resolved** artwork URL (nil ok).
    static func mediaInformation(
      for track: Track,
      mediaUrl: String,
      artworkUrl: String?,
    ) -> GCKMediaInformation? {
      // An unparseable resolved URL means the item is unplayable on the receiver;
      // return nil so the caller SKIPS it rather than queueing a bogus URL that
      // would just fail and feed the re-sign loop.
      guard let contentURL = URL(string: mediaUrl) else { return nil }

      // Treat an unset `live` flag as live so the receiver shows a live affordance
      // (no scrubber/duration) rather than mistaking an unbounded stream for a
      // finite buffered file — the latter is a common source of false IDLE/error on
      // the receiver, which would spuriously trigger CastReSign. Only an explicit
      // `live == false` opts a track into BUFFERED.
      let isLive = track.live != false
      let streamType: GCKMediaStreamType = isLive ? .live : .buffered

      let metadata = GCKMediaMetadata(metadataType: .musicTrack)
      metadata.setString(track.title, forKey: kGCKMetadataKeyTitle)
      if let artist = track.artist { metadata.setString(artist, forKey: kGCKMetadataKeyArtist) }
      if let album = track.album { metadata.setString(album, forKey: kGCKMetadataKeyAlbumTitle) }
      if let subtitle = track.subtitle { metadata.setString(subtitle, forKey: kGCKMetadataKeySubtitle) }
      if let artworkUrl, let url = URL(string: artworkUrl) {
        metadata.addImage(GCKImage(url: url, width: 0, height: 0))
      }

      let builder = GCKMediaInformationBuilder(contentURL: contentURL)
      builder.streamType = streamType
      // An empty content type is not a "please sniff" sentinel — the Default Media
      // Receiver can reject an item with a blank/`audio/*` MIME. Infer a concrete
      // type from the URL (matches the Android converter), defaulting to audio/mpeg.
      builder.contentType = contentTypeFor(mediaUrl)
      builder.metadata = metadata
      builder.customData = customData(for: track)
      // Only a non-live track carries a finite duration hint; never advertise one
      // for a live stream (its length is unbounded).
      if !isLive, let duration = track.duration, duration > 0 {
        builder.streamDuration = duration
      }
      return builder.build()
    }

    /// Wraps a track's `GCKMediaInformation` into a queue item. Returns nil when
    /// the media URL is unparseable (the caller skips the item).
    static func queueItem(
      for track: Track,
      mediaUrl: String,
      artworkUrl: String?,
      autoplay: Bool = true,
    ) -> GCKMediaQueueItem? {
      guard let info = mediaInformation(for: track, mediaUrl: mediaUrl, artworkUrl: artworkUrl) else {
        return nil
      }
      let builder = GCKMediaQueueItemBuilder()
      builder.mediaInformation = info
      builder.autoplay = autoplay
      // 0 = use the receiver default preload window.
      builder.preloadTime = 0
      return builder.build()
    }

    /// Infers a concrete MIME content type for the Cast receiver from the resolved
    /// URL's extension. `audio/*` is not a real MIME and an empty string confuses
    /// some receivers, so map the common stream containers explicitly and default
    /// to `audio/mpeg`. Query string / fragment are stripped before matching. Kept
    /// in sync with the Android `CastMediaItemConverter.contentTypeFor`.
    static func contentTypeFor(_ url: String) -> String {
      let path = url.prefix { $0 != "?" && $0 != "#" }.lowercased()
      switch true {
      case path.hasSuffix(".m3u8"), path.hasSuffix(".m3u"): return "application/x-mpegURL"
      case path.hasSuffix(".mpd"): return "application/dash+xml"
      case path.hasSuffix(".aac"): return "audio/aac"
      case path.hasSuffix(".ogg"), path.hasSuffix(".oga"): return "audio/ogg"
      case path.hasSuffix(".flac"): return "audio/flac"
      case path.hasSuffix(".wav"): return "audio/wav"
      case path.hasSuffix(".mp4"), path.hasSuffix(".m4a"): return "audio/mp4"
      default: return "audio/mpeg"
      }
    }

    /// Rehydrates a `Track` from a receiver `GCKMediaInformation`'s `customData`.
    /// Returns nil when the customData isn't ours (no recognizable identity).
    static func track(from info: GCKMediaInformation?) -> Track? {
      guard let data = info?.customData as? [String: Any] else { return nil }
      // A mirrored item always carries at least one identity field.
      guard data[CustomDataKey.src] != nil || data[CustomDataKey.url] != nil || data[CustomDataKey.id] != nil
      else { return nil }

      func str(_ key: String) -> String? { data[key] as? String }
      func dbl(_ key: String) -> Double? {
        if let d = data[key] as? Double { return d }
        if let n = data[key] as? NSNumber { return n.doubleValue }
        return nil
      }
      func bool(_ key: String) -> Bool? {
        if let b = data[key] as? Bool { return b }
        if let n = data[key] as? NSNumber { return n.boolValue }
        return nil
      }

      return Track(
        id: str(CustomDataKey.id),
        url: str(CustomDataKey.url),
        src: str(CustomDataKey.src),
        artwork: str(CustomDataKey.artwork),
        artworkSource: nil,
        request: nil,
        artworkCarPlayTinted: nil,
        title: str(CustomDataKey.title) ?? "",
        subtitle: str(CustomDataKey.subtitle),
        artist: str(CustomDataKey.artist),
        albumUrl: str(CustomDataKey.albumUrl),
        album: str(CustomDataKey.album),
        description: str(CustomDataKey.description),
        genre: str(CustomDataKey.genre),
        duration: dbl(CustomDataKey.duration),
        style: nil,
        childrenStyle: nil,
        favorited: nil,
        groupTitle: str(CustomDataKey.groupTitle),
        live: bool(CustomDataKey.live),
        imageRow: nil,
      )
    }
  }

#endif
