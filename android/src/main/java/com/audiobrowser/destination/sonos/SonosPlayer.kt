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
import com.margelo.nitro.audiobrowser.Track
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * A Media3 [SimpleBasePlayer] that plays a single live stream on a Sonos speaker over UPnP. It is a
 * thin adapter: all wire control is delegated to the [SonosTransport] (itself fully unit-tested),
 * and this class only maps Media3 commands onto transport calls and reflects the polled UPnP
 * transport state back as Media3 state. Because it is a real Media3 `Player`, the existing
 * `InterceptingPlayer` wrapping, `PlayerListener`, `NowPlayingUpdater`, and the
 * `MediaSession.setPlayer` swap all work on it unchanged — exactly like the Cast `CastPlayer`.
 *
 * Scope: **live-only** (one item, no seek/duration). Threading: command handling and all field
 * mutation run on the application [Looper] thread; SOAP round-trips run on [io] and are serialized
 * by [commandMutex] so `SetAVTransportURI`/`Play`/`Pause`/`Stop` cannot reorder. The player owns its
 * [playerScope]; [handleRelease] cancels it so the poll loop and in-flight commands die together.
 * The stream is loaded on [handlePrepare] (after `playWhenReady` is known) and `Play` is issued only
 * when `playWhenReady` is true, so a paused handoff does not start audio.
 */
@UnstableApi
class SonosPlayer(
  looper: Looper,
  private val device: SonosDevice,
  private val transport: SonosTransport,
  initialMediaItem: MediaItem? = null,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val pollIntervalMs: Long = 1500L,
  private val onFatalError: (PlaybackException) -> Unit = {},
) : SimpleBasePlayer(looper) {

  private val handler = Handler(looper)
  private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val commandMutex = Mutex()

  // Read/written only on the looper thread.
  private var currentItem: MediaItem? = initialMediaItem
  private var playbackStateInternal: Int = STATE_IDLE
  private var playWhenReadyInternal: Boolean = false
  private var deviceVolumeInternal: Int = 0
  private var prepared: Boolean = false
  private var released: Boolean = false
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
    val item = currentItem
    val playlist =
      if (item == null) {
        emptyList()
      } else {
        listOf(
          MediaItemData.Builder(MEDIA_ITEM_UID)
            .setMediaItem(item)
            .setMediaMetadata(item.mediaMetadata)
            .setIsSeekable(false)
            .setIsDynamic(true) // live: unbounded
            .setDurationUs(C.TIME_UNSET)
            .build()
        )
      }
    return State.Builder()
      .setAvailableCommands(availableCommands)
      .setPlaybackState(if (item == null) STATE_IDLE else playbackStateInternal)
      .setPlayWhenReady(playWhenReadyInternal, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaylist(playlist)
      .setContentPositionMs(0)
      .setDeviceInfo(deviceInfo)
      .setDeviceVolume(deviceVolumeInternal)
      .build()
  }

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    currentItem = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
    if (currentItem != null) playbackStateInternal = STATE_BUFFERING
    return Futures.immediateVoidFuture()
  }

  override fun handlePrepare(): ListenableFuture<*> {
    // Load on prepare (playWhenReady is known by now), once. Play only if playWhenReady.
    if (currentItem != null && !prepared) {
      prepared = true
      playbackStateInternal = STATE_BUFFERING
      loadAndStart()
      startPolling()
    }
    return Futures.immediateVoidFuture()
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    playWhenReadyInternal = playWhenReady
    // Before prepare, just record intent; handlePrepare applies it. After prepare, drive transport.
    if (prepared) {
      runTransport { if (playWhenReady) transport.play() else transport.pause() }
    }
    return Futures.immediateVoidFuture()
  }

  override fun handleStop(): ListenableFuture<*> {
    stopPolling()
    playbackStateInternal = STATE_IDLE
    runTransport { transport.stop() }
    return Futures.immediateVoidFuture()
  }

  override fun handleRelease(): ListenableFuture<*> {
    released = true
    stopPolling()
    playerScope.cancel()
    return Futures.immediateVoidFuture()
  }

  override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
    val clamped = deviceVolume.coerceIn(0, MAX_VOLUME)
    deviceVolumeInternal = clamped
    runTransport { transport.setVolume(clamped) }
    return Futures.immediateVoidFuture()
  }

  /** Pushes the active stream to the device (Play only when playWhenReady) and reads volume back. */
  private fun loadAndStart() {
    val item = currentItem ?: return
    val metadata = item.mediaMetadata
    val url = item.localConfiguration?.uri?.toString() ?: item.mediaId
    val live = (item.localConfiguration?.tag as? Track)?.live != false
    val shouldPlay = playWhenReadyInternal
    playerScope.launch(io) {
      try {
        commandMutex.withLock {
          transport.setUri(
            streamUrl = url,
            title = metadata.title?.toString() ?: "",
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            artworkUri = metadata.artworkUri?.toString(),
            live = live,
          )
          if (shouldPlay) transport.play()
        }
        val volume = transport.getVolume()
        if (volume != null) postIfActive { deviceVolumeInternal = volume }
      } catch (t: Throwable) {
        reportError(t)
      }
    }
  }

  private fun startPolling() {
    if (pollJob?.isActive == true) return
    pollJob =
      playerScope.launch(io) {
        while (isActive) {
          val state = runCatching { transport.getTransportState() }.getOrNull()
          val volume = runCatching { transport.getVolume() }.getOrNull()
          if (state != null) {
            val mapped = TransportStateMapper.map(state)
            postIfPolling {
              playbackStateInternal = mapped.playbackState
              if (volume != null) deviceVolumeInternal = volume
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

  /** Runs a transport command on IO, serialized so commands cannot reorder; reports failures. */
  private fun runTransport(block: suspend () -> Unit) {
    playerScope.launch(io) {
      try {
        commandMutex.withLock { block() }
      } catch (t: Throwable) {
        reportError(t)
      }
    }
  }

  /** Posts a state mutation to the looper, skipping it if the player has been released. */
  private fun postIfActive(block: () -> Unit) {
    handler.post {
      if (released) return@post
      block()
      invalidateState()
    }
  }

  /** Like [postIfActive] but also skips if polling was stopped (a stale queued poll result). */
  private fun postIfPolling(block: () -> Unit) {
    handler.post {
      if (released || pollJob?.isActive != true) return@post
      block()
      invalidateState()
    }
  }

  private fun reportError(t: Throwable) {
    Timber.e(t, "Sonos control failed")
    handler.post {
      if (released) return@post
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
