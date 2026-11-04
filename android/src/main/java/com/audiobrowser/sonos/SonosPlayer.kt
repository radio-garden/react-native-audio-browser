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
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.audiobrowser.remote.RemoteMedia
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

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

  fun getTransportState(): String?

  fun getStreamContent(): String?

  fun getVolume(): Int?

  fun setVolume(volume: Int)
}

/**
 * Plays a queue on a Sonos room: Media3 commands in, UPnP out, and the room's transport state,
 * stream title and volume polled back in. As a [com.audiobrowser.remote.RemotePlayback]'s player,
 * setting its items loads the current one on the speaker, without [prepare]. The speaker plays one
 * item at a time, so moving in the queue loads the new item there. A title the speaker reads from
 * the stream arrives as an [IcyInfo] in the timed metadata, where the local player reports its own.
 *
 * Commands run in the order they are made, one at a time, off the main thread. A failed command
 * goes to [onError] and leaves the player idle; so does an item the speaker hasn't started within
 * [startTimeoutMs].
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

  // The entry the speaker has loaded, and when; whether it has played since.
  private var loadedUid: Long? = null
  private var loadedAtMs = 0L
  private var seenPlaying = false

  // A poll that overlapped a command, made or waiting, is dropped: its reading may predate it.
  private var generation = 0
  private var pendingCommands = 0
  private val commands = Mutex()
  private var polling: Job? = null

  override fun getState(): State =
    State.Builder()
      .setAvailableCommands(COMMANDS)
      .setPlayWhenReady(playWhenReady, playWhenReadyReason)
      .setPlaybackState(if (entries.isEmpty()) Player.STATE_IDLE else playbackState)
      .setRepeatMode(repeatMode)
      .setPlaylist(
        entries.map { entry ->
          val live = isLive(entry.item)
          MediaItemData.Builder(entry.uid)
            .setMediaItem(entry.item)
            .setIsSeekable(false)
            .setIsDynamic(live)
            .apply { if (live) setLiveConfiguration(LIVE) }
            .setDurationUs(C.TIME_UNSET)
            .build()
        }
      )
      .setCurrentMediaItemIndex(if (entries.isEmpty()) C.INDEX_UNSET else index)
      .setContentPositionMs(0)
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
      !playWhenReady -> command { pauseOrStop() }
      loadedUid == entries.getOrNull(index)?.uid -> command { control.play() }
      else -> loadCurrent()
    }
    return done()
  }

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    entries = mediaItems.map(::entryOf)
    index =
      if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, maxOf(0, entries.lastIndex))
    if (entries.isEmpty()) handleStop() else load()
    return done()
  }

  override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
    val current = currentUid()
    entries =
      entries.toMutableList().apply { addAll(index.coerceAtMost(size), mediaItems.map(::entryOf)) }
    keepCurrent(current)
    onCurrentMaybeChanged()
    return done()
  }

  override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
    val current = currentUid()
    val removedCurrent = index in fromIndex until toIndex
    entries = entries.toMutableList().apply { subList(fromIndex, toIndex).clear() }
    if (removedCurrent) {
      index = fromIndex.coerceAtMost(maxOf(0, entries.lastIndex))
    } else {
      keepCurrent(current)
    }
    onCurrentMaybeChanged()
    return done()
  }

  override fun handleReplaceMediaItems(
    fromIndex: Int,
    toIndex: Int,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<*> {
    val current = currentUid()
    val replaced = entries.subList(fromIndex, toIndex)
    val replacements =
      mediaItems.mapIndexed { offset, item ->
        // The same item with new metadata keeps its entry, so the speaker doesn't reload it.
        val old = replaced.getOrNull(offset)
        if (old != null && sameStream(old.item, item)) Entry(old.uid, item) else entryOf(item)
      }
    entries =
      entries.toMutableList().apply {
        subList(fromIndex, toIndex).clear()
        addAll(fromIndex, replacements)
      }
    if (entries.none { it.uid == current }) {
      index = index.coerceAtMost(maxOf(0, entries.lastIndex))
    } else {
      keepCurrent(current)
    }
    onCurrentMaybeChanged()
    return done()
  }

  override fun handleMoveMediaItems(
    fromIndex: Int,
    toIndex: Int,
    newIndex: Int,
  ): ListenableFuture<*> {
    val current = currentUid()
    entries =
      entries.toMutableList().apply {
        val moved = subList(fromIndex, toIndex).toList()
        subList(fromIndex, toIndex).clear()
        addAll(newIndex.coerceAtMost(size), moved)
      }
    keepCurrent(current)
    return done()
  }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: Int,
  ): ListenableFuture<*> {
    if (mediaItemIndex in entries.indices) {
      index = mediaItemIndex
      onCurrentMaybeChanged(reloadSame = seekCommand == Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
    }
    return done()
  }

  override fun handleStop(): ListenableFuture<*> {
    stopPolling()
    if (loadedUid != null) command { control.stop() }
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
    command { control.setVolume(target) }
    return done()
  }

  override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
    handleSetDeviceVolume(volume + 1, flags)

  override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
    handleSetDeviceVolume(volume - 1, flags)

  private val isPrepared
    get() = playbackState != Player.STATE_IDLE

  private fun entryOf(item: MediaItem) = Entry(nextUid++, item)

  private fun currentUid() = entries.getOrNull(index)?.uid

  private fun keepCurrent(uid: Long?) {
    val at = entries.indexOfFirst { it.uid == uid }
    if (at >= 0) index = at
  }

  /** Loads the current entry on the speaker when it isn't the one loaded there. */
  private fun onCurrentMaybeChanged(reloadSame: Boolean = false) {
    if (!isPrepared) return
    if (entries.isEmpty()) {
      handleStop()
      return
    }
    if (reloadSame || currentUid() != loadedUid) {
      playbackState = Player.STATE_BUFFERING
      loadCurrent()
    }
  }

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
    loadedUid = entry.uid
    loadedAtMs = nowMs()
    seenPlaying = false
    timedMetadata = null
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

  /** Runs [block] off the main thread after the commands before it; a failure goes to [onError]. */
  private fun command(block: () -> Unit) {
    generation++
    pendingCommands++
    scope.launch {
      commands.withLock {
        try {
          withContext(io) { block() }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Timber.w(e, "Sonos: command failed")
          fail(
            PlaybackException("Sonos: ${e.message}", e, PlaybackException.ERROR_CODE_REMOTE_ERROR)
          )
        } finally {
          pendingCommands--
        }
      }
    }
  }

  private fun fail(e: PlaybackException) {
    playbackState = Player.STATE_IDLE
    loadedUid = null
    stopPolling()
    invalidateState()
    onError(e)
  }

  private fun startPolling() {
    if (polling?.isActive == true) return
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
    val started = generation
    val reading =
      try {
        commands.withLock {
          withContext(io) {
            Reading(control.getTransportState(), control.getStreamContent(), control.getVolume())
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Timber.w(e, "Sonos: poll failed")
        return
      }
    if (generation != started || pendingCommands > 0 || !isPrepared) return
    apply(reading)
    invalidateState()
  }

  private class Reading(val transportState: String?, val streamContent: String?, val volume: Int?)

  private fun apply(reading: Reading) {
    reading.volume?.let { volume = it }
    reading.streamContent?.let(::streamTitle)?.let { title ->
      val metadata = Metadata(0, IcyInfo(title.toByteArray(), title, null))
      if (metadata != timedMetadata) timedMetadata = metadata
    }
    when (reading.transportState?.uppercase() ?: return) {
      "PLAYING" -> {
        seenPlaying = true
        playbackState = Player.STATE_READY
        if (!playWhenReady) setPlayWhenReadyFromSpeaker(true)
      }
      "PAUSED_PLAYBACK",
      "PAUSED_RECORDING" -> {
        playbackState = Player.STATE_READY
        if (playWhenReady && seenPlaying) setPlayWhenReadyFromSpeaker(false)
      }
      "TRANSITIONING" -> playbackState = Player.STATE_BUFFERING
      else -> onStopped()
    }
  }

  /** The speaker is stopped: not started yet, done with the item, or stopped from elsewhere. */
  private fun onStopped() {
    if (!playWhenReady) {
      playbackState = Player.STATE_READY
      return
    }
    if (!seenPlaying) {
      if (nowMs() - loadedAtMs > startTimeoutMs) {
        fail(
          PlaybackException(
            "Sonos: the speaker did not start the stream",
            null,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
          )
        )
      }
      return
    }
    val current = entries.getOrNull(index)
    if (current != null && !isLive(current.item)) {
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
      }
      return
    }
    playbackState = Player.STATE_READY
    loadedUid = null
    setPlayWhenReadyFromSpeaker(false)
  }

  private fun setPlayWhenReadyFromSpeaker(value: Boolean) {
    playWhenReady = value
    playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
  }

  private fun isLive(item: MediaItem) =
    mediaOf(item)?.live ?: (item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_RADIO_STATION)

  private fun sameStream(a: MediaItem, b: MediaItem) =
    a.mediaId == b.mediaId && a.localConfiguration?.uri == b.localConfiguration?.uri

  private fun done() = Futures.immediateVoidFuture()

  private companion object {
    const val MAX_VOLUME = 100
    val LIVE: MediaItem.LiveConfiguration = MediaItem.LiveConfiguration.Builder().build()
    val COMMANDS: Player.Commands =
      Player.Commands.Builder()
        .addAll(
          Player.COMMAND_PLAY_PAUSE,
          Player.COMMAND_PREPARE,
          Player.COMMAND_STOP,
          Player.COMMAND_RELEASE,
          Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
          Player.COMMAND_SEEK_TO_MEDIA_ITEM,
          Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
          Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
          Player.COMMAND_SEEK_TO_NEXT,
          Player.COMMAND_SEEK_TO_PREVIOUS,
          Player.COMMAND_SET_MEDIA_ITEM,
          Player.COMMAND_SET_REPEAT_MODE,
          Player.COMMAND_CHANGE_MEDIA_ITEMS,
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
