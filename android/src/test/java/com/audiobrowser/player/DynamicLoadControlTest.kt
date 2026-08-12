package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicLoadControlTest {

  private fun params(rebuffering: Boolean, bufferedUs: Long = 0L) =
    LoadControl.Parameters(
      PlayerId.UNSET,
      Timeline.EMPTY,
      MediaSource.MediaPeriodId(Any()),
      /* playbackPositionUs= */ 0L,
      /* bufferedDurationUs= */ bufferedUs,
      /* playbackSpeed= */ 1.0f,
      /* playWhenReady= */ true,
      /* rebuffering= */ rebuffering,
      /* targetLiveOffsetUs= */ C.TIME_UNSET,
      /* lastRebufferRealtimeMs= */ 0L,
    )

  @Test
  fun `isRebuffering starts false`() {
    assertFalse(DynamicLoadControl().isRebuffering)
  }

  @Test
  fun `mirrors ExoPlayer's rebuffering flag during buffering decisions`() {
    val loadControl = DynamicLoadControl()
    loadControl.shouldStartPlayback(params(rebuffering = true))
    assertTrue(loadControl.isRebuffering)
  }

  @Test
  fun `clears once the rebuffer recovers`() {
    val loadControl = DynamicLoadControl()
    loadControl.shouldStartPlayback(params(rebuffering = true))
    // A later poll while playing normally reports rebuffering = false.
    loadControl.shouldContinueLoading(params(rebuffering = false))
    assertFalse(loadControl.isRebuffering)
  }

  @Test
  fun `onPrepared resets rebuffering so a new track's initial connect isn't a rebuffer`() {
    val loadControl = DynamicLoadControl()
    loadControl.shouldContinueLoading(params(rebuffering = true))
    loadControl.onPrepared()
    assertFalse(loadControl.isRebuffering)
  }
}
