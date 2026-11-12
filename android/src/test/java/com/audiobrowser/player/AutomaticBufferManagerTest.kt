package com.audiobrowser.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomaticBufferManagerTest {

  private val fastStart =
    BufferConfig(bufferForPlaybackMs = 500, bufferForPlaybackAfterRebufferMs = 1000)
  private val loadControl = DynamicLoadControl(fastStart)
  private var nowMs = 3_600_000L
  private val engine = mock(ExoPlayer::class.java)
  private val manager =
    AutomaticBufferManager(loadControl, fastStart, clock = { nowMs }).also { it.attach(engine) }

  private val threshold: Int
    get() = loadControl.getBufferConfig().bufferForPlaybackAfterRebufferMs

  /** Ready with [bufferedMs] buffered, then [playedMs] of playback. */
  private fun play(bufferedMs: Long = 2_000, playedMs: Long = 20_000) {
    `when`(engine.totalBufferedDuration).thenReturn(bufferedMs)
    manager.onPlaybackStateChanged(MediaPlayer.STATE_READY)
    nowMs += playedMs
  }

  private fun state(state: Int) = manager.onPlaybackStateChanged(state)

  private fun eventTime(windowIndex: Int = 0) =
    AnalyticsListener.EventTime(
      nowMs,
      Timeline.EMPTY,
      windowIndex,
      null,
      0,
      Timeline.EMPTY,
      /* currentWindowIndex= */ 0,
      null,
      0,
      0,
    )

  private fun load(taskId: Long) =
    LoadEventInfo(taskId, DataSpec(Uri.parse("https://example.com/a")), nowMs)

  private val data = MediaLoadData(C.DATA_TYPE_MEDIA)

  private fun loadFailed(taskId: Long = 1, windowIndex: Int = 0, canceled: Boolean = false) =
    manager.onLoadError(eventTime(windowIndex), load(taskId), data, IOException(), canceled)

  private fun loadCompleted(taskId: Long = 1) =
    manager.onLoadCompleted(eventTime(), load(taskId), data)

  @Test
  fun `an underrun raises the threshold to what the drain rate says lasts a minute`() {
    state(MediaPlayer.STATE_BUFFERING)
    // 2 s drained over 20 s: 100 ms a second, 6 s for a minute.
    play(bufferedMs = 2_000, playedMs = 20_000)
    state(MediaPlayer.STATE_BUFFERING)
    assertEquals(6_000, threshold)
  }

  @Test
  fun `a reconnect's first buffering is not a rebuffer`() {
    play()
    state(MediaPlayer.STATE_IDLE)
    state(MediaPlayer.STATE_BUFFERING)
    play()
    assertEquals(1_000, threshold)
  }

  @Test
  fun `a clean close of a stream with an end is not a rebuffer`() {
    play()
    state(MediaPlayer.STATE_ENDED)
    state(MediaPlayer.STATE_BUFFERING)
    play()
    assertEquals(1_000, threshold)
  }

  @Test
  fun `a buffering while a failed load retries in place keeps the threshold`() {
    play()
    loadFailed()
    state(MediaPlayer.STATE_BUFFERING)
    assertEquals(1_000, threshold)
  }

  @Test
  fun `the drain is measured afresh once the failed load recovers`() {
    play()
    loadFailed()
    state(MediaPlayer.STATE_BUFFERING)
    // 4 s drained over 20 s: 12 s for a minute, at most 8 s.
    play(bufferedMs = 4_000)
    state(MediaPlayer.STATE_BUFFERING)
    assertEquals(8_000, threshold)
  }

  @Test
  fun `a failed load that completed leaves the next underrun counted`() {
    play()
    loadFailed()
    loadCompleted()
    state(MediaPlayer.STATE_BUFFERING)
    assertEquals(6_000, threshold)
  }

  @Test
  fun `a failed preload of the next item leaves the underrun counted`() {
    play()
    loadFailed(windowIndex = 1)
    state(MediaPlayer.STATE_BUFFERING)
    assertEquals(6_000, threshold)
  }

  @Test
  fun `a load that recovered without completing stops counting as failing`() {
    play()
    loadFailed()
    // A progressive load that recovers in place completes only at the end of its file.
    nowMs += 20_000
    state(MediaPlayer.STATE_BUFFERING)
    // 2 s drained over 40 s: 3 s for a minute.
    assertEquals(3_000, threshold)
  }

  @Test
  fun `a load that starts without failing does not count as failing`() {
    play()
    manager.onLoadStarted(eventTime(), load(2), data, 0)
    state(MediaPlayer.STATE_BUFFERING)
    assertEquals(6_000, threshold)
  }

  @Test
  fun `a load that keeps failing counts until its last error is old`() {
    play()
    loadFailed()
    nowMs += 15_000
    loadFailed()
    nowMs += 15_000
    state(MediaPlayer.STATE_BUFFERING)
    assertEquals(1_000, threshold)
  }

  @Test
  fun `a failed load media3 cancels still counts as failing`() {
    play()
    // An HLS variant media3 excludes: its load is canceled, and the next variant's starts.
    loadFailed(canceled = true)
    state(MediaPlayer.STATE_BUFFERING)
    assertEquals(1_000, threshold)
  }

  @Test
  fun `each retry of a failing load keeps it failing`() {
    play()
    loadFailed()
    nowMs += 15_000
    manager.onLoadStarted(eventTime(), load(1), data, 1)
    nowMs += 15_000
    state(MediaPlayer.STATE_BUFFERING)
    assertEquals(1_000, threshold)
  }

  @Test
  fun `playing again forgets the failed loads`() {
    play()
    loadFailed()
    state(MediaPlayer.STATE_BUFFERING)
    // 2 s drained over 5 s: 24 s for a minute, at most 8 s.
    play(playedMs = 5_000)
    state(MediaPlayer.STATE_BUFFERING)
    assertEquals(8_000, threshold)
  }

  @Test
  fun `a canceled load no longer counts as failing`() {
    play()
    loadFailed()
    manager.onLoadCanceled(eventTime(), load(1), data)
    state(MediaPlayer.STATE_BUFFERING)
    assertEquals(6_000, threshold)
  }

  @Test
  fun `reset restores the fast start`() {
    play()
    state(MediaPlayer.STATE_BUFFERING)
    manager.reset()
    assertEquals(fastStart, loadControl.getBufferConfig())
  }
}
