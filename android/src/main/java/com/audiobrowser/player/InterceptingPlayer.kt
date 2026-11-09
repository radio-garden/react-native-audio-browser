package com.audiobrowser.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.model.PlayerUpdateOptions
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent

/**
 * What the platform session reads of an idle engine. With `keepAlive`, a terminal error (the
 * engine's, or ERROR once a rebuild dropped it) and a pause that cancelled a retry (PAUSED) read as
 * paused and ready rather than STATE_IDLE, which Android Auto takes for "nothing playing": it
 * closes the now-playing screen and its next/previous buttons with it.
 */
internal object SessionView {
  private val PARKED_STATES = setOf(PlaybackState.PAUSED, PlaybackState.ERROR)
  private val SETTLED_STATES = setOf(PlaybackState.STOPPED, PlaybackState.NONE)

  fun playbackState(
    engineState: Int,
    state: PlaybackState,
    engineFailed: Boolean,
    keepAlive: Boolean,
  ): Int =
    when {
      engineState != MediaPlayer.STATE_IDLE || !keepAlive -> engineState
      state in PARKED_STATES -> MediaPlayer.STATE_READY
      // The engine keeps its error through a stop or an emptied queue, until its next prepare. It
      // counts until the player's state reaches ERROR, which follows the engine's IDLE event.
      engineFailed && state !in SETTLED_STATES -> MediaPlayer.STATE_READY
      else -> engineState
    }

  /** An idle engine read as anything else isn't loading, so it can't be about to play. */
  fun playWhenReady(raw: Boolean, engineState: Int, view: Int): Boolean =
    raw && (engineState != MediaPlayer.STATE_IDLE || view == MediaPlayer.STATE_IDLE)
}

/**
 * The player the MediaSession sees. Offers each transport command from an external surface (lock
 * screen, notification, Bluetooth, Android Auto) to the app's `handleRemote*` callback first: true
 * consumes it, anything else runs the default. Shows the session the [SessionView] of an idle
 * engine; the error still reaches JS through `onPlaybackError` and `playbackState`.
 */
internal class InterceptingPlayer(
  player: MediaPlayer,
  private val callbacksProvider: () -> Callbacks?,
  private val optionsProvider: () -> PlayerUpdateOptions,
  private val keepSessionAliveOnError: Boolean,
  private val playerState: () -> PlaybackState,
  private val playPlayer: () -> Unit,
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

  /**
   * Call when the player's state changes from [old]: on an idle engine the view can move with no
   * engine event.
   */
  fun onStateChanged(old: PlaybackState) {
    val view = playbackState
    val was =
      SessionView.playbackState(
        super.getPlaybackState(),
        old,
        engineFailed,
        keepSessionAliveOnError,
      )
    if (view != was) listeners.toList().forEach { it.onPlaybackStateChanged(view) }
  }

  // Media3 maps a player error to the legacy STATE_ERROR, which Android Auto and the notification
  // show as a disruptive error. Hidden whatever keepSessionAliveOnError says.
  override fun getPlayerError(): androidx.media3.common.PlaybackException? = null

  private val engineFailed: Boolean
    get() = super.getPlayerError() != null

  override fun getPlaybackState(): Int =
    SessionView.playbackState(
      super.getPlaybackState(),
      playerState(),
      engineFailed,
      keepSessionAliveOnError,
    )

  override fun getPlayWhenReady(): Boolean =
    SessionView.playWhenReady(super.getPlayWhenReady(), super.getPlaybackState(), playbackState)

  // After a terminal error or a cancelled retry the engine is idle, and the default skips only move
  // the index; prepare() restarts it. prepare() is a no-op unless idle.
  private fun recoverFromErrorIfNeeded() {
    if (
      super.getPlaybackState() == MediaPlayer.STATE_IDLE && playbackState != MediaPlayer.STATE_IDLE
    ) {
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

  override fun play() = intercept({ it.handleRemotePlay() }) { playPlayer() }

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
