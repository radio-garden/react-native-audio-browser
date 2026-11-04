package com.audiobrowser.remote

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The session's player while remote outputs are on: the local player while none of the remotes has
 * a session, else the receiver of the first that has, carrying the queue, position, play intent,
 * repeat and shuffle across each move.
 * - The phone plays on while the current item is resolved for the receiver ([resolve]).
 * - While remote, this side owns the queue: it shows changes at once, and sends the receiver one
 *   item at a time, moving on when the receiver reports the item's end. An edit that leaves the
 *   current item in place doesn't reach the receiver. The shuffle mode is kept, but the queue plays
 *   in list order (radio-garden/react-native-audio-browser#210).
 * - Moving between remotes, or to a remote's new session, goes straight from receiver to receiver.
 * - A receiver's failure to play is the player error, which [prepare] clears by loading the item
 *   again; so is an item the receiver hasn't started within [startTimeoutMs] of being sent or
 *   played: holding it ready when paused, playing it when not. [stop] stops the receiver; [prepare]
 *   loads the item there again.
 * - [setVolume] is the receiver's device volume while remote.
 */
internal class RemoteSwitchingPlayer(
  local: MediaPlayer,
  remotes: List<RemotePlayback>,
  private val resolve: suspend (MediaItem) -> MediaItem,
  private val scope: CoroutineScope,
  private val startTimeoutMs: Long = 30_000,
) : ForwardingSimpleBasePlayer(local) {

  var local: MediaPlayer = local
    private set

  private var remotes: List<RemotePlayback> = emptyList()
  private var route: Route = Route.Local

  val isRemote: Boolean
    get() = route is Route.Remote

  private val queue = RemoteQueue()
  private var playIntent = false
  private var repeat = MediaPlayer.REPEAT_MODE_OFF
  private var shuffle = false

  private sealed interface Route {
    data object Local : Route

    /** The phone plays on while the current item is resolved for [target]'s receiver. */
    class HandingOver(val target: RemotePlayback, val job: Job) : Route

    class Remote(val output: RemotePlayback, val receiver: MediaPlayer) : Route {
      var load: Load = Load.Idle(C.TIME_UNSET)
      var startTimeout: Job? = null
    }
  }

  /** Where the receiver is with the current item. */
  private sealed interface Load {
    /**
     * Nothing loaded there: not prepared, stopped, or an empty queue. Prepare loads from
     * [positionMs].
     */
    class Idle(val positionMs: Long) : Load

    /**
     * The item is resolved for the receiver; until then, the receiver's reports are the last
     * item's.
     */
    class Resolving(val job: Job, val positionMs: Long) : Load

    /** [item] went to the receiver, which hasn't reported it yet. */
    class Sent(val item: MediaItem, val positionMs: Long) : Load

    /** The receiver has [item]: what it reports is the current item's. */
    class Playing(val item: MediaItem) : Load

    class Failed(val error: PlaybackException, val positionMs: Long) : Load

    /** The last item played to its end, at [positionMs]. */
    class Ended(val positionMs: Long) : Load
  }

  init {
    setRemotes(remotes)
  }

  /** Puts [newLocal] in the local player's place, and plays on it unless remote. */
  fun setLocal(newLocal: MediaPlayer) {
    local = newLocal
    if (!isRemote) setPlayer(newLocal)
  }

  /**
   * Plays on [newRemotes] from now on: a remote that stays keeps its session and what it plays, one
   * that goes is released.
   */
  fun setRemotes(newRemotes: List<RemotePlayback>) {
    val gone = remotes.filter { it !in newRemotes }
    remotes = newRemotes
    for (remote in newRemotes) remote.setListener(listenerFor(remote))
    gone.forEach { it.setListener(null) }
    updateActivePlayer()
    gone.forEach(RemotePlayback::release)
  }

  private fun listenerFor(remote: RemotePlayback) =
    object : RemotePlayback.Listener {
      override fun onSessionChanged() = updateActivePlayer()

      override fun onEnded() {
        if (remote === remoteRoute?.output) onReceiverEnded()
      }

      override fun onFailed(error: PlaybackException) {
        if (remote === remoteRoute?.output) onReceiverFailed(error)
      }
    }

  private val remoteRoute: Route.Remote?
    get() = route as? Route.Remote

  private val receiverListener =
    object : MediaPlayer.Listener {
      override fun onEvents(player: MediaPlayer, events: MediaPlayer.Events) {
        val remote = remoteRoute ?: return
        if (player !== remote.receiver) return
        val changed = followReceiver(remote)
        if (remote.startTimeout != null && started(remote)) disarmStart(remote)
        if (changed) invalidateState()
      }
    }

  /** Plays on [remote]'s receiver from now on, with [load] where it is with the current item. */
  private fun enter(remote: Route.Remote, load: Load) {
    remote.load = load
    route = remote
    remote.receiver.addListener(receiverListener)
    setPlayer(remote.receiver)
  }

  /** Lets go of [remote]'s receiver: its load is cut short and the receiver stopped. */
  private fun leave(remote: Route.Remote) {
    cancelLoad(remote)
    remote.receiver.removeListener(receiverListener)
    stopReceiver(remote.receiver)
  }

  private fun updateActivePlayer() {
    val current = remoteRoute
    if (
      current != null &&
        current.output in remotes &&
        current.output.isSessionAvailable &&
        current.output.player === current.receiver
    ) {
      return
    }
    val next = remotes.firstOrNull { it.isSessionAvailable }
    (route as? Route.HandingOver)?.let {
      if (it.target === next) return
      it.job.cancel()
      route = Route.Local
    }
    when {
      next == null -> if (current != null) handOverToLocal(current)
      current != null -> moveTo(current, next)
      else -> handOverToRemote(next)
    }
  }

  private fun handOverToRemote(target: RemotePlayback) {
    route =
      Route.HandingOver(
        target,
        scope.launch {
          // The phone plays on, and can move to another item, while the current one resolves.
          var item: MediaItem?
          var resolved: Result<MediaItem>?
          do {
            item = local.currentMediaItem
            resolved = item?.let { resolveCatching(it) }
          } while (item !== local.currentMediaItem)
          becomeRemote(target, resolved)
        },
      )
  }

  private fun becomeRemote(target: RemotePlayback, resolved: Result<MediaItem>?) {
    val items = local.mediaItems()
    queue.set(items, local.currentMediaItemIndex)
    playIntent = local.playWhenReady
    repeat = local.repeatMode
    shuffle = local.shuffleModeEnabled
    val prepared = local.playbackState != STATE_IDLE && items.isNotEmpty()
    val localPositionMs = local.currentPosition
    val remote = Route.Remote(target, target.player)
    enter(remote, Load.Idle(localPositionMs))
    local.stop()
    local.clearMediaItems()
    if (prepared) {
      resolved
        ?.onSuccess { send(remote, it, if (it.isRemoteLive) C.TIME_UNSET else localPositionMs) }
        ?.onFailure { fail(remote, it, localPositionMs) }
    }
    invalidateState()
  }

  /** Moves what plays from [from]'s receiver to [to]'s, without the phone in between. */
  private fun moveTo(from: Route.Remote, to: RemotePlayback) {
    val positionMs = positionOn(from)
    val load = from.load
    leave(from)
    val remote = Route.Remote(to, to.player)
    enter(remote, if (load is Load.Failed) load else Load.Idle(positionMs))
    if (load is Load.Resolving || load is Load.Sent || load is Load.Playing)
      load(remote, positionMs)
    invalidateState()
  }

  private fun handOverToLocal(from: Route.Remote) {
    val positionMs = positionOn(from)
    val prepared = from.load.let { it is Load.Resolving || it is Load.Sent || it is Load.Playing }
    leave(from)
    local.repeatMode = repeat
    local.shuffleModeEnabled = shuffle
    if (queue.isEmpty) local.clearMediaItems()
    else local.setMediaItems(queue.items.toList(), queue.index, positionMs)
    local.playWhenReady = playIntent
    if (prepared && !queue.isEmpty) local.prepare()
    route = Route.Local
    setPlayer(local)
  }

  /** Where the current item is on [remote]'s receiver, for the next player to go on from. */
  private fun positionOn(remote: Route.Remote): Long =
    when (val load = remote.load) {
      is Load.Idle -> load.positionMs
      is Load.Resolving -> load.positionMs
      is Load.Sent -> load.positionMs
      is Load.Failed -> load.positionMs
      is Load.Playing ->
        if (load.item.isRemoteLive || !receiverHolds(remote, load.item)) C.TIME_UNSET
        else remote.receiver.currentPosition
      is Load.Ended -> C.TIME_UNSET
    }

  private fun cancelLoad(remote: Route.Remote) {
    (remote.load as? Load.Resolving)?.job?.cancel()
    disarmStart(remote)
  }

  /**
   * Fails the current item unless [remote]'s receiver has [started] it within the start timeout.
   */
  private fun armStart(remote: Route.Remote) {
    disarmStart(remote)
    remote.startTimeout =
      scope.launch {
        delay(startTimeoutMs)
        remote.startTimeout = null
        if (route === remote && !started(remote)) {
          onReceiverFailed(
            PlaybackException(
              "The remote output did not start the item",
              null,
              PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
          )
        }
      }
  }

  private fun disarmStart(remote: Route.Remote) {
    remote.startTimeout?.cancel()
    remote.startTimeout = null
  }

  /** A receiver whose session already ended refuses commands: it stops by itself. */
  private fun stopReceiver(receiver: MediaPlayer) {
    try {
      receiver.stop()
    } catch (e: IllegalStateException) {
      Timber.w(e, "Remote output: could not stop the receiver")
    }
  }

  /** Loads the current item on [remote]'s receiver from [positionMs], once it is resolved. */
  private fun load(remote: Route.Remote, positionMs: Long) {
    cancelLoad(remote)
    val item = queue.current
    if (item == null) {
      remote.load = Load.Idle(C.TIME_UNSET)
      stopReceiver(remote.receiver)
      return
    }
    remote.load =
      Load.Resolving(
        scope.launch {
          val resolved = resolveCatching(item)
          if (route !== remote) return@launch
          resolved
            .onSuccess { send(remote, it, positionMs) }
            .onFailure { fail(remote, it, positionMs) }
          invalidateState()
        },
        positionMs,
      )
  }

  private fun send(remote: Route.Remote, item: MediaItem, positionMs: Long) {
    remote.load = Load.Sent(item, positionMs)
    armStart(remote)
    try {
      // Before the load, which autoplays as this says. Told again, a receiver plays what it holds.
      if (remote.receiver.playWhenReady != playIntent) remote.receiver.playWhenReady = playIntent
      remote.receiver.setMediaItem(item, positionMs)
    } catch (e: IllegalStateException) {
      // The session ended under it: the move, already on its way, takes the item along.
      Timber.e(e, "Remote output: could not load the item on the receiver")
    }
  }

  private fun fail(remote: Route.Remote, cause: Throwable, positionMs: Long) {
    Timber.w(cause, "Remote output: could not resolve the item")
    remote.load =
      Load.Failed(
        PlaybackException(
          "Could not resolve the item for the remote output",
          cause,
          PlaybackException.ERROR_CODE_REMOTE_ERROR,
        ),
        positionMs,
      )
  }

  private suspend fun resolveCatching(item: MediaItem): Result<MediaItem> =
    try {
      Result.success(resolve(item))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * Whether [remote]'s receiver holds the item sent and has started it: playing it, or ready to
   * while paused.
   */
  private fun started(remote: Route.Remote): Boolean {
    val load = remote.load as? Load.Playing ?: return false
    if (!receiverHolds(remote, load.item)) return false
    val receiver = remote.receiver
    return when (receiver.playbackState) {
      STATE_ENDED -> true
      STATE_READY -> !playIntent || receiver.isPlaying
      else -> false
    }
  }

  private fun onReceiverEnded() {
    val remote = remoteRoute ?: return
    followReceiver(remote)
    // An end reported before the receiver holds the item sent is the last item's.
    if (remote.load !is Load.Playing) return
    val next = queue.nextAtEnd(repeat)
    if (next == C.INDEX_UNSET) {
      val duration = remote.receiver.duration
      remote.load = Load.Ended(if (duration == C.TIME_UNSET) positionOn(remote) else duration)
    } else {
      queue.moveTo(next)
      load(remote, C.TIME_UNSET)
    }
    invalidateState()
  }

  private fun onReceiverFailed(error: PlaybackException) {
    val remote = remoteRoute ?: return
    val load = remote.load
    if (load !is Load.Playing && load !is Load.Sent) return
    Timber.w(error, "Remote output: the receiver failed to play")
    // One code for every remote failure: the local engine's recoveries key on the others.
    val remoteError =
      if (error.errorCode == PlaybackException.ERROR_CODE_REMOTE_ERROR) error
      else PlaybackException(error.message, error, PlaybackException.ERROR_CODE_REMOTE_ERROR)
    val positionMs = positionOn(remote)
    cancelLoad(remote)
    remote.load = Load.Failed(remoteError, positionMs)
    invalidateState()
  }

  /** Whether [remote]'s receiver reports [item] as its own. */
  private fun receiverHolds(remote: Route.Remote, item: MediaItem) =
    remote.receiver.currentMediaItem === item

  override fun getState(): State {
    val state = super.getState()
    val remote = remoteRoute ?: return state
    val load = remote.load
    val holds = load is Load.Playing && receiverHolds(remote, load.item)
    // Duration, seekability and liveness come from the receiver's data for the item it plays.
    val playing = state.playlist.getOrNull(state.currentMediaItemIndex).takeIf { holds }
    val playlist =
      queue.items.indices.map { i ->
        val data =
          if (i == queue.index && playing != null) playing.buildUpon().setUid(queue.uids[i])
          else MediaItemData.Builder(queue.uids[i])
        data.setMediaItem(queue.items[i]).build()
      }
    val maxVolume = state.deviceInfo.maxVolume
    return state
      .buildUpon()
      .setPlaylist(playlist)
      .setCurrentMediaItemIndex(queue.index)
      .setRepeatMode(repeat)
      .setShuffleModeEnabled(shuffle)
      .setVolume(if (maxVolume > 0) state.deviceVolume.toFloat() / maxVolume else 0f)
      .setAvailableCommands(state.availableCommands.buildUpon().addAll(*QUEUE_COMMANDS).build())
      .setPlayerError(null)
      .apply {
        if (!holds) setPlayWhenReady(playIntent, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        when (load) {
          is Load.Idle ->
            setPlaybackState(STATE_IDLE).setContentPositionMs(position(load.positionMs))
          is Load.Resolving ->
            setPlaybackState(STATE_BUFFERING).setContentPositionMs(position(load.positionMs))
          is Load.Sent ->
            setPlaybackState(STATE_BUFFERING).setContentPositionMs(position(load.positionMs))
          is Load.Failed ->
            setPlayerError(load.error)
              .setPlaybackState(STATE_IDLE)
              .setContentPositionMs(position(load.positionMs))
          is Load.Ended ->
            setPlaybackState(STATE_ENDED).setContentPositionMs(position(load.positionMs))
          is Load.Playing -> if (!holds) setPlaybackState(STATE_IDLE).setContentPositionMs(0)
        }
        if (queue.isEmpty) setPlaybackState(STATE_IDLE)
      }
      .build()
  }

  private fun position(positionMs: Long) = if (positionMs == C.TIME_UNSET) 0 else positionMs

  /**
   * Catches up with the receiver: the item arriving there, or a pause or play from elsewhere.
   * Returns whether anything changed.
   */
  private fun followReceiver(remote: Route.Remote): Boolean {
    when (val load = remote.load) {
      is Load.Sent ->
        if (receiverHolds(remote, load.item)) {
          remote.load = Load.Playing(load.item)
          playIntent = remote.receiver.playWhenReady
          return true
        }
      is Load.Playing ->
        if (receiverHolds(remote, load.item) && playIntent != remote.receiver.playWhenReady) {
          playIntent = remote.receiver.playWhenReady
          return true
        }
      else -> Unit
    }
    return false
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    val remote = remoteRoute ?: return super.handleSetPlayWhenReady(playWhenReady)
    playIntent = playWhenReady
    // A receiver that played on after failing still hears a pause; one still holding the last
    // item while the next resolves is not told to play it.
    if (!playWhenReady || remote.load !is Load.Resolving)
      remote.receiver.playWhenReady = playWhenReady
    if (remote.load is Load.Playing) {
      if (playWhenReady && !remote.receiver.isPlaying) armStart(remote) else disarmStart(remote)
    }
    return done()
  }

  override fun handlePrepare(): ListenableFuture<*> {
    val remote = remoteRoute ?: return super.handlePrepare()
    when (val load = remote.load) {
      is Load.Idle -> load(remote, load.positionMs)
      is Load.Failed -> load(remote, load.positionMs)
      is Load.Playing -> if (!receiverHolds(remote, load.item)) load(remote, C.TIME_UNSET)
      else -> Unit
    }
    return done()
  }

  override fun handleStop(): ListenableFuture<*> {
    val remote = remoteRoute ?: return super.handleStop()
    val positionMs = positionOn(remote)
    cancelLoad(remote)
    stopReceiver(remote.receiver)
    remote.load = Load.Idle(positionMs)
    return done()
  }

  override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
    if (!isRemote) return super.handleSetRepeatMode(repeatMode)
    repeat = repeatMode
    return done()
  }

  override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
    if (!isRemote) return super.handleSetShuffleModeEnabled(shuffleModeEnabled)
    shuffle = shuffleModeEnabled
    return done()
  }

  override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
    val remote = remoteRoute ?: return super.handleSetVolume(volume, volumeOperationType)
    val maxVolume = remote.receiver.deviceInfo.maxVolume
    remote.receiver.setDeviceVolume((volume * maxVolume).roundToInt(), 0)
    return done()
  }

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    val remote =
      remoteRoute ?: return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    queue.set(mediaItems, if (startIndex == C.INDEX_UNSET) 0 else startIndex)
    reloadCurrent(remote, startPositionMs)
    return done()
  }

  override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
    val remote = remoteRoute ?: return super.handleAddMediaItems(index, mediaItems)
    val wasEmpty = queue.isEmpty
    queue.add(index, mediaItems)
    if (wasEmpty) reloadCurrent(remote, C.TIME_UNSET)
    return done()
  }

  override fun handleReplaceMediaItems(
    fromIndex: Int,
    toIndex: Int,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<*> {
    val remote = remoteRoute ?: return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
    if (queue.replace(fromIndex, toIndex, mediaItems)) reloadCurrent(remote, C.TIME_UNSET)
    return done()
  }

  override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
    val remote = remoteRoute ?: return super.handleRemoveMediaItems(fromIndex, toIndex)
    if (!queue.remove(fromIndex, toIndex)) return done()
    // As the local player does: the current item gone with nothing after it, playback ends there,
    // unless the queue repeats.
    val nothingAfter = fromIndex >= queue.items.size && !queue.isEmpty
    val playing = remote.load !is Load.Idle && remote.load !is Load.Failed
    when {
      nothingAfter && repeat == REPEAT_MODE_ALL -> queue.moveTo(0)
      nothingAfter && playing -> {
        cancelLoad(remote)
        stopReceiver(remote.receiver)
        remote.load = Load.Ended(0)
        return done()
      }
    }
    reloadCurrent(remote, C.TIME_UNSET)
    return done()
  }

  override fun handleMoveMediaItems(
    fromIndex: Int,
    toIndex: Int,
    newIndex: Int,
  ): ListenableFuture<*> {
    if (!isRemote) return super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
    queue.move(fromIndex, toIndex, newIndex)
    return done()
  }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: Int,
  ): ListenableFuture<*> {
    val remote = remoteRoute ?: return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    val target = if (mediaItemIndex == C.INDEX_UNSET) queue.index else mediaItemIndex
    if (target !in queue.items.indices) return done()
    val load = remote.load
    if (
      target == queue.index &&
        positionMs != C.TIME_UNSET &&
        load is Load.Playing &&
        receiverHolds(remote, load.item)
    ) {
      remote.receiver.seekTo(positionMs)
    } else {
      queue.moveTo(target)
      reloadCurrent(remote, positionMs)
    }
    return done()
  }

  /**
   * Loads the current item again from [positionMs] if the receiver is to play it; else keeps the
   * position for the next prepare.
   */
  private fun reloadCurrent(remote: Route.Remote, positionMs: Long) {
    when (val load = remote.load) {
      is Load.Idle -> remote.load = Load.Idle(positionMs)
      is Load.Failed -> remote.load = Load.Failed(load.error, positionMs)
      else -> load(remote, positionMs)
    }
  }

  /**
   * Brings playback back to the local player if remote, and lets go of the remotes. The local
   * player stays with its owner, unreleased.
   */
  override fun handleRelease(): ListenableFuture<*> {
    (route as? Route.HandingOver)?.job?.cancel()
    if (route is Route.HandingOver) route = Route.Local
    remotes.forEach { it.setListener(null) }
    remoteRoute?.let(::handOverToLocal)
    remotes.forEach(RemotePlayback::release)
    setPlayer(ReleasedPlayer(applicationLooper))
    return done()
  }

  private fun done() = Futures.immediateVoidFuture()

  private companion object {
    // Available from the queue this side keeps, whatever the receiver's own.
    val QUEUE_COMMANDS =
      intArrayOf(
        MediaPlayer.COMMAND_PLAY_PAUSE,
        MediaPlayer.COMMAND_PREPARE,
        MediaPlayer.COMMAND_STOP,
        MediaPlayer.COMMAND_SEEK_TO_DEFAULT_POSITION,
        MediaPlayer.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        MediaPlayer.COMMAND_SEEK_TO_PREVIOUS,
        MediaPlayer.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        MediaPlayer.COMMAND_SEEK_TO_NEXT,
        MediaPlayer.COMMAND_SEEK_TO_MEDIA_ITEM,
        MediaPlayer.COMMAND_SET_MEDIA_ITEM,
        MediaPlayer.COMMAND_CHANGE_MEDIA_ITEMS,
        MediaPlayer.COMMAND_GET_TIMELINE,
        MediaPlayer.COMMAND_GET_CURRENT_MEDIA_ITEM,
        MediaPlayer.COMMAND_SET_REPEAT_MODE,
        MediaPlayer.COMMAND_SET_SHUFFLE_MODE,
        MediaPlayer.COMMAND_GET_VOLUME,
        MediaPlayer.COMMAND_SET_VOLUME,
      )
  }

  /** Stands in once released, so this player stops listening to the local one. */
  private class ReleasedPlayer(looper: Looper) : SimpleBasePlayer(looper) {
    override fun getState(): State = State.Builder().build()
  }

  private fun MediaPlayer.mediaItems() = (0 until mediaItemCount).map(::getMediaItemAt)
}
