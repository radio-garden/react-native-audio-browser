package com.audiobrowser.extension

import com.audiobrowser.TestFixtures.track
import com.audiobrowser.util.BrowserPathHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for `android.net.Uri`, which parses contextual paths.
@RunWith(RobolectricTestRunner::class)
class TappedTrackTest {

  private fun stamped(id: String, index: Int) = BrowserPathHelper.build("/page", id, index)

  private val queue =
    arrayOf(
      track(src = "a").copy(path = stamped("a", 0)),
      track(src = "b").copy(path = stamped("b", 1)),
      track(src = "a").copy(path = stamped("a", 2)),
    )

  @Test
  fun `exact path match pins the tapped copy of a duplicate identity`() {
    assertEquals(2, queue.indexOfTappedTrack(stamped("a", 2), "a"))
  }

  @Test
  fun `index-less path falls back to the identity match`() {
    assertEquals(0, queue.indexOfTappedTrack(BrowserPathHelper.build("/page", "a"), "a"))
  }

  @Test
  fun `index-stamped path with no exact match misses instead of identity-skipping`() {
    assertEquals(-1, queue.indexOfTappedTrack(stamped("a", 7), "a"))
  }
}
