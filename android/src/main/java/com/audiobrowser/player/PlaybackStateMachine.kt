package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer
import com.margelo.nitro.audiobrowser.PlaybackState

/**
 * An observation that may move the playback state machine. Context (does a track exist, is the
 * engine audibly playing, the queue size) is captured into the event at the call site —
 * [PlaybackStateMachine] itself stays pure.
 */
sealed interface PlaybackEvent {
  /**
   * ExoPlayer's playback state changed. [exoState] MUST be the real ExoPlayer state, not the
   * InterceptingPlayer-masked value (which reports READY through a masked terminal error to keep
   * the session alive — feeding that back in would clear the ERROR state).
   */
  data class ExoPlaybackStateChanged(val exoState: Int, val mediaItemCount: Int) : PlaybackEvent

  /** The active media item changed (auto-advance, skip, queue swap). */
  data class MediaItemTransition(val hasTrack: Boolean, val isPlaying: Boolean) : PlaybackEvent

  data class PlayWhenReadyChanged(val playWhenReady: Boolean) : PlaybackEvent

  data class IsPlayingChanged(val isPlaying: Boolean) : PlaybackEvent
}

/**
 * The playback state transitions: the next states (in order) for an event from the current state,
 * empty to suppress. The Android analog of iOS's `nextPlaybackState(from:on:)` — guards here are
 * state-related ("not from ERROR/STOPPED"); context-related guards live in the event fields.
 * Effects (error clearing, event emission, timers) stay with the caller: the machine only decides.
 */
object PlaybackStateMachine {

  fun transitions(current: PlaybackState, event: PlaybackEvent): List<PlaybackState> =
    when (event) {
      is PlaybackEvent.ExoPlaybackStateChanged ->
        when (event.exoState) {
          MediaPlayer.STATE_BUFFERING -> listOf(PlaybackState.BUFFERING)
          MediaPlayer.STATE_READY -> listOf(PlaybackState.READY)
          MediaPlayer.STATE_IDLE ->
            // A terminal error (or an explicit stop) idles ExoPlayer; transitioning to NONE
            // would clear the ERROR/STOPPED state the session is rendering.
            if (current == PlaybackState.ERROR || current == PlaybackState.STOPPED) emptyList()
            else listOf(PlaybackState.NONE)
          MediaPlayer.STATE_ENDED ->
            // An emptied queue also reports STATE_ENDED; without items that is "nothing
            // loaded", not "played to end".
            listOf(if (event.mediaItemCount > 0) PlaybackState.ENDED else PlaybackState.NONE)
          else -> emptyList()
        }
      is PlaybackEvent.MediaItemTransition ->
        when {
          !event.hasTrack -> emptyList()
          // Already audibly playing (gapless auto-advance): run the full burst so consumers
          // see the canonical loading→ready→playing sequence rather than sticking on LOADING.
          event.isPlaying ->
            listOf(PlaybackState.LOADING, PlaybackState.READY, PlaybackState.PLAYING)
          else -> listOf(PlaybackState.LOADING)
        }
      is PlaybackEvent.PlayWhenReadyChanged ->
        if (!event.playWhenReady && current != PlaybackState.STOPPED) {
          listOf(PlaybackState.PAUSED)
        } else {
          emptyList()
        }
      is PlaybackEvent.IsPlayingChanged ->
        if (event.isPlaying) listOf(PlaybackState.PLAYING) else emptyList()
    }
}
