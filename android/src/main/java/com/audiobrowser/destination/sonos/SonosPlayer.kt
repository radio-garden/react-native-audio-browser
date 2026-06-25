package com.audiobrowser.destination.sonos

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A Media3 [SimpleBasePlayer] that plays a single live stream on a Sonos speaker over UPnP. It is a
 * thin adapter: all wire control is delegated to the [SonosTransport] (which is itself fully
 * unit-tested), and this class only maps Media3 commands onto transport calls and reflects the
 * polled UPnP transport state back as Media3 player state.
 *
 * Because it is a real Media3 `Player`, the existing `InterceptingPlayer` wrapping, `PlayerListener`,
 * `NowPlayingUpdater`, and the `MediaSession.setPlayer` swap all work on it unchanged — exactly like
 * the Cast `CastPlayer`.
 *
 * Scope: **live-only**. One item, no seeking, no duration; transport is limited to play/pause/stop
 * and device volume. State updates and command handling run on the application [Looper] thread; SOAP
 * round-trips run on [io]. Every field mutation is posted back to the looper thread so [getState]
 * (called by the base on that thread) is race-free.
 */
@UnstableApi
class SonosPlayer(
  looper: Looper,
  private val device: SonosDevice,
  private val transport: SonosTransport,
  private val activeMediaItem: MediaItem,
  private val scope: CoroutineScope,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val pollIntervalMs: Long = 1500L,
  private val onFatalError: (PlaybackException) -> Unit = {},
) : SimpleBasePlayer(looper) {

  private val handler = Handler(looper)

  // All of these are read/written only on the looper thread.
  private var playbackStateInternal: Int = STATE_BUFFERING
  private var playWhenReadyInternal: Boolean = true
  private var deviceVolumeInternal: Int = 0
  private var pollJob: Job? = null

  private val availableCommands: Player.Commands =
    Player.Commands.Builder()
      .addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_STOP,
        Player.COMMAND_PREPARE,
        Player.COMMAND_RELEASE,
        Player.COMMAND_SET_MEDIA_ITEM,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
        Player.COMMAND_GET_DEVICE_VOLUME,
        Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
      )
      .build()

  private val deviceInfo: DeviceInfo =
    DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(MAX_VOLUME).build()

  override fun getState(): State {
    val item =
      MediaItemData.Builder(MEDIA_ITEM_UID)
        .setMediaItem(activeMediaItem)
        .setMediaMetadata(activeMediaItem.mediaMetadata)
        .setIsSeekable(false)
        .setIsDynamic(true) // live: unbounded
        .setDurationUs(C.TIME_UNSET)
        .build()
    return State.Builder()
      .setAvailableCommands(availableCommands)
      .setPlaybackState(playbackStateInternal)
      .setPlayWhenReady(playWhenReadyInternal, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaylist(listOf(item))
      .setCurrentMediaItemIndex(0)
      .setContentPositionMs(0)
      .setDeviceInfo(deviceInfo)
      .setDeviceVolume(deviceVolumeInternal)
      .build()
  }

  override fun handlePrepare(): ListenableFuture<*> {
    playbackStateInternal = STATE_BUFFERING
    loadAndStart()
    startPolling()
    return Futures.immediateVoidFuture()
  }

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    // A single live item; (re)load whatever is active. We ignore startIndex/position (no seek).
    loadAndStart()
    startPolling()
    return Futures.immediateVoidFuture()
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    playWhenReadyInternal = playWhenReady
    launchIo {
      if (playWhenReady) transport.play() else transport.pause()
    }
    return Futures.immediateVoidFuture()
  }

  override fun handleStop(): ListenableFuture<*> {
    stopPolling()
    playbackStateInternal = STATE_IDLE
    launchIo { transport.stop() }
    return Futures.immediateVoidFuture()
  }

  override fun handleRelease(): ListenableFuture<*> {
    stopPolling()
    return Futures.immediateVoidFuture()
  }

  override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
    val clamped = deviceVolume.coerceIn(0, MAX_VOLUME)
    deviceVolumeInternal = clamped
    launchIo { transport.setVolume(clamped) }
    return Futures.immediateVoidFuture()
  }

  /** Pushes the active stream to the device and reads back its current volume. */
  private fun loadAndStart() {
    val metadata = activeMediaItem.mediaMetadata
    val url = activeMediaItem.localConfiguration?.uri?.toString() ?: activeMediaItem.mediaId
    scope.launch(io) {
      try {
        transport.setUriAndPlay(
          streamUrl = url,
          title = metadata.title?.toString() ?: "",
          artist = metadata.artist?.toString(),
          album = metadata.albumTitle?.toString(),
          artworkUri = metadata.artworkUri?.toString(),
          live = true,
        )
        val volume = transport.getVolume()
        if (volume != null) handler.post { deviceVolumeInternal = volume; invalidateState() }
      } catch (t: Throwable) {
        reportError(t)
      }
    }
  }

  private fun startPolling() {
    if (pollJob?.isActive == true) return
    pollJob =
      scope.launch(io) {
        while (isActive) {
          val state = runCatching { transport.getTransportState() }.getOrNull()
          if (state != null) {
            val mapped = TransportStateMapper.map(state)
            handler.post {
              playbackStateInternal = mapped.playbackState
              invalidateState()
            }
          }
          delay(pollIntervalMs)
        }
      }
  }

  private fun stopPolling() {
    pollJob?.cancel()
    pollJob = null
  }

  /** Fire-and-forget SOAP command; surfaces failures as a player error on the looper thread. */
  private fun launchIo(block: suspend () -> Unit) {
    scope.launch(io) {
      try {
        block()
      } catch (t: Throwable) {
        reportError(t)
      }
    }
  }

  private fun reportError(t: Throwable) {
    Timber.e(t, "Sonos control failed")
    handler.post {
      onFatalError(
        PlaybackException(
          "Sonos control failed: ${t.message}",
          t,
          PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )
      )
    }
  }

  private companion object {
    const val MAX_VOLUME = 100
    const val MEDIA_ITEM_UID = "sonos-active-item"
  }
}
