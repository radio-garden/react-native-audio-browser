package com.audiobrowser.cast

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The session's player while Cast is on: the local player while no Cast session is up, the
 * [remote]'s player while one is, carrying the queue, position and play intent across each change,
 * as Media3's `CastPlayer` does. Unlike it:
 * - the remote outlives a local engine ([setLocal]), so a new engine keeps the session;
 * - each item's [CastMedia] is resolved before the queue reaches the receiver, playing on locally
 *   meanwhile at the handover;
 * - while casting, this side owns the queue and the current item, and shows them at once. The
 *   remote player reports a change only once the receiver has, and reports items outside the
 *   receiver's window empty, so it supplies only the playback state and position. Each change loads
 *   the whole queue on the receiver again, which restarts the current stream; a change to an item's
 *   metadata only (a now-playing restamp) stays on this side;
 * - a receiver's failure to play is the player error, which [prepare] clears by loading the queue
 *   again, and [stop] pauses the receiver, which forgets its queue when stopped.
 */
internal class CastSwitchingPlayer(
  local: MediaPlayer,
  private val remote: CastRemote,
  private val resolutions: CastMediaResolutions,
  private val resolveMedia: suspend (MediaItem) -> CastMedia?,
  private val scope: CoroutineScope,
) : ForwardingSimpleBasePlayer(local) {

  var local: MediaPlayer = local
    private set

  /** Whether playback is on the receiver. */
  val isRemote: Boolean
    get() = player === remote.player

  private var handover: Job? = null

  // While casting: the queue and current item as set on this side, which is what shows.
  private val items = mutableListOf<MediaItem>()
  private val uids = mutableListOf<Any>()
  private var index = C.INDEX_UNSET
  private var playIntent = false

  // The item sent to the receiver for each entry, by uid: a restamp replaces the entry's item on
  // this side only.
  private val sent = HashMap<Any, MediaItem>()

  // The resolution and load of the queue on the receiver, and the entry it is to make current,
  // until the receiver reports it current.
  private var load: Job? = null
  private var loading: Any? = null
  // Until the load is sent, the receiver still reports the old queue: its items can be the same
  // objects, and its errors are the old item's.
  private var sending = false
  private var loadPositionMs = 0L

  // What the receiver last failed to play, until the queue is loaded there again.
  private var remoteError: PlaybackException? = null

  // Items whose resolution failed, so it isn't retried in a loop; the converter then sends their
  // URL as it is.
  private val unresolvable = HashSet<MediaItem>()

  init {
    remote.setSessionListener(::updateActivePlayer)
    remote.setErrorListener(::onRemoteError)
    updateActivePlayer()
  }

  /** Puts [newLocal] in the local player's place, and plays on it unless casting. */
  fun setLocal(newLocal: MediaPlayer) {
    local = newLocal
    if (!isRemote) setPlayer(newLocal)
  }

  private fun updateActivePlayer() {
    if (remote.isSessionAvailable) {
      if (!isRemote && handover == null) handOverToRemote()
    } else {
      handover?.cancel()
      handover = null
      if (isRemote) handOverToLocal()
    }
  }

  private fun handOverToRemote() {
    // A URL resolved for an earlier session may have expired since.
    resolutions.clear()
    unresolvable.clear()
    handover =
      scope.launch {
        // The queue can change while a resolution is in flight: settle until every item is tried.
        while (true) {
          val pending = local.mediaItems().filter { it !in resolutions && it !in unresolvable }
          if (pending.isEmpty()) break
          resolveAll(pending)
        }
        handover = null
        replaceQueue(local.mediaItems(), local.currentMediaItemIndex)
        playIntent = local.playWhenReady
        remote.player.repeatMode = local.repeatMode
        val positionMs = if (isCurrentItemLive()) C.TIME_UNSET else local.currentPosition
        if (local.playbackState != STATE_IDLE) loadOnReceiver(positionMs)
        setPlayer(remote.player)
        local.stop()
      }
  }

  private fun handOverToLocal() {
    load?.cancel()
    val positionMs =
      when {
        isCurrentItemLive() -> C.TIME_UNSET
        receiverPlaysCurrent() -> remote.player.currentPosition
        loading != null -> loadPositionMs
        else -> C.TIME_UNSET
      }
    val wasIdle = loading == null && remote.player.playbackState == STATE_IDLE
    local.repeatMode = remote.player.repeatMode
    if (items.isEmpty()) local.clearMediaItems()
    else local.setMediaItems(items.toList(), index, positionMs)
    local.playWhenReady = playIntent
    if (!wasIdle && items.isNotEmpty()) local.prepare()
    remote.player.stop()
    setPlayer(local)
    items.clear()
    uids.clear()
    sent.clear()
    index = C.INDEX_UNSET
    loading = null
    remoteError = null
  }

  private fun onRemoteError(error: PlaybackException) {
    if (!isRemote || remoteError != null || sending) return
    Timber.w(error, "Cast: the receiver failed to play")
    remoteError = error
    loading = null
    invalidateState()
  }

  private suspend fun resolveAll(items: List<MediaItem>) {
    for (item in items) {
      val media =
        try {
          resolveMedia(item)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Timber.w(e, "Cast: could not resolve %s", item.mediaId)
          null
        }
      if (media != null) resolutions[item] = media else unresolvable += item
    }
  }

  /**
   * Loads the queue on the receiver, from [positionMs] in the current item, once its items are
   * resolved; a later call takes the place of one still resolving.
   */
  private fun loadOnReceiver(positionMs: Long) {
    load?.cancel()
    remoteError = null
    if (items.isEmpty()) {
      loading = null
      sending = false
      remote.player.stop()
      return
    }
    val queue = items.toList()
    val current = index
    sent.clear()
    uids.forEachIndexed { i, uid -> sent[uid] = queue[i] }
    loading = uids[current]
    loadPositionMs = if (positionMs == C.TIME_UNSET) 0 else positionMs
    sending = true
    load =
      scope.launch {
        resolveAll(queue.filter { it !in resolutions && it !in unresolvable })
        sending = false
        try {
          // Before the load, which autoplays as this says.
          remote.player.playWhenReady = playIntent
          remote.player.setMediaItems(queue, current, positionMs)
        } catch (e: IllegalStateException) {
          // The session ended under it: the handback, already on its way, plays on locally.
          Timber.e(e, "Cast: could not load the queue on the receiver")
        }
      }
  }

  private fun replaceQueue(newItems: List<MediaItem>, newIndex: Int) {
    items.clear()
    items.addAll(newItems)
    uids.clear()
    uids.addAll(newItems.map { Any() })
    index = if (newItems.isEmpty()) C.INDEX_UNSET else newIndex.coerceIn(0, newItems.size - 1)
  }

  /**
   * Whether the receiver plays the current entry, as far as it says: an item it reports that this
   * side can't place (empty, or rebuilt from its own queue) counts as the current one.
   */
  private fun receiverPlaysCurrent(): Boolean {
    if (loading != null || remote.player.mediaItemCount == 0) return false
    val reported = remote.player.currentMediaItem ?: return false
    val at = entryOf(reported)
    return at < 0 || at == index
  }

  /** The entry the receiver's [item] was sent for, or -1. */
  private fun entryOf(item: MediaItem) = uids.indexOfFirst { sent[it] === item }

  private fun isCurrentItemLive() =
    (if (isRemote) items.getOrNull(index) else local.currentMediaItem)?.let {
      resolutions[it]?.live
    } == true

  override fun getState(): State {
    val state = super.getState()
    if (!isRemote) return state
    followReceiver()
    // Duration, seekability and liveness come from the receiver's data for the item it plays.
    val playing =
      state.playlist.getOrNull(state.currentMediaItemIndex).takeIf { receiverPlaysCurrent() }
    val playlist =
      items.indices.map { i ->
        val data =
          if (i == index && playing != null) playing.buildUpon().setUid(uids[i])
          else MediaItemData.Builder(uids[i])
        data.setMediaItem(items[i]).build()
      }
    val error = remoteError
    return state
      .buildUpon()
      .setPlaylist(playlist)
      .setCurrentMediaItemIndex(index)
      .setAvailableCommands(state.availableCommands.buildUpon().addAll(*QUEUE_COMMANDS).build())
      .apply {
        when {
          items.isEmpty() -> setPlaybackState(STATE_IDLE)
          error != null -> setPlayerError(error).setPlaybackState(STATE_IDLE)
          loading != null ->
            setPlaybackState(STATE_BUFFERING)
              .setPlayWhenReady(playIntent, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
              .setContentPositionMs(loadPositionMs)
          !receiverPlaysCurrent() -> setPlaybackState(STATE_IDLE).setContentPositionMs(0)
        }
      }
      .build()
  }

  /**
   * Catches up with the receiver: the load arriving there, or the receiver moving to another item
   * of the queue by itself (the end of an item, or a skip from another sender).
   */
  private fun followReceiver() {
    val reported = remote.player.currentMediaItem ?: return
    val loadingUid = loading
    if (loadingUid != null) {
      if (!sending && reported === sent[loadingUid]) loading = null
      return
    }
    val moved = entryOf(reported)
    if (moved >= 0) index = moved
    // A pause or play from the TV or another sender.
    if (receiverPlaysCurrent()) playIntent = remote.player.playWhenReady
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    if (isRemote) playIntent = playWhenReady
    return super.handleSetPlayWhenReady(playWhenReady)
  }

  override fun handlePrepare(): ListenableFuture<*> {
    if (!isRemote) return super.handlePrepare()
    val idle = remoteError != null || remote.player.mediaItemCount == 0
    if (loading == null && idle) loadOnReceiver(C.TIME_UNSET)
    return Futures.immediateVoidFuture()
  }

  override fun handleStop(): ListenableFuture<*> {
    if (!isRemote) return super.handleStop()
    playIntent = false
    return super.handleSetPlayWhenReady(false)
  }

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    if (!isRemote) return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    replaceQueue(mediaItems, if (startIndex == C.INDEX_UNSET) 0 else startIndex)
    loadOnReceiver(startPositionMs)
    return Futures.immediateVoidFuture()
  }

  override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
    if (!isRemote) return super.handleAddMediaItems(index, mediaItems)
    val at = index.coerceAtMost(items.size)
    items.addAll(at, mediaItems)
    uids.addAll(at, mediaItems.map { Any() })
    when {
      this.index == C.INDEX_UNSET -> this.index = 0
      this.index >= at -> this.index += mediaItems.size
    }
    loadOnReceiver(currentPositionOnReceiver())
    return Futures.immediateVoidFuture()
  }

  override fun handleReplaceMediaItems(
    fromIndex: Int,
    toIndex: Int,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<*> {
    if (!isRemote) return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
    val to = toIndex.coerceAtMost(items.size)
    if (isMetadataOnly(fromIndex, to, mediaItems)) {
      mediaItems.forEachIndexed { offset, item -> items[fromIndex + offset] = item }
      return Futures.immediateVoidFuture()
    }
    val replacesCurrent = index in fromIndex until to
    val positionMs = if (replacesCurrent) C.TIME_UNSET else currentPositionOnReceiver()
    items.subList(fromIndex, to).clear()
    uids.subList(fromIndex, to).clear()
    items.addAll(fromIndex, mediaItems)
    uids.addAll(fromIndex, mediaItems.map { Any() })
    index =
      when {
        items.isEmpty() -> C.INDEX_UNSET
        replacesCurrent -> fromIndex.coerceAtMost(items.size - 1)
        index >= to -> index - (to - fromIndex) + mediaItems.size
        else -> index
      }
    loadOnReceiver(positionMs)
    return Futures.immediateVoidFuture()
  }

  override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
    if (!isRemote) return super.handleRemoveMediaItems(fromIndex, toIndex)
    val to = toIndex.coerceAtMost(items.size)
    val removesCurrent = index in fromIndex until to
    val positionMs = if (removesCurrent) C.TIME_UNSET else currentPositionOnReceiver()
    items.subList(fromIndex, to).clear()
    uids.subList(fromIndex, to).clear()
    index =
      when {
        items.isEmpty() -> C.INDEX_UNSET
        removesCurrent -> fromIndex.coerceAtMost(items.size - 1)
        index >= to -> index - (to - fromIndex)
        else -> index
      }
    loadOnReceiver(positionMs)
    return Futures.immediateVoidFuture()
  }

  override fun handleMoveMediaItems(
    fromIndex: Int,
    toIndex: Int,
    newIndex: Int,
  ): ListenableFuture<*> {
    if (!isRemote) return super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
    val current = uids.getOrNull(index)
    Util.moveItems(items, fromIndex, toIndex.coerceAtMost(items.size), newIndex)
    Util.moveItems(uids, fromIndex, toIndex.coerceAtMost(uids.size), newIndex)
    index = uids.indexOf(current)
    loadOnReceiver(currentPositionOnReceiver())
    return Futures.immediateVoidFuture()
  }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: Int,
  ): ListenableFuture<*> {
    if (!isRemote) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    val target = if (mediaItemIndex == C.INDEX_UNSET) index else mediaItemIndex
    if (target == index && receiverPlaysCurrent()) {
      remote.player.seekTo(if (positionMs == C.TIME_UNSET) 0 else positionMs)
    } else if (target in items.indices) {
      index = target
      loadOnReceiver(positionMs)
    }
    return Futures.immediateVoidFuture()
  }

  private fun currentPositionOnReceiver() =
    when {
      loading != null -> loadPositionMs
      receiverPlaysCurrent() -> remote.player.currentPosition
      else -> C.TIME_UNSET
    }

  private fun isMetadataOnly(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) =
    toIndex - fromIndex == mediaItems.size &&
      mediaItems.withIndex().all { (offset, item) ->
        val replaced = items[fromIndex + offset]
        replaced.mediaId == item.mediaId &&
          replaced.localConfiguration?.uri == item.localConfiguration?.uri
      }

  /**
   * Brings playback back to the local player if casting, and lets go of the remote. The local
   * player stays with its owner, unreleased.
   */
  override fun handleRelease(): ListenableFuture<*> {
    handover?.cancel()
    remote.setSessionListener(null)
    remote.setErrorListener(null)
    if (isRemote) handOverToLocal()
    remote.release()
    setPlayer(ReleasedPlayer(applicationLooper))
    return Futures.immediateVoidFuture()
  }

  private companion object {
    // Available from the queue this side keeps, however far the receiver's own lags.
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
      )
  }

  /** Stands in once released, so this player stops listening to the local one. */
  private class ReleasedPlayer(looper: Looper) : SimpleBasePlayer(looper) {
    override fun getState(): State = State.Builder().build()
  }

  private fun MediaPlayer.mediaItems() = (0 until mediaItemCount).map(::getMediaItemAt)
}
