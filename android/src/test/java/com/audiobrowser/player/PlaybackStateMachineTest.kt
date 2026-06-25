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
  fun `buffering maps directly`() {
    assertEquals(
      listOf(PlaybackState.BUFFERING),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_BUFFERING,
          mediaItemCount = 1,
          playWhenReady = true,
        )
      ),
    )
  }

  @Test
  fun `ready is emitted only while paused, suppressed while playing`() {
    // Paused (playWhenReady=false): READY is the settled signal consumers need to
    // clear a spinner from a buffer that finished while paused (mono#3325).
    assertEquals(
      listOf(PlaybackState.READY),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_READY,
          mediaItemCount = 1,
          playWhenReady = false,
        ),
        from = PlaybackState.BUFFERING,
      ),
    )
    // Playing (playWhenReady=true): READY is a transient before PLAYING — suppressed
    // so consumers don't flash a settled/non-loading state mid-startup.
    assertEquals(
      emptyList<PlaybackState>(),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_READY,
          mediaItemCount = 1,
          playWhenReady = true,
        ),
        from = PlaybackState.BUFFERING,
      ),
    )
  }

  @Test
  fun `idle maps to none except from error or stopped`() {
    val idle =
      PlaybackEvent.ExoPlaybackStateChanged(
        MediaPlayer.STATE_IDLE,
        mediaItemCount = 1,
        playWhenReady = false,
      )
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
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_ENDED,
          mediaItemCount = 3,
          playWhenReady = false,
        )
      ),
    )
    // An emptied queue also reports STATE_ENDED; that is "nothing loaded", not "played to end".
    assertEquals(
      listOf(PlaybackState.NONE),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_ENDED,
          mediaItemCount = 0,
          playWhenReady = false,
        )
      ),
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
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_BUFFERING,
          mediaItemCount = 1,
          playWhenReady = true,
        ),
        from = PlaybackState.ERROR,
      ),
    )
    // A paused recovery reaching READY (playWhenReady=false) also leaves ERROR.
    assertEquals(
      listOf(PlaybackState.READY),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_READY,
          mediaItemCount = 1,
          playWhenReady = false,
        ),
        from = PlaybackState.ERROR,
      ),
    )
  }

  @Test
  fun `an unmapped exo state is a no-op`() {
    assertEquals(
      emptyList<PlaybackState>(),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          exoState = 99,
          mediaItemCount = 1,
          playWhenReady = false,
        )
      ),
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
