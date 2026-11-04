package com.audiobrowser.sonos

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.audiobrowser.remote.isRemoteLive
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Where a room is in what it plays, from `GetPositionInfo`; a field it doesn't report is null. */
internal data class SonosPosition(
  val streamContent: String?,
  val positionMs: Long?,
  val durationMs: Long?,
)

/** The UPnP control of one Sonos room, as [SonosPlayer] drives it. Every call blocks. */
internal interface SonosControl {
  fun setUri(
    streamUrl: String,
    title: String,
    artist: String?,
    album: String?,
    artworkUri: String?,
    live: Boolean,
  )

  fun play()

  fun pause()

  fun stop()

  fun seek(positionMs: Long)

  fun getTransportState(): String?

  fun getPosition(): SonosPosition?

  /**
   * The URI the room was last told to play, as it was sent; the track it plays may be another, the
   * one a redirect led to.
   */
  fun getMediaUri(): String?

  fun getVolume(): Int?

  fun setVolume(volume: Int)
}

/**
 * Plays one item at a time on a Sonos room: Media3 commands in, UPnP out, and the room's transport
 * state, position, stream title and volume polled back in. As a
 * [com.audiobrowser.remote.RemotePlayback]'s player, setting its item loads it on the speaker from
 * the start position given, without [prepare]. Each poll that reads it playing calls [onPlaying]. A
 * track that plays to its end calls [onEnded]; one stopped there early reads as paused. Once
 * another app or the Sonos app plays something else there, it reads as paused and leaves the room
 * alone until played again. A title the speaker reads from the stream arrives as an [IcyInfo] in
 * the timed metadata, where the local player reports its own. The room's volume is read when the
 * player starts and with each poll, and goes to [onVolume].
 *
 * Commands run in the order they are made, one at a time, off the main thread, and run to the end
 * even once the player is released, so its last stop reaches the speaker. A failed command that no
 * later one replaced goes to [onError] and leaves the player idle; so does a speaker that stops
 * answering [unreachableAfterPolls] polls in a row. An item the speaker never starts is the
 * switch's to time out. A room that played on after a failure still hears pause and stop. A failed
 * seek or volume change is only logged.
 */
internal class SonosPlayer(
  looper: Looper,
  private val control: SonosControl,
  private val onEnded: () -> Unit,
  private val onPlaying: () -> Unit,
  private val onError: (PlaybackException) -> Unit,
  private val onVolume: (Int) -> Unit,
  private val routingControllerId: () -> String?,
  private val scope: CoroutineScope,
  private val io: CoroutineDispatcher,
  private val pollIntervalMs: Long = 2_000,
  private val unreachableAfterPolls: Int = 3,
  private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : SimpleBasePlayer(looper) {

  private var item: MediaItem? = null
  private var playWhenReady = false
  private var playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
  private var playbackState = Player.STATE_IDLE
  private var volume: Int? = null
  private var timedMetadata: Metadata? = null

  // The track's duration and position as last read; while it reads as playing, the time of that
  // reading, from which its position runs on.
  private var durationMs = C.TIME_UNSET
  private var position: PositionSupplier = PositionSupplier.getConstant(0)
  private var readPositionMs = 0L
  private var playingSinceReadAtMs: Long? = null

  // The URL the speaker holds for us, until stopped or taken over by another app; whether it holds
  // it ready to play without loading again; when it was asked to play; whether it has since.
  private var loadedUrl: String? = null
  private var ready = false
  private var seenPlaying = false

  // A poll that overlapped a command is dropped: its reading may predate it. A failed command is an
  // error only while no later one has been made.
  private var generation = 0
  private var pendingCommands = 0
  private val commands = Mutex()
  private var polling: Job? = null
  private var failedPolls = 0

  init {
    command(fatal = false) {
      val read = control.getVolume() ?: return@command
      scope.launch { if (volume == null) setVolumeRead(read) }
    }
  }

  override fun getState(): State {
    val current = item
    val live = current?.isRemoteLive == true
    return State.Builder()
      .setAvailableCommands(if (volume == null) COMMANDS else COMMANDS_WITH_VOLUME)
      .setPlayWhenReady(playWhenReady, playWhenReadyReason)
      .setPlaybackState(if (current == null) Player.STATE_IDLE else playbackState)
      .setPlaylist(
        listOfNotNull(current).map {
          MediaItemData.Builder(UID)
            .setMediaItem(it)
            .setIsSeekable(!live)
            .setIsDynamic(live)
            .apply { if (live) setLiveConfiguration(it.liveConfiguration) }
            .setDurationUs(
              if (!live && durationMs != C.TIME_UNSET) Util.msToUs(durationMs) else C.TIME_UNSET
            )
            .build()
        }
      )
      .setCurrentMediaItemIndex(if (current == null) C.INDEX_UNSET else 0)
      .setContentPositionMs(position)
      .setDeviceInfo(
        DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
          .setMaxVolume(MAX_VOLUME)
          .setRoutingControllerId(routingControllerId())
          .build()
      )
      .setDeviceVolume(volume ?: 0)
      .apply { timedMetadata?.let(::setTimedMetadata) }
      .build()
  }

  override fun handlePrepare(): ListenableFuture<*> {
    if (!isPrepared) load(C.TIME_UNSET)
    return done()
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    this.playWhenReady = playWhenReady
    playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    if (!playWhenReady) {
      freezePosition()
      if (loadedUrl != null) command { pauseOrStop() }
      return done()
    }
    if (!isPrepared) return done()
    if (ready) {
      command { control.play() }
    } else {
      load(C.TIME_UNSET)
    }
    return done()
  }

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    item = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
    load(startPositionMs)
    return done()
  }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: Int,
  ): ListenableFuture<*> {
    val current = item ?: return done()
    if (!isPrepared) return done()
    if (current.isRemoteLive || positionMs == C.TIME_UNSET || !ready) {
      load(positionMs)
      return done()
    }
    readPositionMs = positionMs
    if (playingSinceReadAtMs != null) {
      playingSinceReadAtMs = nowMs()
      position = PositionSupplier.getExtrapolating(positionMs, 1f)
    } else {
      position = PositionSupplier.getConstant(positionMs)
    }
    command(fatal = false) { control.seek(positionMs) }
    return done()
  }

  override fun handleStop(): ListenableFuture<*> {
    stopPolling()
    freezePosition()
    if (loadedUrl != null) command { control.stop() }
    loadedUrl = null
    ready = false
    playbackState = Player.STATE_IDLE
    timedMetadata = null
    return done()
  }

  override fun handleRelease(): ListenableFuture<*> {
    stopPolling()
    generation++
    return done()
  }

  override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
    val target = deviceVolume.coerceIn(0, MAX_VOLUME)
    volume = target
    onVolume(target)
    command(fatal = false) { control.setVolume(target) }
    return done()
  }

  override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
    volume?.let { handleSetDeviceVolume(it + 1, flags) } ?: done()

  override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
    volume?.let { handleSetDeviceVolume(it - 1, flags) } ?: done()

  private val isPrepared
    get() = playbackState != Player.STATE_IDLE

  /**
   * Loads the item on the speaker from [startPositionMs], playing it if asked, and polls the room.
   */
  private fun load(startPositionMs: Long) {
    val current = item ?: return
    val url = current.localConfiguration?.uri?.toString() ?: return
    val metadata = current.mediaMetadata
    val live = current.isRemoteLive
    val play = playWhenReady
    val seekMs = startPositionMs.takeIf { !live && it != C.TIME_UNSET && it > 0 }
    playbackState = Player.STATE_BUFFERING
    loadedUrl = url
    ready = true
    seenPlaying = false
    timedMetadata = null
    durationMs = C.TIME_UNSET
    readPositionMs = seekMs ?: 0
    position = PositionSupplier.getConstant(readPositionMs)
    playingSinceReadAtMs = null
    command {
      control.setUri(
        streamUrl = url,
        title = metadata.station?.toString() ?: metadata.title?.toString() ?: "",
        artist = metadata.artist?.toString(),
        album = metadata.albumTitle?.toString(),
        artworkUri = metadata.artworkUri?.toString(),
        live = live,
      )
      if (seekMs != null) {
        try {
          control.seek(seekMs)
        } catch (e: SoapException) {
          Timber.w(e, "Sonos: could not start the track at %d ms", seekMs)
        }
      }
      if (play) control.play()
    }
    startPolling()
  }

  /** A radio stream often refuses Pause (UPnP 701): it is stopped instead, and Play restarts it. */
  private fun pauseOrStop() {
    try {
      control.pause()
    } catch (e: SoapException) {
      control.stop()
    }
  }

  /**
   * Runs [block] off the main thread after the commands before it. A [fatal] command's failure is
   * the player's while no later fatal command has been made.
   */
  private fun command(fatal: Boolean = true, block: () -> Unit) {
    val issued = if (fatal) ++generation else generation
    pendingCommands++
    scope.launch(NonCancellable) {
      commands.withLock {
        try {
          withContext(io) { block() }
        } catch (e: Exception) {
          Timber.w(e, "Sonos: command failed")
          if (fatal && generation == issued) {
            fail(
              PlaybackException("Sonos: ${e.message}", e, PlaybackException.ERROR_CODE_REMOTE_ERROR)
            )
          }
        } finally {
          pendingCommands--
        }
      }
    }
  }

  /** Idle, with the room still held: what played on there still hears pause and stop. */
  private fun fail(e: PlaybackException) {
    playbackState = Player.STATE_IDLE
    ready = false
    freezePosition()
    stopPolling()
    invalidateState()
    onError(e)
  }

  private fun setVolumeRead(read: Int) {
    if (read == volume) return
    volume = read
    onVolume(read)
    invalidateState()
  }

  private fun startPolling() {
    if (polling?.isActive == true) return
    failedPolls = 0
    polling =
      scope.launch {
        while (isActive) {
          poll()
          delay(pollIntervalMs)
        }
      }
  }

  private fun stopPolling() {
    polling?.cancel()
    polling = null
  }

  private suspend fun poll() {
    val reading =
      try {
        commands.withLock {
          withContext(io) {
            Reading(
              control.getTransportState(),
              control.getPosition(),
              control.getMediaUri(),
              control.getVolume(),
            )
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Timber.w(e, "Sonos: poll failed")
        if (++failedPolls >= unreachableAfterPolls && isPrepared) {
          fail(
            PlaybackException(
              "Sonos: the speaker doesn't answer",
              e,
              PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
          )
        }
        return
      }
    failedPolls = 0
    if (pendingCommands > 0 || !isPrepared) return
    apply(reading)
    invalidateState()
  }

  private class Reading(
    val transportState: String?,
    val position: SonosPosition?,
    val mediaUri: String?,
    val volume: Int?,
  )

  private fun apply(reading: Reading) {
    reading.volume?.let(::setVolumeRead)
    val url = loadedUrl ?: return
    val sent = SonosStreamUri.forTransport(url, item?.isRemoteLive == true)
    if (!reading.mediaUri.isNullOrEmpty() && reading.mediaUri != sent) {
      onTakenOver()
      return
    }
    reading.position?.streamContent?.let(::streamTitle)?.let { title ->
      timedMetadata = Metadata(0, IcyInfo(title.toByteArray(), title, null))
    }
    val state = reading.transportState?.uppercase() ?: return
    val live = item?.isRemoteLive ?: true
    if (!live) reading.position?.durationMs?.let { durationMs = it }
    val positionMs = if (live) 0 else reading.position?.positionMs ?: 0
    when (state) {
      "PLAYING" -> {
        seenPlaying = true
        playbackState = Player.STATE_READY
        if (!playWhenReady) setPlayWhenReadyFromSpeaker(true)
        readPositionMs = positionMs
        playingSinceReadAtMs = nowMs()
        position = PositionSupplier.getExtrapolating(positionMs, 1f)
        onPlaying()
      }
      "PAUSED_PLAYBACK",
      "PAUSED_RECORDING" -> {
        playbackState = Player.STATE_READY
        if (playWhenReady && seenPlaying) setPlayWhenReadyFromSpeaker(false)
        readPositionMs = positionMs
        playingSinceReadAtMs = null
        position = PositionSupplier.getConstant(positionMs)
      }
      "TRANSITIONING" -> {
        playbackState = Player.STATE_BUFFERING
        freezePosition()
      }
      else -> onStopped()
    }
  }

  /** Another app plays on the room: this player reads as paused and leaves the room alone. */
  private fun onTakenOver() {
    loadedUrl = null
    ready = false
    timedMetadata = null
    playbackState = Player.STATE_READY
    position = PositionSupplier.getConstant(0)
    playingSinceReadAtMs = null
    if (playWhenReady) setPlayWhenReadyFromSpeaker(false)
  }

  private fun freezePosition() {
    position = PositionSupplier.getConstant(position.get())
    playingSinceReadAtMs = null
  }

  /** The speaker is stopped: not started yet, done with the item, or stopped from elsewhere. */
  private fun onStopped() {
    if (playbackState == Player.STATE_ENDED) return
    if (!playWhenReady) {
      playbackState = Player.STATE_READY
      return
    }
    if (!seenPlaying) return
    ready = false
    if (trackEnded()) {
      playbackState = Player.STATE_ENDED
      position = PositionSupplier.getConstant(durationMs)
      playingSinceReadAtMs = null
      onEnded()
      return
    }
    playbackState = Player.STATE_READY
    position = PositionSupplier.getConstant(0)
    setPlayWhenReadyFromSpeaker(false)
  }

  /** Whether the track played to its end, as its last position read says. */
  private fun trackEnded(): Boolean {
    if (item?.isRemoteLive != false || durationMs == C.TIME_UNSET) return false
    val reachedMs = readPositionMs + (playingSinceReadAtMs?.let { nowMs() - it } ?: 0)
    return reachedMs >= durationMs - END_TOLERANCE_MS
  }

  private fun setPlayWhenReadyFromSpeaker(value: Boolean) {
    playWhenReady = value
    playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
  }

  private fun done() = Futures.immediateVoidFuture()

  private companion object {
    const val MAX_VOLUME = 100
    const val END_TOLERANCE_MS = 1_000L
    val UID = Any()
    val COMMANDS: Player.Commands =
      Player.Commands.Builder()
        .addAll(
          Player.COMMAND_PLAY_PAUSE,
          Player.COMMAND_PREPARE,
          Player.COMMAND_STOP,
          Player.COMMAND_RELEASE,
          Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
          Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
          Player.COMMAND_SET_MEDIA_ITEM,
          Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
          Player.COMMAND_GET_TIMELINE,
          Player.COMMAND_GET_METADATA,
          Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
        )
        .build()
    // Reading and stepping the volume wait for it to be known.
    val COMMANDS_WITH_VOLUME: Player.Commands =
      COMMANDS.buildUpon()
        .addAll(Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        .build()
  }
}

/**
 * The song line in a speaker's `streamContent`: an ICY title as it is, or an HLS stream's ID3 form,
 * `TYPE=SNG|TITLE <title>|ARTIST <artist>|…`, as `<artist> - <title>`. Null when blank, or for the
 * speaker's own status (`ZPSTR_CONNECTING`, `ZPSTR_BUFFERING`) while it connects.
 */
internal fun streamTitle(streamContent: String): String? {
  val content = streamContent.trim()
  if (content.isEmpty() || content.startsWith("ZPSTR_")) return null
  if (!content.startsWith("TYPE=")) return content
  val fields =
    content.split('|').associate { field ->
      field.substringBefore(' ') to field.substringAfter(' ', "").trim()
    }
  val title = fields["TITLE"].orEmpty()
  val artist = fields["ARTIST"].orEmpty()
  return when {
    title.isEmpty() && artist.isEmpty() -> null
    artist.isEmpty() -> title
    title.isEmpty() -> artist
    else -> "$artist - $title"
  }
}
