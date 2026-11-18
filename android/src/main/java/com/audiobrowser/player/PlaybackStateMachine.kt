package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer
import com.margelo.nitro.audiobrowser.PlaybackState

/** An input to [PlaybackStateMachine], carrying whatever context its transition depends on. */
sealed interface PlaybackEvent {
  /**
   * [exoState] is the session player's real state, not the InterceptingPlayer's: that one reports
   * READY through a masked terminal error, which would move the machine out of ERROR.
   */
  data class ExoPlaybackStateChanged(
    @get:MediaPlayer.State val exoState: Int,
    val mediaItemCount: Int,
    val shouldPlayWhenReady: Boolean,
  ) : PlaybackEvent

  /**
   * [loads] is false when the engine idles, so nothing loads the track. Read it after the command's
   * own prepare(): onEvents runs once the command returns.
   */
  data class MediaItemTransition(
    val hasTrack: Boolean,
    val isPlaying: Boolean,
    val loads: Boolean,
    val shouldPlayWhenReady: Boolean,
  ) : PlaybackEvent

  /**
   * The intent or a playback suppression (a transient audio focus loss) changed; [exoState] is the
   * real state, as in [ExoPlaybackStateChanged].
   */
  data class ShouldPlayWhenReadyChanged(
    val shouldPlayWhenReady: Boolean,
    @get:MediaPlayer.State val exoState: Int,
  ) : PlaybackEvent

  data class IsPlayingChanged(val isPlaying: Boolean) : PlaybackEvent
}

/**
 * Decides the playback state transitions, as iOS's `nextPlaybackState(from:on:)` does. Effects stay
 * with the caller.
 */
object PlaybackStateMachine {

  /** The states to set, in order, when [event] arrives in [current]; empty to keep [current]. */
  fun transitions(current: PlaybackState, event: PlaybackEvent): List<PlaybackState> =
    when (event) {
      is PlaybackEvent.ExoPlaybackStateChanged ->
        when (event.exoState) {
          MediaPlayer.STATE_BUFFERING -> listOf(PlaybackState.BUFFERING)
          MediaPlayer.STATE_READY ->
            // While playing, READY is a transient before IsPlayingChanged's PLAYING. While paused
            // it is the only signal that a load finished.
            if (event.shouldPlayWhenReady) emptyList() else listOf(PlaybackState.READY)
          MediaPlayer.STATE_IDLE ->
            // A terminal error, stop() and a pause during a retry all idle ExoPlayer.
            if (current in setOf(PlaybackState.ERROR, PlaybackState.STOPPED, PlaybackState.PAUSED))
              emptyList()
            else listOf(PlaybackState.NONE)
          MediaPlayer.STATE_ENDED ->
            // An emptied queue also reports STATE_ENDED.
            listOf(if (event.mediaItemCount > 0) PlaybackState.ENDED else PlaybackState.NONE)
          else -> emptyList()
        }
      is PlaybackEvent.MediaItemTransition ->
        when {
          // The service clears the queue just before stop(), and the events arrive after it.
          !event.hasTrack ->
            if (current == PlaybackState.STOPPED) emptyList() else listOf(PlaybackState.NONE)
          // play() loads the new track. With the intent up, PAUSED would contradict it.
          !event.loads ->
            if (current == PlaybackState.STOPPED || event.shouldPlayWhenReady) emptyList()
            else listOf(PlaybackState.PAUSED)
          // Gapless auto-advance: no IsPlayingChanged follows, so the whole sequence runs here.
          event.isPlaying ->
            listOf(PlaybackState.LOADING, PlaybackState.READY, PlaybackState.PLAYING)
          else -> listOf(PlaybackState.LOADING)
        }
      is PlaybackEvent.ShouldPlayWhenReadyChanged ->
        when {
          // stop() and a natural end clear the intent themselves. A pause holds ERROR, as on iOS.
          !event.shouldPlayWhenReady ->
            if (current in setOf(PlaybackState.STOPPED, PlaybackState.ENDED, PlaybackState.ERROR))
              emptyList()
            else listOf(PlaybackState.PAUSED)
          // Resuming while still buffering: no state change of its own follows, and the READY
          // after it is dropped while playing.
          current == PlaybackState.PAUSED && event.exoState == MediaPlayer.STATE_BUFFERING ->
            listOf(PlaybackState.BUFFERING)
          else -> emptyList()
        }
      is PlaybackEvent.IsPlayingChanged ->
        if (event.isPlaying) listOf(PlaybackState.PLAYING) else emptyList()
    }
}
