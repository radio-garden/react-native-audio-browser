#if canImport(NitroModules)
  import NitroModules
#endif

extension Track {
  /// `id` when non-empty, else `src`; nil for a browsable-only track, which is addressed by `path`.
  /// Two tracks are the same item iff their identities are equal. Must match TS `getTrackIdentity`;
  /// Android's `Track.identity` differs on a whitespace-only `id`, which it treats as unset
  /// (docs/adr/0008-track-identity-is-id-falling-back-to-src.md).
  var identity: String? {
    if let id, !id.isEmpty { return id }
    return src
  }
}

extension Track {
  var isPlayable: Bool {
    src != nil && disabled != true
  }
}
