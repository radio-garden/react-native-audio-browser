package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer
import com.margelo.nitro.audiobrowser.PlaybackState

/**
 * An input to [PlaybackStateMachine]. The caller captures any context a transition depends on
 * (queue size, whether a track exists, whether audio plays) into the event, so the machine stays
 * pure.
 */
sealed interface PlaybackEvent {
  /**
   * [exoState] must be the session player's real state, not the InterceptingPlayer's: that one
   * reports READY through a masked terminal error, which would move the machine out of ERROR.
   */
  data class ExoPlaybackStateChanged(
    @get:MediaPlayer.State val exoState: Int,
    val mediaItemCount: Int,
    val shouldPlayWhenReady: Boolean,
  ) : PlaybackEvent

  /**
   * [loads] is false when the engine idles, so nothing loads the track. Read after the command's
   * own prepare(): onEvents runs once the command returns.
   */
  data class MediaItemTransition(
    val hasTrack: Boolean,
    val isPlaying: Boolean,
    val loads: Boolean,
    val shouldPlayWhenReady: Boolean,
  ) : PlaybackEvent

  /**
   * An input of [Player.shouldPlayWhenReady] changed: the intent, or a playback suppression such as
   * a transient audio focus loss, which moves the machine as a pause does. [exoState] is the real
   * state, as in [ExoPlaybackStateChanged].
   */
  data class ShouldPlayWhenReadyChanged(
    val shouldPlayWhenReady: Boolean,
    @get:MediaPlayer.State val exoState: Int,
  ) : PlaybackEvent

  data class IsPlayingChanged(val isPlaying: Boolean) : PlaybackEvent
}

/**
 * Decides the playback state transitions; the Android counterpart of iOS's
 * `nextPlaybackState(from:on:)`. Effects (clearing errors, emitting events, timers) stay with the
 * caller.
 */
object PlaybackStateMachine {

  /** The states to set, in order, when [event] arrives in [current]; empty to keep [current]. */
  fun transitions(current: PlaybackState, event: PlaybackEvent): List<PlaybackState> =
    when (event) {
      is PlaybackEvent.ExoPlaybackStateChanged ->
        when (event.exoState) {
          MediaPlayer.STATE_BUFFERING -> listOf(PlaybackState.BUFFERING)
          MediaPlayer.STATE_READY ->
            // While playing, READY is a transient before PLAYING (which IsPlayingChanged brings)
            // and would flash a settled state mid-startup. While paused it is the only signal that
            // a load finished, so consumers can drop their loading indicator.
            if (event.shouldPlayWhenReady) emptyList() else listOf(PlaybackState.READY)
          MediaPlayer.STATE_IDLE ->
            // A terminal error, stop() and a pause during a retry all idle ExoPlayer; NONE would
            // clear the state the session shows.
            if (current in setOf(PlaybackState.ERROR, PlaybackState.STOPPED, PlaybackState.PAUSED))
              emptyList()
            else listOf(PlaybackState.NONE)
          MediaPlayer.STATE_ENDED ->
            // An emptied queue also reports STATE_ENDED: nothing loaded, not played to the end.
            listOf(if (event.mediaItemCount > 0) PlaybackState.ENDED else PlaybackState.NONE)
          else -> emptyList()
        }
      is PlaybackEvent.MediaItemTransition ->
        when {
          // An emptied queue. STOPPED holds: the service clears the queue just before stop(), and
          // the events arrive after it.
          !event.hasTrack ->
            if (current == PlaybackState.STOPPED) emptyList() else listOf(PlaybackState.NONE)
          // Removing the current track after a stop, an error or a cancelled retry: play() loads
          // the new one. With the intent up PAUSED would read as playing; ERROR's toggle reloads.
          !event.loads ->
            if (current == PlaybackState.STOPPED || event.shouldPlayWhenReady) emptyList()
            else listOf(PlaybackState.PAUSED)
          // Already playing (gapless auto-advance): no IsPlayingChanged follows, so run the whole
          // sequence here or the state sticks on LOADING.
          event.isPlaying ->
            listOf(PlaybackState.LOADING, PlaybackState.READY, PlaybackState.PLAYING)
          else -> listOf(PlaybackState.LOADING)
        }
      is PlaybackEvent.ShouldPlayWhenReadyChanged ->
        when {
          // stop() and a natural end both clear the intent themselves; STOPPED and ENDED must not
          // turn into PAUSED. A pause holds ERROR, as on iOS.
          !event.shouldPlayWhenReady ->
            if (current in setOf(PlaybackState.STOPPED, PlaybackState.ENDED, PlaybackState.ERROR))
              emptyList()
            else listOf(PlaybackState.PAUSED)
          // Resuming while ExoPlayer still buffers: no state change of its own follows, and the
          // READY after it is dropped while playing, so report the load now.
          current == PlaybackState.PAUSED && event.exoState == MediaPlayer.STATE_BUFFERING ->
            listOf(PlaybackState.BUFFERING)
          else -> emptyList()
        }
      is PlaybackEvent.IsPlayingChanged ->
        if (event.isPlaying) listOf(PlaybackState.PLAYING) else emptyList()
    }
}
