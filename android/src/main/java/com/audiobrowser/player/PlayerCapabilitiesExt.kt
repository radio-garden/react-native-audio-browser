package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.PlayerCapabilities

/** Opt-in, unlike the other capabilities: only an explicit `true` enables it. */
val PlayerCapabilities.favoriteEnabled: Boolean
  get() = favorite == true
