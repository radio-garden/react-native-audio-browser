#if canImport(NitroModules)
  import NitroModules
#endif

/// A track's identity: the opaque `id` when present (non-blank), else the
/// playable `src`. Two tracks refer to the same item iff their identities are
/// equal. Browsable-only tracks (neither `id` nor `src`) have no identity —
/// they are addressed by `path` instead.
///
/// This is THE comparison rule for favorites matching, section scoping,
/// skip-in-place, the car now-playing row indicator, and the contextual
/// `__trackId` — see ADR 0008. Mirrors `trackIdentity` on the TS side.
extension Track {
  var identity: String? {
    if let id, !id.isEmpty { return id }
    return src
  }
}

extension ImageRowItem {
  var identity: String? {
    if let id, !id.isEmpty { return id }
    return src
  }
}
