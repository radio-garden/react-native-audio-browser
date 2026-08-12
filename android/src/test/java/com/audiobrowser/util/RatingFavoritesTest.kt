package com.audiobrowser.util

import androidx.media3.common.HeartRating
import androidx.media3.common.StarRating
import androidx.media3.common.ThumbRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RatingFavoritesTest {
  @Test
  fun `rated heart up maps to favorited true`() {
    assertEquals(true, RatingFavorites.favoritedFor(HeartRating(true)))
  }

  @Test
  fun `rated heart down maps to favorited false`() {
    assertEquals(false, RatingFavorites.favoritedFor(HeartRating(false)))
  }

  @Test
  fun `unrated heart carries no favorite intent`() {
    assertNull(RatingFavorites.favoritedFor(HeartRating()))
  }

  @Test
  fun `non-heart ratings carry no favorite intent`() {
    assertNull(RatingFavorites.favoritedFor(ThumbRating(true)))
    assertNull(RatingFavorites.favoritedFor(StarRating(5, 4f)))
  }
}
