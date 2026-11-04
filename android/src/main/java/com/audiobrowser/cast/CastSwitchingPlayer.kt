package com.audiobrowser.cast

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The session's player while Cast is on: the local player while no Cast session is up, the
 * [remote]'s player while one is, carrying the queue, position and play intent across each change,
 * as Media3's `CastPlayer` does. Unlike it:
 * - each item's [CastMedia] is resolved before the queue reaches the receiver, playing on locally
 *   meanwhile at the handover; while casting, queue changes and seeks wait their turn behind the
 *   resolutions, so they apply in the order they were made;
 * - the remote outlives a local engine ([setLocal]), so a new engine keeps the session;
 * - while casting, a replacement that changes only an item's metadata (a now-playing restamp) stays
 *   on this side, because the receiver reloads any item it is sent again;
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

  // The queue changes and seeks waiting on a resolution while casting, the last one last.
  private val queue = SupervisorJob(scope.coroutineContext[Job])
  private var queued: Job? = null

  // What the receiver last failed to play, until the queue is loaded there again.
  private var remoteError: PlaybackException? = null

  // Items whose resolution failed during this handover, so it isn't retried in a loop; the
  // converter then sends their URL as it is.
  private val unresolvable = HashSet<MediaItem>()

  // Metadata-only replacements made while casting, by media id, laid over the receiver's items.
  private val restamped = HashMap<String, MediaItem>()

  // The receiver's queue as it was sent, which is what shows. The remote player reports as sent
  // only the items around the current one; the rest come as `MediaItem.EMPTY`, rebuilt without the
  // tag the caller's data rides on, or as an earlier queue's item whose id the receiver reused.
  private val sent = mutableListOf<MediaItem>()

  // The last state shown as sent, shown again while the reported queue and [sent] differ in
  // length: for a status or two after a change.
  private var lastSettled: State? = null

  // The uids of the items a restamp just replaced: kept in the placeholder state shown while
  // another change waits, which would otherwise give them new ones, read as a track change.
  private var restampedUids: List<Any>? = null
  private var restampedFrom = 0

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
        // The receiver can refuse the queue (its session ending under it); playback stays here.
        try {
          transfer(from = local, to = remote.player)
        } catch (e: IllegalStateException) {
          Timber.e(e, "Cast: could not hand playback to the receiver")
        }
      }
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

  private fun handOverToLocal() {
    // What still waits is in the reported queue already, which is what moves across.
    queue.cancelChildren()
    transfer(from = remote.player, to = local)
    restamped.clear()
    sent.clear()
    lastSettled = null
    remoteError = null
  }

  private fun onRemoteError(error: PlaybackException) {
    if (!isRemote || remoteError != null) return
    Timber.w(error, "Cast: the receiver failed to play")
    remoteError = error
    invalidateState()
  }

  private fun transfer(from: MediaPlayer, to: MediaPlayer) {
    val state = PlayerTransferState.builderFromPlayer(from)
    if (from === remote.player) state.setMediaItems(this.mediaItems())
    if (to === remote.player && isCurrentItemLive(from)) state.setCurrentPosition(C.TIME_UNSET)
    val transferred = state.build()
    if (to === remote.player) sent.replaceWith(transferred.mediaItems)
    transferred.setToPlayer(to)
    if (from.playbackState != STATE_IDLE) to.prepare()
    from.stop()
    setPlayer(to)
  }

  private fun isCurrentItemLive(player: MediaPlayer) =
    player.currentMediaItem?.let { resolutions[it]?.live } == true

  override fun getState(): State {
    restampedUids = null
    val state = super.getState()
    if (!isRemote) return state
    val asSent = sent.takeIf { it.size == state.playlist.size }
    val playlist =
      state.playlist.mapIndexed { index, data ->
        val item = asSent?.get(index) ?: data.mediaItem
        val shown = restamped[item.mediaId] ?: item
        if (shown === data.mediaItem) data else data.buildUpon().setMediaItem(shown).build()
      }
    val settled =
      asSent != null ||
        (sent.isEmpty() && playlist.all { it.mediaItem.localConfiguration?.tag != null })
    if (!settled) {
      return lastSettled
        ?: state
          .buildUpon()
          .setPlaylist(emptyList())
          .setCurrentMediaItemIndex(C.INDEX_UNSET)
          .setPlaybackState(STATE_IDLE)
          .build()
    }
    val error = remoteError
    return state
      .buildUpon()
      .setPlaylist(playlist)
      .apply { if (error != null) setPlayerError(error).setPlaybackState(STATE_IDLE) }
      .build()
      .also { lastSettled = it }
  }

  override fun getPlaceholderState(suggestedPlaceholderState: State): State {
    val uids = restampedUids ?: return suggestedPlaceholderState
    restampedUids = null
    val playlist = suggestedPlaceholderState.playlist.toMutableList()
    uids.forEachIndexed { offset, uid ->
      val index = restampedFrom + offset
      playlist[index] = playlist[index].buildUpon().setUid(uid).setIsPlaceholder(false).build()
    }
    return suggestedPlaceholderState.buildUpon().setPlaylist(playlist).build()
  }

  override fun handlePrepare(): ListenableFuture<*> {
    if (!isRemote || remoteError == null) return super.handlePrepare()
    remoteError = null
    val items = this.mediaItems()
    if (items.isEmpty()) return Futures.immediateVoidFuture()
    sent.replaceWith(items)
    return super.handleSetMediaItems(items, currentMediaItemIndex, C.TIME_UNSET)
  }

  override fun handleStop(): ListenableFuture<*> =
    if (isRemote) super.handleSetPlayWhenReady(false) else super.handleStop()

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    restamped.clear()
    return inTurn(mediaItems) {
      remoteError = null
      whileRemote { sent.replaceWith(mediaItems) }
      super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }
  }

  override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
    forgetRestamps(mediaItems)
    return inTurn(mediaItems) {
      whileRemote { sent.addAll(index.coerceAtMost(sent.size), mediaItems) }
      super.handleAddMediaItems(index, mediaItems)
    }
  }

  override fun handleReplaceMediaItems(
    fromIndex: Int,
    toIndex: Int,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<*> {
    if (isRemote && isMetadataOnly(fromIndex, toIndex, mediaItems)) {
      mediaItems.forEach { restamped[it.mediaId] = it }
      mediaItems.forEachIndexed { offset, item -> sent.restamp(fromIndex + offset, item) }
      val window = Timeline.Window()
      restampedFrom = fromIndex
      restampedUids = (fromIndex until toIndex).map { currentTimeline.getWindow(it, window).uid }
      return Futures.immediateVoidFuture()
    }
    forgetRestamps(itemsBetween(fromIndex, toIndex) + mediaItems)
    return inTurn(mediaItems) {
      whileRemote {
        sent.removeBetween(fromIndex, toIndex)
        sent.addAll(fromIndex.coerceAtMost(sent.size), mediaItems)
      }
      super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
    }
  }

  override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
    forgetRestamps(itemsBetween(fromIndex, toIndex))
    return inTurn {
      whileRemote { sent.removeBetween(fromIndex, toIndex) }
      super.handleRemoveMediaItems(fromIndex, toIndex)
    }
  }

  /** Drops the restamps of [items], which the receiver is sent or drops as they are now. */
  private fun forgetRestamps(items: List<MediaItem>) {
    items.forEach { restamped.remove(it.mediaId) }
  }

  private fun itemsBetween(fromIndex: Int, toIndex: Int) =
    (fromIndex until minOf(toIndex, mediaItemCount)).map(::getMediaItemAt)

  override fun handleMoveMediaItems(
    fromIndex: Int,
    toIndex: Int,
    newIndex: Int,
  ): ListenableFuture<*> = inTurn {
    whileRemote { if (toIndex <= sent.size) Util.moveItems(sent, fromIndex, toIndex, newIndex) }
    super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
  }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: Int,
  ): ListenableFuture<*> = inTurn { super.handleSeek(mediaItemIndex, positionMs, seekCommand) }

  /**
   * Runs [apply] on the active player now, unless it is the remote and [items] need resolving or an
   * earlier change still waits: then after those, in order. The returned future completes once
   * [apply] has run, which holds the player's reported state at what it will be until then.
   */
  private fun inTurn(
    items: List<MediaItem> = emptyList(),
    apply: () -> ListenableFuture<*>,
  ): ListenableFuture<*> {
    val pending = if (isRemote) items.filter { it !in resolutions } else emptyList()
    val previous = queued
    if (pending.isEmpty() && previous == null) return apply()
    val done = SettableFuture.create<Unit>()
    val job =
      scope.launch(queue) {
        try {
          previous?.join()
          resolveAll(pending)
          apply()
        } catch (e: IllegalStateException) {
          Timber.e(e, "Cast: could not apply a queue change")
        } finally {
          done.set(Unit)
        }
      }
    queued = job
    job.invokeOnCompletion { if (queued === job) queued = null }
    return done
  }

  private inline fun whileRemote(block: () -> Unit) {
    if (isRemote) block()
  }

  private fun MutableList<MediaItem>.replaceWith(items: List<MediaItem>) {
    clear()
    addAll(items)
  }

  private fun MutableList<MediaItem>.removeBetween(fromIndex: Int, toIndex: Int) {
    if (fromIndex < size) subList(fromIndex, toIndex.coerceAtMost(size)).clear()
  }

  /** Puts [item] at [index] if that is where it is: a restamp can come from a queue since gone. */
  private fun MutableList<MediaItem>.restamp(index: Int, item: MediaItem) {
    if (getOrNull(index)?.mediaId == item.mediaId) set(index, item)
  }

  private fun isMetadataOnly(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) =
    toIndex - fromIndex == mediaItems.size &&
      mediaItems.withIndex().all { (offset, item) ->
        // As reported, which already shows the changes still waiting.
        val replaced = getMediaItemAt(fromIndex + offset)
        replaced.mediaId == item.mediaId &&
          replaced.localConfiguration?.uri == item.localConfiguration?.uri
      }

  /**
   * Brings playback back to the local player if casting, and lets go of the remote. The local
   * player stays with its owner, unreleased.
   */
  override fun handleRelease(): ListenableFuture<*> {
    handover?.cancel()
    queue.cancelChildren()
    remote.setSessionListener(null)
    remote.setErrorListener(null)
    if (isRemote) handOverToLocal()
    remote.release()
    setPlayer(ReleasedPlayer(applicationLooper))
    return Futures.immediateVoidFuture()
  }

  /** Stands in once released, so this player stops listening to the local one. */
  private class ReleasedPlayer(looper: Looper) : SimpleBasePlayer(looper) {
    override fun getState(): State = State.Builder().build()
  }

  private fun MediaPlayer.mediaItems() = (0 until mediaItemCount).map(::getMediaItemAt)
}
