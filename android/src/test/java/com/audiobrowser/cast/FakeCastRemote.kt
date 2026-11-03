package com.audiobrowser.cast

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionParameters
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** A player that keeps what it is told and records what it was sent: a receiver by default. */
internal class FakePlayer(looper: Looper, private val deviceInfo: DeviceInfo = DEVICE_INFO) :
  SimpleBasePlayer(looper) {
  val items = mutableListOf<MediaItem>()
  var index = C.INDEX_UNSET
  var positionMs = 0L
  private var intent = false
  private var readiness = STATE_IDLE
  private var volumeLevel = MAX_VOLUME
  var error: PlaybackException? = null

  /** Every list of items sent to it, as set, added or replaced. */
  val sent = mutableListOf<List<MediaItem>>()
  var stops = 0
  val volumeChanges = mutableListOf<Int>()
  private val uids = mutableListOf<Any>()

  fun update() = invalidateState()

  override fun getState(): State =
    State.Builder()
      .setAvailableCommands(MediaPlayer.Commands.Builder().addAllCommands().build())
      .setPlaylist(
        items.mapIndexed { i, item -> MediaItemData.Builder(uids[i]).setMediaItem(item).build() }
      )
      .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else index)
      .setContentPositionMs(positionMs)
      .setPlayWhenReady(intent, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaybackState(if (items.isEmpty() || error != null) STATE_IDLE else readiness)
      .setPlayerError(error)
      .setDeviceInfo(deviceInfo)
      .setDeviceVolume(volumeLevel)
      .build()

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    items.clear()
    uids.clear()
    insert(0, mediaItems)
    index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
    positionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
    return done()
  }

  override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
    insert(index, mediaItems)
    if (this.index == C.INDEX_UNSET) this.index = 0
    return done()
  }

  override fun handleReplaceMediaItems(
    fromIndex: Int,
    toIndex: Int,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<*> {
    remove(fromIndex, toIndex)
    insert(fromIndex, mediaItems)
    return done()
  }

  override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
    remove(fromIndex, toIndex)
    if (items.isEmpty()) index = C.INDEX_UNSET
    return done()
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    intent = playWhenReady
    return done()
  }

  override fun handlePrepare(): ListenableFuture<*> {
    readiness = STATE_READY
    return done()
  }

  override fun handleStop(): ListenableFuture<*> {
    readiness = STATE_IDLE
    stops++
    return done()
  }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: Int,
  ): ListenableFuture<*> {
    if (mediaItemIndex != C.INDEX_UNSET) index = mediaItemIndex
    this.positionMs = if (positionMs == C.TIME_UNSET) 0 else positionMs
    return done()
  }

  override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
    volumeLevel = deviceVolume
    volumeChanges += deviceVolume
    return done()
  }

  override fun handleRelease(): ListenableFuture<*> = done()

  // The handover sets these; the tests don't read them back.
  override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = done()

  override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
    done()

  override fun handleSetPlaybackParameters(
    playbackParameters: PlaybackParameters
  ): ListenableFuture<*> = done()

  override fun handleSetTrackSelectionParameters(
    trackSelectionParameters: TrackSelectionParameters
  ): ListenableFuture<*> = done()

  private fun insert(at: Int, mediaItems: List<MediaItem>) {
    sent += mediaItems
    items.addAll(at, mediaItems)
    uids.addAll(at, mediaItems.map { Any() })
  }

  private fun remove(fromIndex: Int, toIndex: Int) {
    items.subList(fromIndex, toIndex).clear()
    uids.subList(fromIndex, toIndex).clear()
  }

  private fun done() = Futures.immediateVoidFuture()

  companion object {
    const val MAX_VOLUME = 20
    val DEVICE_INFO: DeviceInfo =
      DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(MAX_VOLUME).build()
  }
}

/** A Cast session the test starts and ends. */
internal class FakeCastRemote(looper: Looper) : CastRemote {
  override val player = FakePlayer(looper)
  override var isSessionAvailable = false
    private set

  private var listener: (() -> Unit)? = null
  var released = false

  override fun setSessionListener(listener: (() -> Unit)?) {
    this.listener = listener
  }

  fun startSession() {
    isSessionAvailable = true
    listener?.invoke()
  }

  fun endSession() {
    isSessionAvailable = false
    listener?.invoke()
  }

  override fun release() {
    released = true
  }
}
