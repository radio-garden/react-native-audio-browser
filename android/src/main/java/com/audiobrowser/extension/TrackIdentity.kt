package com.audiobrowser.extension

import com.margelo.nitro.audiobrowser.Track

/**
 * Must match the TS `getTrackIdentity`, which both sides compare tracks by, except that a
 * whitespace-only `id` counts as unset here and not there (ADR 0008).
 */
val Track.identity: String?
  get() = id?.takeUnless { it.isBlank() } ?: src
