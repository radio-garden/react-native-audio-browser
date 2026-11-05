package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer
import com.margelo.nitro.audiobrowser.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

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
          shouldPlayWhenReady = true,
        )
      ),
    )
  }

  @Test
  fun `ready is emitted only while paused, suppressed while playing`() {
    assertEquals(
      listOf(PlaybackState.READY),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_READY,
          mediaItemCount = 1,
          shouldPlayWhenReady = false,
        ),
        from = PlaybackState.BUFFERING,
      ),
    )
    assertEquals(
      emptyList<PlaybackState>(),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_READY,
          mediaItemCount = 1,
          shouldPlayWhenReady = true,
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
        shouldPlayWhenReady = false,
      )
    assertEquals(listOf(PlaybackState.NONE), on(idle, from = PlaybackState.PLAYING))
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
          shouldPlayWhenReady = false,
        )
      ),
    )
    assertEquals(
      listOf(PlaybackState.NONE),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_ENDED,
          mediaItemCount = 0,
          shouldPlayWhenReady = false,
        )
      ),
    )
  }

  @Test
  fun `recovery out of error is allowed`() {
    assertEquals(
      listOf(PlaybackState.BUFFERING),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_BUFFERING,
          mediaItemCount = 1,
          shouldPlayWhenReady = true,
        ),
        from = PlaybackState.ERROR,
      ),
    )
    assertEquals(
      listOf(PlaybackState.READY),
      on(
        PlaybackEvent.ExoPlaybackStateChanged(
          MediaPlayer.STATE_READY,
          mediaItemCount = 1,
          shouldPlayWhenReady = false,
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
          shouldPlayWhenReady = false,
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

  // MARK: shouldPlayWhenReady / isPlaying

  @Test
  fun `losing shouldPlayWhenReady pauses except from stopped or ended`() {
    val paused =
      PlaybackEvent.ShouldPlayWhenReadyChanged(
        shouldPlayWhenReady = false,
        exoState = MediaPlayer.STATE_READY,
      )
    assertEquals(listOf(PlaybackState.PAUSED), on(paused, from = PlaybackState.PLAYING))
    assertEquals(emptyList<PlaybackState>(), on(paused, from = PlaybackState.STOPPED))
    assertEquals(emptyList<PlaybackState>(), on(paused, from = PlaybackState.ENDED))
    assertEquals(
      emptyList<PlaybackState>(),
      on(
        PlaybackEvent.ShouldPlayWhenReadyChanged(
          shouldPlayWhenReady = true,
          exoState = MediaPlayer.STATE_READY,
        )
      ),
    )
  }

  @Test
  fun `resuming from paused while exo buffers reports buffering`() {
    val resumed =
      PlaybackEvent.ShouldPlayWhenReadyChanged(
        shouldPlayWhenReady = true,
        exoState = MediaPlayer.STATE_BUFFERING,
      )
    assertEquals(listOf(PlaybackState.BUFFERING), on(resumed, from = PlaybackState.PAUSED))
    assertEquals(emptyList<PlaybackState>(), on(resumed, from = PlaybackState.LOADING))
    assertEquals(emptyList<PlaybackState>(), on(resumed, from = PlaybackState.STOPPED))
    // Exo READY: PLAYING follows from IsPlayingChanged.
    assertEquals(
      emptyList<PlaybackState>(),
      on(
        PlaybackEvent.ShouldPlayWhenReadyChanged(
          shouldPlayWhenReady = true,
          exoState = MediaPlayer.STATE_READY,
        ),
        from = PlaybackState.PAUSED,
      ),
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
