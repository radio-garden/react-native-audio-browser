package com.audiobrowser.remote

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteQueueTest {
  private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://s/$id").build()

  private fun queue(ids: String, index: Int) =
    RemoteQueue().apply { set(ids.map { item(it.toString()) }, index) }

  private val RemoteQueue.ids
    get() = items.joinToString("") { it.mediaId }

  @Test
  fun `adding keeps the current item current`() {
    data class Case(val at: Int, val ids: String, val index: Int)
    for ((at, ids, index) in
      listOf(Case(0, "xabc", 2), Case(1, "axbc", 2), Case(2, "abxc", 1), Case(3, "abcx", 1))) {
      val q = queue("abc", 1)
      q.add(at, listOf(item("x")))
      assertEquals("add at $at", ids, q.ids)
      assertEquals("add at $at", index, q.index)
    }
  }

  @Test
  fun `removing keeps the current item, or puts the next in its place`() {
    data class Case(
      val from: Int,
      val to: Int,
      val ids: String,
      val index: Int,
      val current: Boolean,
    )
    for ((from, to, ids, index, current) in
      listOf(
        Case(0, 1, "bcd", 0, false),
        Case(1, 2, "acd", 1, true),
        Case(1, 4, "a", 0, true),
        Case(2, 4, "ab", 1, false),
        Case(0, 4, "", C.INDEX_UNSET, true),
      )) {
      val q = queue("abcd", 1)
      assertEquals("remove $from..$to", current, q.remove(from, to))
      assertEquals("remove $from..$to", ids, q.ids)
      assertEquals("remove $from..$to", index, q.index)
    }
  }

  @Test
  fun `replacing reports whether the current item is another media`() {
    val restamp = queue("abc", 1)
    assertEquals(false, restamp.replace(1, 2, listOf(item("b"))))

    val other = queue("abc", 1)
    assertEquals(true, other.replace(1, 2, listOf(item("x"))))
    assertEquals("axc", other.ids)

    val longer = queue("abc", 1)
    assertEquals(true, longer.replace(1, 2, listOf(item("x"), item("y"))))
    assertEquals("axyc", longer.ids)
    assertEquals(1, longer.index)

    val elsewhere = queue("abc", 1)
    assertEquals(false, elsewhere.replace(2, 3, listOf(item("x"), item("y"))))
    assertEquals(1, elsewhere.index)
  }

  @Test
  fun `moving follows the current item`() {
    data class Case(val from: Int, val to: Int, val newIndex: Int, val ids: String, val index: Int)
    for ((from, to, newIndex, ids, index) in
      listOf(
        Case(1, 2, 3, "acdb", 3),
        Case(0, 1, 3, "bcda", 0),
        Case(3, 4, 0, "dabc", 2),
        Case(2, 4, 0, "cdab", 3),
      )) {
      val q = queue("abcd", 1)
      q.move(from, to, newIndex)
      assertEquals("move $from..$to to $newIndex", ids, q.ids)
      assertEquals("move $from..$to to $newIndex", index, q.index)
    }
  }

  @Test
  fun `the item at an end follows the repeat mode`() {
    assertEquals(2, queue("abc", 1).nextAtEnd(MediaPlayer.REPEAT_MODE_OFF))
    assertEquals(C.INDEX_UNSET, queue("abc", 2).nextAtEnd(MediaPlayer.REPEAT_MODE_OFF))
    assertEquals(0, queue("abc", 2).nextAtEnd(MediaPlayer.REPEAT_MODE_ALL))
    assertEquals(2, queue("abc", 2).nextAtEnd(MediaPlayer.REPEAT_MODE_ONE))
    assertEquals(C.INDEX_UNSET, RemoteQueue().nextAtEnd(MediaPlayer.REPEAT_MODE_ALL))
  }
}
