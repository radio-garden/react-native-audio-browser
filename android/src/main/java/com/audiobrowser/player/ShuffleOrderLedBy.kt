package com.audiobrowser.player

import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * A shuffle order over [count] items with [startIndex] pinned to shuffle position 0.
 *
 * Media3 does not do this for us. `ExoPlayerImpl.setMediaSources` hands the start index to
 * `ShuffleOrder.cloneAndSet(insertionCount, startIndex)`, but that is an interface default of
 * `cloneAndClear().cloneAndInsert(0, insertionCount)` — the start index is discarded — and
 * `DefaultShuffleOrder` does not override it (verified against the pinned media3 build). So with
 * shuffle on, the track playback starts from lands at a random position in the order, and a 1-in-N
 * share of queues put it last: `nextMediaItemIndex` is then `INDEX_UNSET` from the very first
 * track, so auto-advance ends the queue after one track and fires `onQueueEnded`. The
 * voice-assistant cold resume expands a queue exactly this way, so it sits squarely on that path.
 *
 * Applied unconditionally by `Player.setQueue`, like the Swift twin's `adoptInitialCurrent`:
 * enabling shuffle later does not regenerate the order, so it has to be sane whether or not shuffle
 * is on right now.
 */
internal fun shuffleOrderLedBy(startIndex: Int, count: Int, random: Random = Random): ShuffleOrder {
  val first = startIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
  val indices = IntArray(count)
  if (count > 0) {
    indices[0] = first
    ((0 until count) - first).shuffled().forEachIndexed { i, index -> indices[i + 1] = index }
  }
  return ShuffleOrder.DefaultShuffleOrder(indices, random.nextLong())
}
