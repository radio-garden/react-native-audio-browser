package com.audiobrowser.extension

import com.margelo.nitro.audiobrowser.ImageRowItem
import com.margelo.nitro.audiobrowser.Track

/**
 * A track's identity: the opaque `id` when present (non-blank), else the playable `src`. Two tracks
 * refer to the same item iff their identities are equal. Browsable-only tracks (neither `id` nor
 * `src`) have no identity — they are addressed by `path` instead.
 *
 * This is THE comparison rule for favorites matching, section scoping, skip-in-place, the car
 * now-playing row indicator, and the contextual `__trackId` — see ADR 0008. Mirrors the TS
 * `trackIdentity` helper.
 */
val Track.identity: String?
  get() = id?.takeUnless { it.isBlank() } ?: src

/** [Track.identity] for image-row items, which carry the same `id`/`src` pair. */
val ImageRowItem.identity: String?
  get() = id?.takeUnless { it.isBlank() } ?: src
