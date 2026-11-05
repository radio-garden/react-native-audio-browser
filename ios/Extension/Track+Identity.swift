#if canImport(NitroModules)
  import NitroModules
#endif

extension Track {
  /// `id` when non-empty, else `src`; nil for a browsable-only track, which is addressed by `path`.
  /// Two tracks are the same item iff their identities are equal. Must match the TS and Android
  /// rule: docs/adr/0008-track-identity-is-id-falling-back-to-src.md.
  var identity: String? {
    if let id, !id.isEmpty { return id }
    return src
  }
}

extension Track {
  /// Has a `src` and isn't disabled: a track the library may queue and play.
  var isPlayable: Bool {
    src != nil && disabled != true
  }
}
