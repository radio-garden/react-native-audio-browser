package com.audiobrowser.remote

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Util
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal class FakeLocalPlayer(looper: Looper) : SimpleBasePlayer(looper) {
  val items = mutableListOf<MediaItem>()
  var index = C.INDEX_UNSET
  var positionMs = 0L
  var stops = 0
  var prepares = 0
  private var intent = false
  private var readiness = STATE_IDLE
  private var repeat = REPEAT_MODE_OFF
  private var shuffle = false
  private val uids = mutableListOf<Any>()
  private var metadata = Metadata(C.TIME_UNSET)

  /**
   * Sends a stream title. ExoPlayer sends each one it decodes; this state emits only a change, so a
   * stop and a prepare clear it for the first title of each connection to go out.
   */
  fun title(title: String) {
    metadata = Metadata(0, IcyInfo(title.toByteArray(), title, null))
    invalidateState()
  }

  override fun getState(): State =
    State.Builder()
      .setAvailableCommands(MediaPlayer.Commands.Builder().addAllCommands().build())
      .setPlaylist(
        items.mapIndexed { i, item ->
          MediaItemData.Builder(uids[i])
            .setMediaItem(item)
            .setLiveConfiguration(item.liveConfiguration.takeIf { item.isRemoteLive })
            .build()
        }
      )
      .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else index)
      .setContentPositionMs(positionMs)
      .setPlayWhenReady(intent, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaybackState(if (items.isEmpty()) STATE_IDLE else readiness)
      .setRepeatMode(repeat)
      .setShuffleModeEnabled(shuffle)
      .setTimedMetadata(metadata)
      .build()

  /** Moves to [index] without a seek, as the player does at an item's end. */
  fun moveTo(index: Int) {
    this.index = index
    invalidateState()
  }

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    items.clear()
    uids.clear()
    items.addAll(mediaItems)
    uids.addAll(mediaItems.map { Any() })
    index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
    positionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
    return done()
  }

  override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
    items.subList(fromIndex, toIndex).clear()
    uids.subList(fromIndex, toIndex).clear()
    if (items.isEmpty()) index = C.INDEX_UNSET
    return done()
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    intent = playWhenReady
    return done()
  }

  override fun handlePrepare(): ListenableFuture<*> {
    readiness = STATE_READY
    metadata = Metadata(C.TIME_UNSET)
    prepares++
    return done()
  }

  override fun handleStop(): ListenableFuture<*> {
    readiness = STATE_IDLE
    metadata = Metadata(C.TIME_UNSET)
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

  override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
    repeat = repeatMode
    return done()
  }

  override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
    shuffle = shuffleModeEnabled
    return done()
  }

  override fun handleSetPlaybackParameters(
    playbackParameters: PlaybackParameters
  ): ListenableFuture<*> = done()

  override fun handleSetTrackSelectionParameters(
    trackSelectionParameters: TrackSelectionParameters
  ): ListenableFuture<*> = done()

  override fun handleRelease(): ListenableFuture<*> = done()
}

/** [autoplay] is the receiver's playWhenReady when the load arrived. */
internal data class SentLoad(val item: MediaItem, val positionMs: Long, val autoplay: Boolean)

/** Holds one item at a time, as [RemotePlayback.player] does; a load of any other count throws. */
internal class FakeReceiver(looper: Looper) : SimpleBasePlayer(looper) {
  var item: MediaItem? = null
  var positionMs = 0L
  var durationMs = C.TIME_UNSET
  var intent = false
  var readiness = STATE_IDLE
  var volume = 10
  var stops = 0
  var released = false
  val loads = mutableListOf<SentLoad>()
  val seeks = mutableListOf<Long>()
  val intents = mutableListOf<Boolean>()

  /** While set, a load is held back from the reported state until [catchUp], as a receiver lags. */
  var lagging = false
  private var held: MediaItem? = null
  private var uid = Any()
  private var metadata: Metadata? = null

  fun catchUp() {
    held?.let { item = it }
    held = null
    invalidateState()
  }

  /** Changes playWhenReady as another sender would, unrecorded in [intents]. */
  fun setIntentFromElsewhere(playWhenReady: Boolean) {
    intent = playWhenReady
    invalidateState()
  }

  fun update() = invalidateState()

  /** Reports a stream title for whatever it plays, as a receiver's poll does; a load clears it. */
  fun title(title: String) {
    metadata = Metadata(0, IcyInfo(title.toByteArray(), title, null))
    invalidateState()
  }

  override fun getState(): State =
    State.Builder()
      .setAvailableCommands(MediaPlayer.Commands.Builder().addAllCommands().build())
      .setPlaylist(
        listOfNotNull(item).map {
          MediaItemData.Builder(uid).setMediaItem(it).setDurationUs(Util.msToUs(durationMs)).build()
        }
      )
      .setCurrentMediaItemIndex(if (item == null) C.INDEX_UNSET else 0)
      .setContentPositionMs(positionMs)
      .setPlayWhenReady(intent, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaybackState(if (item == null) STATE_IDLE else readiness)
      .setDeviceInfo(DEVICE_INFO)
      .setDeviceVolume(volume)
      .apply { metadata?.let(::setTimedMetadata) }
      .build()

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    val loaded = mediaItems.single()
    loads += SentLoad(loaded, startPositionMs, intent)
    // A fresh uid makes a reload of the same item a new playlist entry to listeners.
    uid = Any()
    metadata = null
    positionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
    readiness = STATE_READY
    if (lagging) held = loaded else item = loaded
    return done()
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    intent = playWhenReady
    intents += playWhenReady
    return done()
  }

  override fun handlePrepare(): ListenableFuture<*> = done()

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
    seeks += positionMs
    this.positionMs = positionMs
    return done()
  }

  override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
    volume = deviceVolume
    return done()
  }

  override fun handleRelease(): ListenableFuture<*> {
    released = true
    return done()
  }

  companion object {
    const val MAX_VOLUME = 20
    val DEVICE_INFO: DeviceInfo =
      DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(MAX_VOLUME).build()
  }
}

internal class FakeRemote(private val looper: Looper) : RemotePlayback {
  private var receiver: FakeReceiver? = null
  private var listener: RemotePlayback.Listener? = null
  var released = false

  override val player: FakeReceiver
    get() = checkNotNull(receiver) { "No session" }

  override val isSessionAvailable: Boolean
    get() = receiver != null

  override fun setListener(listener: RemotePlayback.Listener?) {
    this.listener = listener
  }

  /** A session already up, as one the Cast framework resumes, without telling the listener. */
  fun sessionUp() {
    receiver = FakeReceiver(looper)
  }

  /**
   * Starts a session, or another in place of the one up, as selecting another room does. [player]
   * is then a new receiver; the old one is released.
   */
  fun startSession() {
    val ended = receiver
    receiver = FakeReceiver(looper)
    listener?.onSessionChanged()
    ended?.release()
  }

  fun endSession() {
    receiver = null
    listener?.onSessionChanged()
  }

  fun playing() {
    listener?.onPlaying()
  }

  fun end() {
    player.readiness = MediaPlayer.STATE_ENDED
    player.update()
    listener?.onEnded()
  }

  fun fail(code: Int = PlaybackException.ERROR_CODE_REMOTE_ERROR) {
    listener?.onFailed(PlaybackException("receiver failed", null, code))
  }

  override fun release() {
    released = true
  }
}

private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()
