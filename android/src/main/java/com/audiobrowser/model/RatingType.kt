package com.audiobrowser.model

import android.support.v4.media.RatingCompat

enum class RatingType(val string: String, val compat: Int) {
  HEART("heart", RatingCompat.RATING_HEART),
  THUMBS_UP_DOWN("thumbs-up-down", RatingCompat.RATING_THUMB_UP_DOWN),
  THREE_STARS("3-stars", RatingCompat.RATING_3_STARS),
  FOUR_STARS("4-stars", RatingCompat.RATING_4_STARS),
  FIVE_STARS("5-stars", RatingCompat.RATING_5_STARS),
  PERCENTAGE("percentage", RatingCompat.RATING_PERCENTAGE);

  companion object {
    fun fromString(value: String): RatingType? {
      return entries.find { it.string == value }
    }

    fun fromCompat(value: Int): RatingType? {
      return entries.find { it.compat == value }
    }
  }
}
