package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Timeline
import com.audiobrowser.Callbacks
import com.audiobrowser.model.PlayerUpdateOptions
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent

/**
 * What the platform session reads of an idle engine. Android Auto takes STATE_IDLE for "nothing
 * playing": it closes the now-playing screen and its next/previous buttons with it. An engine never
 * prepared since its items were set (a paused restore) reads idle whatever the state, so Media3
 * posts no notification and starts no foreground service for it. Otherwise an ended queue reads
 * ended, as on an ended engine (a rebuild carries an ended engine idle), and with `keepAlive` a
 * terminal error (the engine's, or ERROR once a rebuild dropped it) and a pause that cancelled a
 * retry (PAUSED) read as paused and ready.
 */
internal object SessionView {
  private val PARKED_STATES = setOf(PlaybackState.PAUSED, PlaybackState.ERROR)
  private val SETTLED_STATES = setOf(PlaybackState.STOPPED, PlaybackState.NONE)

  fun playbackState(
    engineState: Int,
    state: PlaybackState,
    engineFailed: Boolean,
    keepAlive: Boolean,
    unprepared: Boolean,
  ): Int =
    when {
      engineState != MediaPlayer.STATE_IDLE -> engineState
      unprepared -> engineState
      state == PlaybackState.ENDED -> MediaPlayer.STATE_ENDED
      !keepAlive -> engineState
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

/** How far behind its default position a live item still plays at its live edge. */
internal const val LIVE_EDGE_TOLERANCE_MS = 10_000L

/**
 * Whether a skip to [index] leaves the stream as it plays, [state] being the engine's as the retry
 * view reads it: the current item, playing, live and at its live edge, where a stream without a
 * seekable window always is. Anything else seeks: an on-demand item starts over, a live one paused
 * or behind its edge rejoins it. iOS's `Player.skipTo` keeps the same rule; its `skipToCurrent`
 * names where they differ.
 */
internal fun MediaPlayer.skipsInPlace(index: Int, @MediaPlayer.State state: Int): Boolean {
  if (index != currentMediaItemIndex || !playWhenReady || state != MediaPlayer.STATE_READY) {
    return false
  }
  val window = currentTimeline.getWindow(index, Timeline.Window())
  return window.isLive &&
    (!window.isSeekable || window.defaultPositionMs - currentPosition < LIVE_EDGE_TOLERANCE_MS)
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
  /** The engine state shown for the engine's own; see [Player.retryView]. */
  private val engineView: (Int) -> Int,
  /** Whether the engine's playing is a blip the retry loop hides. */
  private val masksPlaying: () -> Boolean,
  /** Whether the engine has not been prepared since a paused restore set its items. */
  private val unprepared: () -> Boolean,
  /** Leads the engine's shuffle order with a start index, given the queue's size. */
  private val leadShuffleOrder: (startIndex: Int, count: Int) -> Unit,
  private val playPlayer: () -> Unit,
  private val stopPlayer: () -> Unit,
) : ForwardingPlayer(player) {

  private val engineListeners = mutableSetOf<MediaPlayer.Listener>()

  /** Each listener the session added, and the [SessionViewListener] it hears the engine through. */
  private val views = mutableMapOf<MediaPlayer.Listener, SessionViewListener>()

  /** Adds the player's own listener, which hears the engine's events as they are. */
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

  /**
   * Call when the player's state changes from [old]: on an idle engine the view can move with no
   * engine event.
   */
  fun onStateChanged(old: PlaybackState) {
    val view = playbackState
    val was =
      SessionView.playbackState(
        engineView(super.getPlaybackState()),
        old,
        engineFailed,
        keepSessionAliveOnError,
        unprepared(),
      )
    if (view != was) engineListeners.toList().forEach { it.onPlaybackStateChanged(view) }
    refreshViews()
  }

  // Media3 maps a player error to the legacy STATE_ERROR, which Android Auto and the notification
  // show as a disruptive error. Hidden whatever keepSessionAliveOnError says.
  override fun getPlayerError(): PlaybackException? = null

  private val engineFailed: Boolean
    get() = super.getPlayerError() != null

  /** Tells the session's listeners where the view moved with no event of the engine's. */
  fun refreshViews() = views.values.toList().forEach { it.refresh() }

  override fun isPlaying(): Boolean = super.isPlaying() && !masksPlaying()

  override fun getPlaybackState(): Int =
    SessionView.playbackState(
      engineView(super.getPlaybackState()),
      playerState(),
      engineFailed,
      keepSessionAliveOnError,
      unprepared(),
    )

  override fun getPlayWhenReady(): Boolean =
    SessionView.playWhenReady(
      super.getPlayWhenReady(),
      engineView(super.getPlaybackState()),
      playbackState,
    )

  // After a terminal error, a cancelled retry or a rebuild in ENDED the engine is idle, and the
  // default skips only move the index. An ended queue that stayed put stays ended, as on an ended
  // engine.
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

  // The session withholds the command from controllers while the capability is off; this refuses
  // one that arrives anyway. The app's own skips go to the wrapped player, not through here.
  private fun refusesQueueItem(mediaItemIndex: Int): Boolean =
    mediaItemIndex != currentMediaItemIndex &&
      !optionsProvider().capabilities.isEnabled(Capability.SKIP_TO_QUEUE_ITEM)

  override fun seekToDefaultPosition(mediaItemIndex: Int) {
    if (refusesQueueItem(mediaItemIndex)) return
    super.seekToDefaultPosition(mediaItemIndex)
  }

  override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
    if (refusesQueueItem(mediaItemIndex)) return
    intercept({ it.handleRemoteSeek(RemoteSeekEvent(position = positionMs.toDouble() / 1000.0)) }) {
      super.seekTo(mediaItemIndex, positionMs)
    }
  }

  override fun seekTo(positionMs: Long) =
    intercept({ it.handleRemoteSeek(RemoteSeekEvent(position = positionMs.toDouble() / 1000.0)) }) {
      super.seekTo(positionMs)
    }

  // Media3 sets here the queue of a track picked on an external surface and of playback
  // resumption. A pick in the queue the player holds skips to it, as Player.skipTo does, past the
  // intercepted seekTo. An unset start index is skipped: shuffleOrderLedBy would clamp it to 0.
  override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
    if (startIndex != C.INDEX_UNSET && isCurrentQueue(mediaItems)) {
      val state = engineView(wrappedPlayer.playbackState)
      if (!wrappedPlayer.skipsInPlace(startIndex, state)) {
        wrappedPlayer.seekTo(startIndex, startPositionMs)
      }
      leadShuffleOrder(startIndex, mediaItems.size)
      return
    }
    super.setMediaItems(mediaItems, startIndex, startPositionMs)
    if (startIndex != C.INDEX_UNSET) leadShuffleOrder(startIndex, mediaItems.size)
  }

  private fun isCurrentQueue(mediaItems: List<MediaItem>): Boolean =
    mediaItems.size == mediaItemCount &&
      mediaItems.withIndex().all { (i, item) -> item.mediaId == getMediaItemAt(i).mediaId }
}
