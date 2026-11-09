package com.audiobrowser.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.model.PlayerUpdateOptions
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent

/**
 * With `keepAlive`, a terminal error (idle with an error, or in ERROR once a rebuild dropped it) or
 * a pause that cancelled a retry (idle in PAUSED) reads as paused and ready to the platform session
 * rather than STATE_NONE, which Android Auto takes for "nothing playing": it closes the now-playing
 * screen and its next/previous buttons with it.
 */
internal object SessionErrorMask {
  fun playbackState(state: Int, hasError: Boolean, keepAlive: Boolean): Int =
    if (keepAlive && state == MediaPlayer.STATE_IDLE && hasError) MediaPlayer.STATE_READY else state

  fun playWhenReady(raw: Boolean, state: Int, hasError: Boolean, keepAlive: Boolean): Boolean =
    if (keepAlive && state == MediaPlayer.STATE_IDLE && hasError) false else raw
}

/**
 * The player the MediaSession sees. Offers each transport command from an external surface (lock
 * screen, notification, Bluetooth, Android Auto) to the app's `handleRemote*` callback first: true
 * consumes it, anything else runs the default. Masks a terminal error and a cancelled retry from
 * the session (see [SessionErrorMask]); the error still reaches JS through `onPlaybackError` and
 * `playbackState`.
 */
internal class InterceptingPlayer(
  player: MediaPlayer,
  private val callbacksProvider: () -> Callbacks?,
  private val optionsProvider: () -> PlayerUpdateOptions,
  private val keepSessionAliveOnError: Boolean,
  private val isParked: () -> Boolean,
  private val stopPlayer: () -> Unit,
) : ForwardingPlayer(player) {

  private val listeners = mutableSetOf<MediaPlayer.Listener>()

  override fun addListener(listener: MediaPlayer.Listener) {
    listeners += listener
    super.addListener(listener)
  }

  override fun removeListener(listener: MediaPlayer.Listener) {
    listeners -= listener
    super.removeListener(listener)
  }

  /** Call when [isParked] changes: on an idle engine the masked state moves with no event. */
  fun onParkedChanged() {
    if (keepSessionAliveOnError && super.getPlaybackState() == MediaPlayer.STATE_IDLE) {
      listeners.toList().forEach { it.onPlaybackStateChanged(playbackState) }
    }
  }

  private val parked: Boolean
    get() = super.getPlayerError() != null || isParked()

  // Media3 maps a player error to the legacy STATE_ERROR, which Android Auto and the notification
  // show as a disruptive error. Hidden whatever keepSessionAliveOnError says.
  override fun getPlayerError(): androidx.media3.common.PlaybackException? = null

  // The masks read super's error: this player's getPlayerError() is always null.
  override fun getPlaybackState(): Int =
    SessionErrorMask.playbackState(
      super.getPlaybackState(),
      hasError = parked,
      keepAlive = keepSessionAliveOnError,
    )

  override fun getPlayWhenReady(): Boolean =
    SessionErrorMask.playWhenReady(
      super.getPlayWhenReady(),
      super.getPlaybackState(),
      hasError = parked,
      keepAlive = keepSessionAliveOnError,
    )

  // After a terminal error or a cancelled retry the player is idle, and the default play/skip only
  // move playWhenReady or the index; prepare() restarts it. prepare() is a no-op unless idle.
  private fun recoverFromErrorIfNeeded() {
    if (keepSessionAliveOnError && parked) {
      prepare()
    }
  }

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

  // Media3 controllers can send play/pause as setPlayWhenReady, which would bypass the handlers and
  // the recovery.
  override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

  override fun stop() = intercept({ it.handleRemoteStop() }) { stopPlayer() }

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
