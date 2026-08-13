package com.audiobrowser.util

import com.audiobrowser.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The mediaId is the item's identity on car surfaces: Android Auto marks the "now playing" browse
 * row by exact mediaId equality between rows and the player's current item, so a playable track
 * with a stable `id` must use it on both — a consumer-loaded track's `src` can differ textually
 * from the browse row's for the same item.
 */
@RunWith(RobolectricTestRunner::class)
class TrackFactoryMediaIdTest {

  @Test
  fun `playable track with id uses the stable id as mediaId`() {
    val track = TestFixtures.track(id = "abc123", src = "/listen/abc123/channel.mp3")
    assertEquals("abc123", TrackFactory.toMedia3(track).mediaId)
  }

  @Test
  fun `stable id wins over the contextual path for playable tracks`() {
    val track =
      TestFixtures.track(id = "abc123", src = "/listen/abc123/channel.mp3")
        .copy(path = "/home?__trackId=%2Flisten%2Fabc123%2Fchannel.mp3")
    assertEquals("abc123", TrackFactory.toMedia3(track).mediaId)
  }

  @Test
  fun `playable track without id falls back to path then src`() {
    val withPath = TestFixtures.track(src = "/a.mp3").copy(path = "/home?__trackId=%2Fa.mp3")
    assertEquals("/home?__trackId=%2Fa.mp3", TrackFactory.toMedia3(withPath).mediaId)

    val srcOnly = TestFixtures.track(src = "/a.mp3")
    assertEquals("/a.mp3", TrackFactory.toMedia3(srcOnly).mediaId)
  }

  @Test
  fun `browsable-only track keeps path as mediaId even with an id`() {
    // Navigation parentIds must stay resolvable paths, so a container's id is
    // never its mediaId.
    val track = TestFixtures.browseTrack(path = "/stations").copy(id = "abc123")
    assertEquals("/stations", TrackFactory.toMedia3(track).mediaId)
  }

  @Test
  fun `blank id is ignored`() {
    val track = TestFixtures.track(id = " ", src = "/a.mp3")
    assertEquals("/a.mp3", TrackFactory.toMedia3(track).mediaId)
  }

  @Test
  fun `playable uri rides in requestMetadata for replay after process death`() {
    val track = TestFixtures.track(id = "abc123", src = "https://s/a.mp3")
    assertEquals(
      "https://s/a.mp3",
      TrackFactory.toMedia3(track).requestMetadata.mediaUri.toString(),
    )
  }
}
