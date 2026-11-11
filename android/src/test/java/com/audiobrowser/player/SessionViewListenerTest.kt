package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionViewListenerTest {

  private val view = mock(MediaPlayer::class.java)
  private val told = mutableListOf<String>()
  private val target =
    object : MediaPlayer.Listener {
      override fun onPlaybackStateChanged(playbackState: Int) {
        told += "state $playbackState"
      }

      override fun onIsPlayingChanged(isPlaying: Boolean) {
        told += "playing $isPlaying"
      }

      override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        told += "timeline"
      }

      override fun onPositionDiscontinuity(
        oldPosition: MediaPlayer.PositionInfo,
        newPosition: MediaPlayer.PositionInfo,
        reason: Int,
      ) {
        told += "position ${newPosition.positionMs}"
      }
    }

  private fun listener(): SessionViewListener {
    `when`(view.currentTimeline).thenReturn(Timeline.EMPTY)
    return SessionViewListener(target, view)
  }

  @Test
  fun `forwards every callback media3's listener declares`() {
    val declared =
      MediaPlayer.Listener::class.java.declaredMethods.map { it.name to it.parameterTypes.toList() }
    val overridden =
      SessionViewListener::class
        .java
        .declaredMethods
        .map { it.name to it.parameterTypes.toList() }
        .toSet()

    assertEquals(emptyList<Any>(), declared.filterNot { it in overridden })
  }

  @Test
  fun `tells the view's state once each time it moves`() {
    `when`(view.playbackState).thenReturn(MediaPlayer.STATE_BUFFERING)
    val listener = listener()

    listener.onPlaybackStateChanged(MediaPlayer.STATE_IDLE)
    `when`(view.playbackState).thenReturn(MediaPlayer.STATE_READY)
    listener.onPlaybackStateChanged(MediaPlayer.STATE_READY)
    listener.onPlaybackStateChanged(MediaPlayer.STATE_READY)

    assertEquals(listOf("state ${MediaPlayer.STATE_READY}"), told)
  }

  @Test
  fun `tells the view's playing once each time it moves`() {
    val listener = listener()

    listener.onIsPlayingChanged(true)
    `when`(view.isPlaying).thenReturn(true)
    listener.onIsPlayingChanged(true)
    listener.onIsPlayingChanged(true)

    assertEquals(listOf("playing true"), told)
  }

  @Test
  fun `tells a timeline that changes, and not its period turning placeholder and back`() {
    val listener = listener()
    val a = timeline("a", durationUs = 60_000_000)

    listener.onTimelineChanged(a, MediaPlayer.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
    listener.onTimelineChanged(
      PlaceholderPeriods(a),
      MediaPlayer.TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
    )
    listener.onTimelineChanged(
      timeline("a", durationUs = 60_000_000),
      MediaPlayer.TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
    )
    assertEquals(listOf("timeline"), told)

    listener.onTimelineChanged(
      PeriodAt(a, positionInWindowUs = -1_000_000),
      MediaPlayer.TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
    )
    listener.onTimelineChanged(
      timeline("a", durationUs = 30_000_000),
      MediaPlayer.TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
    )
    listener.onTimelineChanged(
      timeline("b", durationUs = 30_000_000),
      MediaPlayer.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
    )
    listener.onTimelineChanged(Timeline.EMPTY, MediaPlayer.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
    assertEquals(List(5) { "timeline" }, told)
  }

  @Test
  fun `tells a queue whose shuffle order changes`() {
    val listener = listener()

    listener.onTimelineChanged(
      TwoItems(shuffled = false),
      MediaPlayer.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
    )
    listener.onTimelineChanged(
      TwoItems(shuffled = true),
      MediaPlayer.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
    )

    assertEquals(listOf("timeline", "timeline"), told)
  }

  @Test
  fun `tells a position that moves, and not a seek to where it already is`() {
    val listener = listener()

    listener.onPositionDiscontinuity(
      position(120_000),
      position(0),
      MediaPlayer.DISCONTINUITY_REASON_SEEK,
    )
    listener.onPositionDiscontinuity(
      position(0),
      position(0),
      MediaPlayer.DISCONTINUITY_REASON_SEEK,
    )

    assertEquals(listOf("position 0"), told)
  }

  private fun position(positionMs: Long) =
    MediaPlayer.PositionInfo(UID, 0, UID, 0, positionMs, positionMs, C.INDEX_UNSET, C.INDEX_UNSET)

  private fun timeline(mediaId: String, durationUs: Long): Timeline =
    SinglePeriodTimeline(
      durationUs,
      /* isSeekable= */ true,
      /* isDynamic= */ false,
      /* useLiveConfiguration= */ false,
      /* manifest= */ null,
      MediaItem.Builder().setMediaId(mediaId).build(),
    )

  private companion object {
    val UID = Any()
  }

  /** Two items, in reverse when shuffled. */
  private class TwoItems(private val shuffled: Boolean) : Timeline() {
    override fun getWindowCount() = 2

    override fun getWindow(index: Int, window: Window, defaultPositionProjectionUs: Long) =
      window.set(
        index,
        MediaItem.Builder().setMediaId("$index").build(),
        null,
        C.TIME_UNSET,
        C.TIME_UNSET,
        C.TIME_UNSET,
        true,
        false,
        null,
        0,
        C.TIME_UNSET,
        index,
        index,
        0,
      )

    override fun getPeriodCount() = 2

    override fun getPeriod(index: Int, period: Period, setIds: Boolean): Period =
      period.set(index, index, index, C.TIME_UNSET, 0)

    override fun getIndexOfPeriod(uid: Any) = uid as? Int ?: C.INDEX_UNSET

    override fun getUidOfPeriod(index: Int): Any = index

    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean) =
      if (shuffleModeEnabled && shuffled) 1 else 0

    override fun getLastWindowIndex(shuffleModeEnabled: Boolean) =
      if (shuffleModeEnabled && shuffled) 0 else 1

    override fun getNextWindowIndex(index: Int, repeatMode: Int, shuffleModeEnabled: Boolean) =
      if (index == getLastWindowIndex(shuffleModeEnabled)) C.INDEX_UNSET
      else if (shuffleModeEnabled && shuffled) index - 1 else index + 1
  }

  private class PeriodAt(timeline: Timeline, private val positionInWindowUs: Long) :
    ForwardingTimeline(timeline) {
    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
      super.getPeriod(periodIndex, period, setIds).apply {
        positionInWindowUs = this@PeriodAt.positionInWindowUs
      }
  }

  private class PlaceholderPeriods(timeline: Timeline) : ForwardingTimeline(timeline) {
    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
      super.getPeriod(periodIndex, period, setIds).apply { isPlaceholder = true }
  }
}
