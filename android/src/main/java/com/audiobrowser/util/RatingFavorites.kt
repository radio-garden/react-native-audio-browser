package com.audiobrowser.util

import androidx.media3.common.HeartRating
import androidx.media3.common.Rating

/**
 * Maps a Media3 [Rating] arriving from a controller (e.g. Google Assistant "I like this") to a
 * favorite intent. Only an explicitly-rated heart carries favorite intent: a thumbs / star /
 * percentage rating, or an unrated (cleared) heart, returns null — no favorite change.
 */
object RatingFavorites {
  fun favoritedFor(rating: Rating): Boolean? =
    (rating as? HeartRating)?.takeIf { it.isRated }?.isHeart
}
