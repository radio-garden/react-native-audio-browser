package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.section
import com.audiobrowser.TestFixtures.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteChangeTest {

  private fun change(previous: Set<String>, favorites: Set<String>) =
    FavoriteChange.between(previous, favorites)!!

  @Test
  fun `a replace with the same identities is no change`() {
    assertNull(FavoriteChange.between(setOf("a", "b"), setOf("b", "a")))
  }

  @Test
  fun `a hydrated track follows a later replace, both ways`() {
    val added = change(emptySet(), setOf("a"))
    val favorited = added.restamp(track(src = "a", favorited = false))
    assertEquals(true, favorited?.favorited)

    val removed = change(setOf("a"), emptySet())
    assertEquals(false, removed.restamp(favorited!!)?.favorited)
  }

  @Test
  fun `restamp fills an unset flag`() {
    assertEquals(true, change(emptySet(), setOf("a")).restamp(track(src = "a"))?.favorited)
  }

  @Test
  fun `restamp leaves a track whose identity did not change`() {
    val change = change(setOf("a"), setOf("a", "c"))
    // Carried by the tracks themselves, against the set: only a change to their identity moves
    // them.
    assertNull(change.restamp(track(src = "a", favorited = false)))
    assertNull(change.restamp(track(src = "b", favorited = true)))
  }

  @Test
  fun `restampStamped leaves an unset flag to hydration`() {
    val change = change(emptySet(), setOf("a"))
    assertNull(change.restampStamped(track(src = "a")))
    assertEquals(true, change.restampStamped(track(src = "a", favorited = false))?.favorited)
  }

  @Test
  fun `restamp leaves a track that already agrees`() {
    assertNull(change(emptySet(), setOf("a")).restamp(track(src = "a", favorited = true)))
  }

  @Test
  fun `restamp matches by id before src`() {
    val change = change(emptySet(), setOf("https://s/a.mp3"))
    assertNull(change.restamp(track(id = "stable-a", src = "https://s/a.mp3", favorited = false)))
  }

  @Test
  fun `restamp leaves a track without identity alone`() {
    assertNull(change(emptySet(), setOf("/browse")).restamp(track(src = null)))
  }

  @Test
  fun `a toggle restamps its identity even when the set already agreed`() {
    val toggle = FavoriteChange.toggle("a", true)
    assertEquals(true, toggle.restamp(track(src = "a", favorited = false))?.favorited)
    assertNull(toggle.restamp(track(src = "b", favorited = false)))
  }

  @Test
  fun `restamps the changed tracks of a page`() {
    val page =
      resolvedTrack(
        path = "/p",
        sections =
          arrayOf(
            section(arrayOf(track(src = "a", favorited = false))),
            section(
              arrayOf(track(src = "b", favorited = true), track(src = "c", favorited = false))
            ),
          ),
      )

    val restamped = change(setOf("b"), setOf("a", "c")).restamp(page)

    assertEquals(
      listOf(listOf(true), listOf(false, true)),
      restamped?.sections?.map { section -> section.children.map { it.favorited } },
    )
    assertEquals("/p", restamped?.path)
  }

  @Test
  fun `a page with no changed track is not restamped`() {
    val page =
      resolvedTrack(
        path = "/p",
        sections = arrayOf(section(arrayOf(track(src = "b", favorited = true)))),
      )
    assertNull(change(setOf("b"), setOf("b", "elsewhere")).restamp(page))
  }
}
