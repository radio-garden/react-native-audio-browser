package com.audiobrowser.util

import androidx.media3.common.HeartRating
import androidx.media3.common.Rating

object RatingFavorites {
  /**
   * The favorite state a controller's [rating] asks for, or null for no change. Only a rated
   * [HeartRating] counts: a thumb, star or percentage rating, or an unrated heart, returns null.
   */
  fun favoritedFor(rating: Rating): Boolean? =
    (rating as? HeartRating)?.takeIf { it.isRated }?.isHeart
}
