package com.audiobrowser.remote

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.util.Util

/**
 * The queue [RemoteSwitchingPlayer] keeps while remote: its items, each with a uid of its own, and
 * the current one. It plays in list order.
 */
internal class RemoteQueue {
  val items = mutableListOf<MediaItem>()
  val uids = mutableListOf<Any>()
  var index = C.INDEX_UNSET

  val current: MediaItem?
    get() = items.getOrNull(index)

  val isEmpty: Boolean
    get() = items.isEmpty()

  fun set(newItems: List<MediaItem>, newIndex: Int) {
    items.clear()
    items.addAll(newItems)
    uids.clear()
    uids.addAll(newItems.map { Any() })
    index = if (newItems.isEmpty()) C.INDEX_UNSET else newIndex.coerceIn(0, newItems.size - 1)
  }

  fun add(at: Int, newItems: List<MediaItem>) {
    val to = at.coerceIn(0, items.size)
    items.addAll(to, newItems)
    uids.addAll(to, newItems.map { Any() })
    when {
      index == C.INDEX_UNSET -> index = 0
      index >= to -> index += newItems.size
    }
  }

  /** Removes the items from [fromIndex] to [toIndex]; true when the current one went with them. */
  fun remove(fromIndex: Int, toIndex: Int): Boolean {
    val to = toIndex.coerceAtMost(items.size)
    val removesCurrent = index in fromIndex until to
    items.subList(fromIndex, to).clear()
    uids.subList(fromIndex, to).clear()
    index =
      when {
        items.isEmpty() -> C.INDEX_UNSET
        removesCurrent -> fromIndex.coerceAtMost(items.size - 1)
        index >= to -> index - (to - fromIndex)
        else -> index
      }
    return removesCurrent
  }

  /**
   * Puts [newItems] in place of the items from [fromIndex] to [toIndex]. True when the current item
   * is now another media; a restamp of its metadata alone is not.
   */
  fun replace(fromIndex: Int, toIndex: Int, newItems: List<MediaItem>): Boolean {
    val to = toIndex.coerceAtMost(items.size)
    if (to - fromIndex == newItems.size) {
      val before = current
      newItems.forEachIndexed { offset, item -> items[fromIndex + offset] = item }
      val after = current
      return before != null &&
        after != null &&
        (before.mediaId != after.mediaId ||
          before.localConfiguration?.uri != after.localConfiguration?.uri)
    }
    val replacesCurrent = remove(fromIndex, to)
    add(fromIndex, newItems)
    if (replacesCurrent) index = fromIndex.coerceAtMost(items.size - 1)
    return replacesCurrent
  }

  fun move(fromIndex: Int, toIndex: Int, newIndex: Int) {
    val current = uids.getOrNull(index)
    val to = toIndex.coerceAtMost(items.size)
    Util.moveItems(items, fromIndex, to, newIndex)
    Util.moveItems(uids, fromIndex, to, newIndex)
    index = uids.indexOf(current)
  }

  /**
   * The item that follows the current one at its end, or [C.INDEX_UNSET] at the end of the queue.
   */
  fun nextAtEnd(repeatMode: Int): Int =
    when {
      isEmpty -> C.INDEX_UNSET
      repeatMode == MediaPlayer.REPEAT_MODE_ONE -> index
      index + 1 < items.size -> index + 1
      repeatMode == MediaPlayer.REPEAT_MODE_ALL -> 0
      else -> C.INDEX_UNSET
    }
}
