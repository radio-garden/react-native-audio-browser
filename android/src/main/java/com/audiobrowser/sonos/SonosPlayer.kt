package com.audiobrowser.sonos

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.audiobrowser.remote.RemoteMedia
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
 * Plays a queue on a Sonos room: Media3 commands in, UPnP out, and the room's transport state,
 * position, stream title and volume polled back in. As a [com.audiobrowser.remote.RemotePlayback]'s
 * player, setting its items loads the current one on the speaker, without [prepare]. The speaker
 * plays one item at a time: a track that ends there moves the queue on, and a track stopped there
 * early reads as paused. Once another app or the Sonos app plays something else there, it reads as
 * paused and leaves the room alone until played again. A title the speaker reads from the stream
 * arrives as an [IcyInfo] in the timed metadata, where the local player reports its own.
 *
 * Commands run in the order they are made, one at a time, off the main thread, and run to the end
 * even once the player is released, so its last stop reaches the speaker. A failed command that no
 * later one replaced goes to [onError] and leaves the player idle; so does an item the speaker
 * hasn't started within [startTimeoutMs] of being asked to play, and a speaker that stops answering
 * [unreachableAfterPolls] polls in a row. A failed volume change is only logged.
 */
internal class SonosPlayer(
  looper: Looper,
  private val control: SonosControl,
  private val mediaOf: (MediaItem) -> RemoteMedia?,
  private val onError: (PlaybackException) -> Unit,
  private val routingControllerId: () -> String?,
  private val scope: CoroutineScope,
  private val io: CoroutineDispatcher,
  private val pollIntervalMs: Long = 2_000,
  private val startTimeoutMs: Long = 20_000,
  private val unreachableAfterPolls: Int = 3,
  private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : SimpleBasePlayer(looper) {

  private class Entry(val uid: Long, val item: MediaItem)

  private var entries: List<Entry> = emptyList()
  private var nextUid = 0L
  private var index = 0
  private var playWhenReady = false
  private var playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
  private var playbackState = Player.STATE_IDLE
  private var volume = 0
  private var repeatMode = Player.REPEAT_MODE_OFF
  private var timedMetadata: Metadata? = null

  // The current track's duration and position as last read; while it reads as playing, the time
  // of that reading, from which its position runs on.
  private var durationMs = C.TIME_UNSET
  private var position: PositionSupplier = PositionSupplier.getConstant(0)
  private var readPositionMs = 0L
  private var playingSinceReadAtMs: Long? = null

  // The URL the speaker holds for us, until stopped or taken over by another app. The entry it
  // holds, ready to play without loading again; when it was asked to play; whether it has since.
  private var loadedUrl: String? = null
  private var loadedUid: Long? = null
  private var playRequestedAtMs = 0L
  private var seenPlaying = false

  // A poll that overlapped a command is dropped: its reading may predate it. A failed command is an
  // error only while no later one has been made.
  private var generation = 0
  private var pendingCommands = 0
  private val commands = Mutex()
  private var polling: Job? = null
  private var failedPolls = 0

  override fun getState(): State =
    State.Builder()
      .setAvailableCommands(COMMANDS)
      .setPlayWhenReady(playWhenReady, playWhenReadyReason)
      .setPlaybackState(if (entries.isEmpty()) Player.STATE_IDLE else playbackState)
      .setRepeatMode(repeatMode)
      .setPlaylist(
        entries.mapIndexed { i, entry ->
          val live = isLive(entry.item)
          MediaItemData.Builder(entry.uid)
            .setMediaItem(entry.item)
            .setIsSeekable(!live)
            .setIsDynamic(live)
            .apply { if (live) setLiveConfiguration(LIVE) }
            .setDurationUs(
              if (i == index && !live && durationMs != C.TIME_UNSET) Util.msToUs(durationMs)
              else C.TIME_UNSET
            )
            .build()
        }
      )
      .setCurrentMediaItemIndex(if (entries.isEmpty()) C.INDEX_UNSET else index)
      .setContentPositionMs(position)
      .setDeviceInfo(
        DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
          .setMaxVolume(MAX_VOLUME)
          .setRoutingControllerId(routingControllerId())
          .build()
      )
      .setDeviceVolume(volume)
      .apply { timedMetadata?.let(::setTimedMetadata) }
      .build()

  override fun handlePrepare(): ListenableFuture<*> {
    if (!isPrepared) load()
    return done()
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    this.playWhenReady = playWhenReady
    playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    if (!isPrepared) return done()
    when {
      !playWhenReady -> {
        freezePosition()
        if (loadedUrl != null) command { pauseOrStop() }
      }
      loadedUid == entries.getOrNull(index)?.uid -> {
        if (!seenPlaying) playRequestedAtMs = nowMs()
        command { control.play() }
      }
      else -> loadCurrent()
    }
    return done()
  }

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    entries = mediaItems.map { Entry(nextUid++, it) }
    index =
      if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, maxOf(0, entries.lastIndex))
    if (entries.isEmpty()) handleStop() else load()
    return done()
  }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: Int,
  ): ListenableFuture<*> {
    if (mediaItemIndex !in entries.indices) return done()
    val moved = mediaItemIndex != index
    index = mediaItemIndex
    if (!isPrepared) return done()
    val withinTrack =
      !moved &&
        loadedUid == entries[index].uid &&
        !isLive(entries[index].item) &&
        seekCommand == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM &&
        positionMs != C.TIME_UNSET
    if (withinTrack) {
      readPositionMs = positionMs
      if (playingSinceReadAtMs != null) {
        playingSinceReadAtMs = nowMs()
        position = PositionSupplier.getExtrapolating(positionMs, 1f)
      } else {
        position = PositionSupplier.getConstant(positionMs)
      }
      command(fatal = false) { control.seek(positionMs) }
    } else {
      load()
    }
    return done()
  }

  override fun handleStop(): ListenableFuture<*> {
    stopPolling()
    freezePosition()
    if (loadedUrl != null) command { control.stop() }
    loadedUrl = null
    loadedUid = null
    playbackState = Player.STATE_IDLE
    timedMetadata = null
    return done()
  }

  override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
    this.repeatMode = repeatMode
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
    command(fatal = false) { control.setVolume(target) }
    return done()
  }

  override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
    handleSetDeviceVolume(volume + 1, flags)

  override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
    handleSetDeviceVolume(volume - 1, flags)

  private val isPrepared
    get() = playbackState != Player.STATE_IDLE

  private fun load() {
    if (entries.isEmpty()) return
    playbackState = Player.STATE_BUFFERING
    loadCurrent()
    startPolling()
  }

  private fun loadCurrent() {
    val entry = entries.getOrNull(index) ?: return
    val item = entry.item
    val media = mediaOf(item)
    val url = media?.url ?: item.localConfiguration?.uri?.toString() ?: item.mediaId
    val metadata = item.mediaMetadata
    val play = playWhenReady
    loadedUrl = url
    loadedUid = entry.uid
    playRequestedAtMs = nowMs()
    seenPlaying = false
    timedMetadata = null
    durationMs = C.TIME_UNSET
    position = PositionSupplier.getConstant(0)
    readPositionMs = 0
    playingSinceReadAtMs = null
    command {
      control.setUri(
        streamUrl = url,
        title = metadata.station?.toString() ?: metadata.title?.toString() ?: "",
        artist = metadata.artist?.toString(),
        album = metadata.albumTitle?.toString(),
        artworkUri =
          media?.artworkUrl
            ?: metadata.artworkUri?.takeIf { it.scheme?.startsWith("http") == true }?.toString(),
        live = media?.live ?: isLive(item),
      )
      if (play) control.play()
    }
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

  private fun fail(e: PlaybackException) {
    playbackState = Player.STATE_IDLE
    loadedUid = null
    freezePosition()
    stopPolling()
    invalidateState()
    onError(e)
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
    reading.volume?.let { volume = it }
    val url = loadedUrl ?: return
    if (!reading.mediaUri.isNullOrEmpty() && !reading.mediaUri.contains(url)) {
      onTakenOver()
      return
    }
    reading.position?.streamContent?.let(::streamTitle)?.let { title ->
      timedMetadata = Metadata(0, IcyInfo(title.toByteArray(), title, null))
    }
    val state = reading.transportState?.uppercase() ?: return
    val live = entries.getOrNull(index)?.let { isLive(it.item) } ?: true
    if (!live) reading.position?.durationMs?.let { durationMs = it }
    val positionMs = if (live) 0 else reading.position?.positionMs ?: 0
    if (state != "PLAYING" && playWhenReady && !seenPlaying && startTimedOut()) {
      fail(
        PlaybackException(
          "Sonos: the speaker did not start the stream",
          null,
          PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )
      )
      return
    }
    when (state) {
      "PLAYING" -> {
        seenPlaying = true
        playbackState = Player.STATE_READY
        if (!playWhenReady) setPlayWhenReadyFromSpeaker(true)
        readPositionMs = positionMs
        playingSinceReadAtMs = nowMs()
        position = PositionSupplier.getExtrapolating(positionMs, 1f)
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

  private fun startTimedOut() = nowMs() - playRequestedAtMs > startTimeoutMs

  /** Another app plays on the room: this player reads as paused and leaves the room alone. */
  private fun onTakenOver() {
    loadedUrl = null
    loadedUid = null
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
    if (trackEnded()) {
      val next =
        when (repeatMode) {
          Player.REPEAT_MODE_ONE -> index
          Player.REPEAT_MODE_ALL -> (index + 1) % entries.size
          else -> index + 1
        }
      if (next < entries.size) {
        index = next
        playbackState = Player.STATE_BUFFERING
        loadCurrent()
      } else {
        playbackState = Player.STATE_ENDED
        loadedUid = null
        position = PositionSupplier.getConstant(durationMs)
        playingSinceReadAtMs = null
      }
      return
    }
    playbackState = Player.STATE_READY
    loadedUid = null
    position = PositionSupplier.getConstant(0)
    setPlayWhenReadyFromSpeaker(false)
  }

  /** Whether the current track played to its end, as its last position read says. */
  private fun trackEnded(): Boolean {
    val current = entries.getOrNull(index) ?: return false
    if (isLive(current.item) || durationMs == C.TIME_UNSET) return false
    val reachedMs = readPositionMs + (playingSinceReadAtMs?.let { nowMs() - it } ?: 0)
    return reachedMs >= durationMs - END_TOLERANCE_MS
  }

  private fun setPlayWhenReadyFromSpeaker(value: Boolean) {
    playWhenReady = value
    playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
  }

  private fun isLive(item: MediaItem) =
    mediaOf(item)?.live ?: (item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_RADIO_STATION)

  private fun done() = Futures.immediateVoidFuture()

  private companion object {
    const val MAX_VOLUME = 100
    const val END_TOLERANCE_MS = 1_000L
    val LIVE: MediaItem.LiveConfiguration = MediaItem.LiveConfiguration.Builder().build()
    val COMMANDS: Player.Commands =
      Player.Commands.Builder()
        .addAll(
          Player.COMMAND_PLAY_PAUSE,
          Player.COMMAND_PREPARE,
          Player.COMMAND_STOP,
          Player.COMMAND_RELEASE,
          Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
          Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
          Player.COMMAND_SEEK_TO_MEDIA_ITEM,
          Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
          Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
          Player.COMMAND_SEEK_TO_NEXT,
          Player.COMMAND_SEEK_TO_PREVIOUS,
          Player.COMMAND_SET_MEDIA_ITEM,
          // For setMediaItems of other than one item; the switch only ever sets the whole queue.
          Player.COMMAND_CHANGE_MEDIA_ITEMS,
          Player.COMMAND_SET_REPEAT_MODE,
          Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
          Player.COMMAND_GET_TIMELINE,
          Player.COMMAND_GET_METADATA,
          Player.COMMAND_GET_DEVICE_VOLUME,
          Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
          Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
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
