package com.audiobrowser.player

import com.audiobrowser.TestFixtures
import com.audiobrowser.TestFixtures.trackStyle
import com.margelo.nitro.audiobrowser.ArtworkRendering
import com.margelo.nitro.audiobrowser.StyleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackStateStoreTrackJsonTest {

  @Test
  fun `style and disabled survive the round-trip`() {
    val track =
      TestFixtures.track(title = "Night Mix", src = "https://s/a.mp3", disabled = true)
        .copy(
          style =
            trackStyle(display = StyleDisplay.GRID, artworkRendering = ArtworkRendering.STENCIL)
        )

    val restored = PlaybackStateStore.trackFromJson(PlaybackStateStore.trackToJson(track))

    assertEquals(StyleDisplay.GRID, restored?.style?.display)
    assertEquals(ArtworkRendering.STENCIL, restored?.style?.artworkRendering)
    assertEquals(true, restored?.disabled)
  }

  @Test
  fun `a track without style round-trips without one`() {
    val track = TestFixtures.track(title = "Plain", src = "https://s/a.mp3")

    val restored = PlaybackStateStore.trackFromJson(PlaybackStateStore.trackToJson(track))

    assertNull(restored?.style)
    assertNull(restored?.disabled)
  }

  @Test
  fun `a pre-block snapshot with a string style restores without a declaration`() {
    // Snapshots written before the block model (ADR 0011) store `style` as an enum-name string.
    val restored = PlaybackStateStore.trackFromJson("""{"title":"X","src":"s","style":"LIST"}""")

    assertEquals("X", restored?.title)
    assertNull(restored?.style)
  }
}
