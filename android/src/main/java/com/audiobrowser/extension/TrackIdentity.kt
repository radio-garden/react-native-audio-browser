package com.audiobrowser.extension

import com.margelo.nitro.audiobrowser.Track

/**
 * The key every comparison of tracks uses: `id` when non-blank, else `src`. Null for a
 * browsable-only track (neither), which is addressed by `path` instead. Must match the TS
 * `getTrackIdentity` helper — see ADR 0008.
 */
val Track.identity: String?
  get() = id?.takeUnless { it.isBlank() } ?: src
