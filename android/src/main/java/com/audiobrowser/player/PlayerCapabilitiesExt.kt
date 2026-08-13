package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.PlayerCapabilities

/** Whether the favorite/like control is enabled. Opt-in: only an explicit `true` enables it. */
val PlayerCapabilities.favoriteEnabled: Boolean
  get() = favorite == true
