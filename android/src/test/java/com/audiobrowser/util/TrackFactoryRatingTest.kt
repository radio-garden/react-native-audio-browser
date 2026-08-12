package com.audiobrowser.util

import androidx.media3.common.HeartRating
import com.audiobrowser.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackFactoryRatingTest {
  @Test
  fun `favorited true advertises a rated, hearted userRating`() {
    val item = TrackFactory.toMedia3(TestFixtures.track(favorited = true))
    val rating = item.mediaMetadata.userRating as HeartRating
    assertTrue(rating.isRated)
    assertTrue(rating.isHeart)
  }

  @Test
  fun `favorited false advertises a rated, un-hearted userRating`() {
    val item = TrackFactory.toMedia3(TestFixtures.track(favorited = false))
    val rating = item.mediaMetadata.userRating as HeartRating
    assertTrue(rating.isRated)
    assertEquals(false, rating.isHeart)
  }

  @Test
  fun `favorited null advertises no userRating (favoriting disabled)`() {
    val item = TrackFactory.toMedia3(TestFixtures.track(favorited = null))
    assertNull(item.mediaMetadata.userRating)
  }
}
