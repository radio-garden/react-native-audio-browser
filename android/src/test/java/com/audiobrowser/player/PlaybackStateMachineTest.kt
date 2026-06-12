package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer
import com.margelo.nitro.audiobrowser.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for the playback state transitions — the Android analog of iOS's
 * `nextPlaybackState(from:on:)`, previously inline branching in PlayerListener.onEvents. Guards
 * here are state-related; context guards arrive as event fields captured at the call site.
 */
class PlaybackStateMachineTest {

  private fun on(
    event: PlaybackEvent,
    from: PlaybackState = PlaybackState.PLAYING,
  ): List<PlaybackState> = PlaybackStateMachine.transitions(from, event)

  // MARK: ExoPlayer state changes

  @Test
  fun `buffering and ready map directly`() {
    assertEquals(
      listOf(PlaybackState.BUFFERING),
      on(PlaybackEvent.ExoPlaybackStateChanged(MediaPlayer.STATE_BUFFERING, mediaItemCount = 1)),
    )
    assertEquals(
      listOf(PlaybackState.READY),
      on(PlaybackEvent.ExoPlaybackStateChanged(MediaPlayer.STATE_READY, mediaItemCount = 1)),
    )
  }

  @Test
  fun `idle maps to none except from error or stopped`() {
    val idle = PlaybackEvent.ExoPlaybackStateChanged(MediaPlayer.STATE_IDLE, mediaItemCount = 1)
    assertEquals(listOf(PlaybackState.NONE), on(idle, from = PlaybackState.PLAYING))
    // A terminal error idles ExoPlayer; transitioning to NONE would clear the ERROR state the
    // session is rendering. Same for an explicit stop.
    assertEquals(emptyList<PlaybackState>(), on(idle, from = PlaybackState.ERROR))
    assertEquals(emptyList<PlaybackState>(), on(idle, from = PlaybackState.STOPPED))
  }

  @Test
  fun `ended maps to ended only while the queue has items`() {
    assertEquals(
      listOf(PlaybackState.ENDED),
      on(PlaybackEvent.ExoPlaybackStateChanged(MediaPlayer.STATE_ENDED, mediaItemCount = 3)),
    )
    // An emptied queue also reports STATE_ENDED; that is "nothing loaded", not "played to end".
    assertEquals(
      listOf(PlaybackState.NONE),
      on(PlaybackEvent.ExoPlaybackStateChanged(MediaPlayer.STATE_ENDED, mediaItemCount = 0)),
    )
  }

  @Test
  fun `recovery out of error is allowed`() {
    // The asymmetry that makes the session-error mask work: IDLE is suppressed from ERROR (the
    // error keeps rendering), but a real recovery (re-prepare reaching BUFFERING/READY) must
    // transition out of ERROR.
    assertEquals(
      listOf(PlaybackState.BUFFERING),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(MediaPlayer.STATE_BUFFERING, mediaItemCount = 1),
        from = PlaybackState.ERROR,
      ),
    )
    assertEquals(
      listOf(PlaybackState.READY),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(MediaPlayer.STATE_READY, mediaItemCount = 1),
        from = PlaybackState.ERROR,
      ),
    )
  }

  @Test
  fun `an unmapped exo state is a no-op`() {
    assertEquals(
      emptyList<PlaybackState>(),
      on(PlaybackEvent.ExoPlaybackStateChanged(exoState = 99, mediaItemCount = 1)),
    )
  }

  // MARK: media item transitions

  @Test
  fun `a transition to a track synthesizes the loading burst`() {
    assertEquals(
      listOf(PlaybackState.LOADING),
      on(PlaybackEvent.MediaItemTransition(hasTrack = true, isPlaying = false)),
    )
    // Already audibly playing (gapless auto-advance): run the full burst so consumers see the
    // canonical sequence rather than staying stuck on LOADING.
    assertEquals(
      listOf(PlaybackState.LOADING, PlaybackState.READY, PlaybackState.PLAYING),
      on(PlaybackEvent.MediaItemTransition(hasTrack = true, isPlaying = true)),
    )
  }

  @Test
  fun `a transition without a track is suppressed`() {
    assertEquals(
      emptyList<PlaybackState>(),
      on(PlaybackEvent.MediaItemTransition(hasTrack = false, isPlaying = true)),
    )
  }

  // MARK: playWhenReady / isPlaying

  @Test
  fun `losing playWhenReady pauses except from stopped`() {
    val paused = PlaybackEvent.PlayWhenReadyChanged(playWhenReady = false)
    assertEquals(listOf(PlaybackState.PAUSED), on(paused, from = PlaybackState.PLAYING))
    assertEquals(emptyList<PlaybackState>(), on(paused, from = PlaybackState.STOPPED))
    assertEquals(
      emptyList<PlaybackState>(),
      on(PlaybackEvent.PlayWhenReadyChanged(playWhenReady = true)),
    )
  }

  @Test
  fun `audible playback maps to playing`() {
    assertEquals(
      listOf(PlaybackState.PLAYING),
      on(PlaybackEvent.IsPlayingChanged(isPlaying = true), from = PlaybackState.READY),
    )
    assertEquals(emptyList<PlaybackState>(), on(PlaybackEvent.IsPlayingChanged(isPlaying = false)))
  }
}
