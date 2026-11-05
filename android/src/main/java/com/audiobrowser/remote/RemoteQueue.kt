package com.audiobrowser.remote

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.util.Util

/**
 * The queue [RemoteSwitchingPlayer] keeps while remote, played in list order. Each item has a uid
 * of its own that follows it through moves, so equal media items stay distinct playlist entries.
 */
internal class RemoteQueue {
  private val itemList = mutableListOf<MediaItem>()
  private val uidList = mutableListOf<Any>()

  val items: List<MediaItem>
    get() = itemList

  val uids: List<Any>
    get() = uidList

  var index = C.INDEX_UNSET
    private set

  val current: MediaItem?
    get() = itemList.getOrNull(index)

  val isEmpty: Boolean
    get() = itemList.isEmpty()

  fun set(newItems: List<MediaItem>, newIndex: Int) {
    itemList.clear()
    itemList.addAll(newItems)
    uidList.clear()
    uidList.addAll(newItems.map { Any() })
    index = if (newItems.isEmpty()) C.INDEX_UNSET else newIndex.coerceIn(0, newItems.size - 1)
  }

  /** Inserts [newItems] at [at], clamped; in an empty queue, the first of them becomes current. */
  fun add(at: Int, newItems: List<MediaItem>) {
    val to = at.coerceIn(0, itemList.size)
    itemList.addAll(to, newItems)
    uidList.addAll(to, newItems.map { Any() })
    when {
      index == C.INDEX_UNSET -> index = 0
      index >= to -> index += newItems.size
    }
  }

  /**
   * Removes the items from [fromIndex] to [toIndex]; true when the current one went with them, and
   * then the item after them, or else the last, is current.
   */
  fun remove(fromIndex: Int, toIndex: Int): Boolean {
    val to = toIndex.coerceAtMost(itemList.size)
    val removesCurrent = index in fromIndex until to
    itemList.subList(fromIndex, to).clear()
    uidList.subList(fromIndex, to).clear()
    index =
      when {
        itemList.isEmpty() -> C.INDEX_UNSET
        removesCurrent -> fromIndex.coerceAtMost(itemList.size - 1)
        index >= to -> index - (to - fromIndex)
        else -> index
      }
    return removesCurrent
  }

  /**
   * Puts [newItems] in place of the items from [fromIndex] to [toIndex]. True when the current item
   * is now another media: for a same-length replacement, by media id or uri, so a restamp of its
   * metadata alone is not; otherwise whenever the range held the current item.
   */
  fun replace(fromIndex: Int, toIndex: Int, newItems: List<MediaItem>): Boolean {
    val to = toIndex.coerceAtMost(itemList.size)
    if (to - fromIndex == newItems.size) {
      val before = current
      newItems.forEachIndexed { offset, item -> itemList[fromIndex + offset] = item }
      val after = current
      return before != null &&
        after != null &&
        (before.mediaId != after.mediaId ||
          before.localConfiguration?.uri != after.localConfiguration?.uri)
    }
    val replacesCurrent = remove(fromIndex, to)
    add(fromIndex, newItems)
    if (replacesCurrent) index = fromIndex.coerceAtMost(itemList.size - 1)
    return replacesCurrent
  }

  fun move(fromIndex: Int, toIndex: Int, newIndex: Int) {
    val current = uidList.getOrNull(index)
    val to = toIndex.coerceAtMost(itemList.size)
    Util.moveItems(itemList, fromIndex, to, newIndex)
    Util.moveItems(uidList, fromIndex, to, newIndex)
    index = uidList.indexOf(current)
  }

  /** Throws when no item is at [newIndex]. */
  fun moveTo(newIndex: Int) {
    require(newIndex in itemList.indices) { "No item at $newIndex" }
    index = newIndex
  }

  /**
   * The item that plays when the current one ends under [repeatMode], or [C.INDEX_UNSET] when
   * playback stops.
   */
  fun nextAtEnd(repeatMode: Int): Int =
    when {
      isEmpty -> C.INDEX_UNSET
      repeatMode == MediaPlayer.REPEAT_MODE_ONE -> index
      index + 1 < itemList.size -> index + 1
      repeatMode == MediaPlayer.REPEAT_MODE_ALL -> 0
      else -> C.INDEX_UNSET
    }
}
