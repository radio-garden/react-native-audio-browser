package com.audiobrowser.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.model.PlayerUpdateOptions
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent

/**
 * The session-error masking rules, pure so their truth table is testable: with
 * `keepSessionAliveOnError`, a terminal load error (ExoPlayer idle + error present) is reported to
 * the platform session as paused-but-ready instead of STATE_NONE — Android Auto reads STATE_NONE as
 * "nothing playing" and tears down the now-playing screen, losing the next/previous buttons.
 */
internal object SessionErrorMask {
  fun playbackState(state: Int, hasError: Boolean, keepAlive: Boolean): Int =
    if (keepAlive && state == MediaPlayer.STATE_IDLE && hasError) MediaPlayer.STATE_READY else state

  fun playWhenReady(raw: Boolean, state: Int, hasError: Boolean, keepAlive: Boolean): Boolean =
    if (keepAlive && state == MediaPlayer.STATE_IDLE && hasError) false else raw
}

/**
 * The Remote-command seam: intercepts transport controls arriving from External surfaces (lock
 * screen, notification, Bluetooth, Android Auto — all via the MediaSession) and offers each to the
 * app's `handleRemote*` callback first; a callback returning true consumes the command, anything
 * else falls through to the default behavior. Also masks a terminal playback error from the
 * platform session (see [SessionErrorMask]) so the session stays alive and skippable; the error
 * still surfaces via `onPlaybackError` and `playbackState`.
 */
internal class InterceptingPlayer(
  player: MediaPlayer,
  private val callbacksProvider: () -> Callbacks?,
  private val optionsProvider: () -> PlayerUpdateOptions,
  private val keepSessionAliveOnError: Boolean,
) : ForwardingPlayer(player) {

  // Hide playback errors from the platform session. Media3 builds the legacy PlaybackStateCompat
  // from player.getPlayerError() (-> STATE_ERROR), which Android Auto and the notification render
  // as a disruptive error. The error still surfaces via the onPlaybackError JS callback and
  // `playbackState`, so a now-playing formatter can render it; playback context is preserved.
  override fun getPlayerError(): androidx.media3.common.PlaybackException? = null

  // Both masks read the real underlying error (super.getPlayerError()) so they're race-free with
  // state-change listeners.
  override fun getPlaybackState(): Int =
    SessionErrorMask.playbackState(
      super.getPlaybackState(),
      hasError = super.getPlayerError() != null,
      keepAlive = keepSessionAliveOnError,
    )

  override fun getPlayWhenReady(): Boolean =
    SessionErrorMask.playWhenReady(
      super.getPlayWhenReady(),
      super.getPlaybackState(),
      hasError = super.getPlayerError() != null,
      keepAlive = keepSessionAliveOnError,
    )

  /**
   * A terminal error leaves ExoPlayer idle; the default transport controls (super.play() /
   * super.seekToNext()) then flip playWhenReady or move the queue index but never restart a stopped
   * player. prepare() recovers it (and early-returns when not idle, so it's a no-op during normal
   * playback) — so next/previous/play resume on the selected station from the masked error state
   * instead of doing nothing.
   */
  private fun recoverFromErrorIfNeeded() {
    if (keepSessionAliveOnError && super.getPlayerError() != null) {
      // Through the forwarding chain (no override exists), reaching the wrapped player.
      prepare()
    }
  }

  /**
   * One definition of the dispatch: offer the command to the app's handler; true consumes it,
   * anything else runs the default (and, for the resuming commands, the idle-error recovery).
   */
  private inline fun intercept(
    handler: (Callbacks) -> Boolean,
    recover: Boolean = false,
    fallback: () -> Unit,
  ) {
    if (callbacksProvider()?.let(handler) == true) return
    fallback()
    if (recover) recoverFromErrorIfNeeded()
  }

  override fun play() = intercept({ it.handleRemotePlay() }, recover = true) { super.play() }

  override fun pause() = intercept({ it.handleRemotePause() }) { super.pause() }

  // Media3 controllers may drive transport via setPlayWhenReady instead of play()/pause()
  // (the documented ForwardingPlayer hazard); route it through the same interception so the
  // remote handlers and the idle-error recovery aren't bypassed.
  override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

  override fun stop() = intercept({ it.handleRemoteStop() }) { super.stop() }

  override fun seekToNext() =
    intercept({ it.handleRemoteNext() }, recover = true) { super.seekToNext() }

  override fun seekToNextMediaItem() =
    intercept({ it.handleRemoteNext() }, recover = true) { super.seekToNextMediaItem() }

  override fun seekToPrevious() =
    intercept({ it.handleRemotePrevious() }, recover = true) { super.seekToPrevious() }

  override fun seekToPreviousMediaItem() =
    intercept({ it.handleRemotePrevious() }, recover = true) { super.seekToPreviousMediaItem() }

  override fun seekForward() =
    intercept({
      it.handleRemoteJumpForward(
        RemoteJumpForwardEvent(interval = optionsProvider().forwardJumpInterval)
      )
    }) {
      super.seekForward()
    }

  override fun seekBack() =
    intercept({
      it.handleRemoteJumpBackward(
        RemoteJumpBackwardEvent(interval = optionsProvider().backwardJumpInterval)
      )
    }) {
      super.seekBack()
    }

  override fun seekTo(mediaItemIndex: Int, positionMs: Long) =
    intercept({ it.handleRemoteSeek(RemoteSeekEvent(position = positionMs.toDouble() / 1000.0)) }) {
      super.seekTo(mediaItemIndex, positionMs)
    }

  override fun seekTo(positionMs: Long) =
    intercept({ it.handleRemoteSeek(RemoteSeekEvent(position = positionMs.toDouble() / 1000.0)) }) {
      super.seekTo(positionMs)
    }
}
