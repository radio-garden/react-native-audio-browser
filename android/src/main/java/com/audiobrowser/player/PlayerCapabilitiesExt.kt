package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.FavoritesMatchMode
import com.margelo.nitro.audiobrowser.PlayerCapabilities

/**
 * Resolved favorite match mode, or null when favoriting is disabled.
 *
 * `false`/unset → null; `true` → EXACT; `{ match }` → that match.
 */
val PlayerCapabilities.favoriteMatch: FavoritesMatchMode?
  get() =
    favorite?.match(
      first = { enabled -> if (enabled) FavoritesMatchMode.EXACT else null },
      second = { config -> config.match },
    )

/** Whether the favorite/like control is enabled. */
val PlayerCapabilities.favoriteEnabled: Boolean
  get() = favoriteMatch != null
