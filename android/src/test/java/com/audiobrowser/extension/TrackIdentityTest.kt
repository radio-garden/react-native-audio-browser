package com.audiobrowser.extension

import com.audiobrowser.TestFixtures.imageRowItem
import com.audiobrowser.TestFixtures.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * THE identity rule (ADR 0008): a track's identity is its `id` when non-blank, else its `src`.
 * Everything that compares tracks — favorites, section scoping, skip-in-place, `__trackId` — goes
 * through this extension.
 */
class TrackIdentityTest {

  @Test
  fun `id wins over src`() {
    assertEquals("stable-id", track(id = "stable-id", src = "https://s/a.mp3").identity)
  }

  @Test
  fun `blank id falls back to src`() {
    assertEquals("https://s/a.mp3", track(id = "", src = "https://s/a.mp3").identity)
    assertEquals("https://s/a.mp3", track(id = "  ", src = "https://s/a.mp3").identity)
  }

  @Test
  fun `missing id falls back to src`() {
    assertEquals("https://s/a.mp3", track(id = null, src = "https://s/a.mp3").identity)
  }

  @Test
  fun `id alone is an identity`() {
    assertEquals("stable-id", track(id = "stable-id", src = null).identity)
  }

  @Test
  fun `neither id nor src means no identity`() {
    assertNull(track(id = null, src = null).identity)
    assertNull(track(id = "", src = null).identity)
  }

  @Test
  fun `image row items follow the same rule`() {
    assertEquals("row-id", imageRowItem(src = "https://s/r.mp3", id = "row-id").identity)
    assertEquals("https://s/r.mp3", imageRowItem(src = "https://s/r.mp3").identity)
  }
}
