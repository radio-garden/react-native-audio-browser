package com.audiobrowser.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.model.PlayerUpdateOptions
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent

/**
 * What the platform session reads of an idle engine. Android Auto takes STATE_IDLE for "nothing
 * playing" and closes the now-playing screen with its next/previous buttons. So an ended queue
 * reads ended, as a rebuild carries an ended engine idle, and with `keepAlive` a terminal error or
 * a pause that cancelled a retry reads ready and paused.
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
      engineState != MediaPlayer.STATE_IDLE -> engineState
      state == PlaybackState.ENDED -> MediaPlayer.STATE_ENDED
      !keepAlive -> engineState
      state in PARKED_STATES -> MediaPlayer.STATE_READY
      // The engine keeps its error through a stop or an emptied queue until its next prepare; the
      // player's state reaches ERROR only after the engine's IDLE event.
      engineFailed && state !in SETTLED_STATES -> MediaPlayer.STATE_READY
      else -> engineState
    }

  /** An idle engine read as anything else isn't loading, so it can't be about to play. */
  fun playWhenReady(raw: Boolean, engineState: Int, view: Int): Boolean =
    raw && (engineState != MediaPlayer.STATE_IDLE || view == MediaPlayer.STATE_IDLE)
}

/**
 * The player the MediaSession sees. Offers each transport command from an external surface to the
 * app's `handleRemote*` callback first: true consumes it, anything else runs the default. Shows the
 * session the [SessionView] of an idle engine; the error still reaches JS.
 */
internal class InterceptingPlayer(
  player: MediaPlayer,
  private val callbacksProvider: () -> Callbacks?,
  private val optionsProvider: () -> PlayerUpdateOptions,
  private val keepSessionAliveOnError: Boolean,
  private val playerState: () -> PlaybackState,
  /** The engine state shown in place of the engine's own; see [Player.retryView]. */
  private val engineView: (Int) -> Int,
  /** Whether the engine's playing is a blip the retry loop hides. */
  private val masksPlaying: () -> Boolean,
  private val playPlayer: () -> Unit,
  private val stopPlayer: () -> Unit,
) : ForwardingPlayer(player) {

  private val engineListeners = mutableSetOf<MediaPlayer.Listener>()

  private val views = mutableMapOf<MediaPlayer.Listener, SessionViewListener>()

  /** Adds a listener that hears the engine's events as they are, not the session's view. */
  fun addEngineListener(listener: MediaPlayer.Listener) {
    engineListeners += listener
    super.addListener(listener)
  }

  override fun addListener(listener: MediaPlayer.Listener) {
    super.addListener(views.getOrPut(listener) { SessionViewListener(listener, this) })
  }

  override fun removeListener(listener: MediaPlayer.Listener) {
    engineListeners -= listener
    super.removeListener(views.remove(listener) ?: listener)
  }

  /** Call when the player's state changes from [old]: on an idle engine the view moves with it. */
  fun onStateChanged(old: PlaybackState) {
    val view = playbackState
    val was =
      SessionView.playbackState(
        engineView(super.getPlaybackState()),
        old,
        engineFailed,
        keepSessionAliveOnError,
      )
    if (view != was) engineListeners.toList().forEach { it.onPlaybackStateChanged(view) }
    refreshViews()
  }

  // Media3 maps a player error to the legacy STATE_ERROR, which Android Auto and the notification
  // show as a disruptive error. Hidden whatever keepSessionAliveOnError says.
  override fun getPlayerError(): PlaybackException? = null

  private val engineFailed: Boolean
    get() = super.getPlayerError() != null

  fun refreshViews() = views.values.toList().forEach { it.refresh() }

  override fun isPlaying(): Boolean = super.isPlaying() && !masksPlaying()

  override fun getPlaybackState(): Int =
    SessionView.playbackState(
      engineView(super.getPlaybackState()),
      playerState(),
      engineFailed,
      keepSessionAliveOnError,
    )

  override fun getPlayWhenReady(): Boolean =
    SessionView.playWhenReady(
      super.getPlayWhenReady(),
      engineView(super.getPlaybackState()),
      playbackState,
    )

  // After a terminal error, a cancelled retry or a rebuild in ENDED the engine is idle, and the
  // default skips only move the index.
  private fun loadIdleEngine(fromIndex: Int) {
    if (super.getPlaybackState() != MediaPlayer.STATE_IDLE) return
    val view = playbackState
    if (
      view == MediaPlayer.STATE_READY ||
        view == MediaPlayer.STATE_ENDED && currentMediaItemIndex != fromIndex
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
    val index = currentMediaItemIndex
    fallback()
    if (recover) loadIdleEngine(index)
  }

  override fun play() = intercept({ it.handleRemotePlay() }) { playPlayer() }

  override fun pause() = intercept({ it.handleRemotePause() }) { super.pause() }

  // Media3 controllers can send play/pause as setPlayWhenReady, which would bypass the handlers.
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
