package com.audiobrowser.cast

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 *   on this side, because the receiver reloads any item it is sent again.
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

  // The last queue change or seek waiting on a resolution while casting.
  private var queued: Job? = null

  // Items whose resolution failed during this handover, so it isn't retried in a loop; the
  // converter then sends their URL as it is.
  private val unresolvable = HashSet<MediaItem>()

  // Metadata-only replacements made while casting, by media id, laid over the receiver's items.
  private val restamped = HashMap<String, MediaItem>()

  init {
    remote.setSessionListener(::updateActivePlayer)
    if (remote.isSessionAvailable) setPlayer(remote.player)
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
    transfer(from = remote.player, to = local)
    restamped.clear()
  }

  private fun transfer(from: MediaPlayer, to: MediaPlayer) {
    val state = PlayerTransferState.builderFromPlayer(from)
    if (from === remote.player) state.setMediaItems(this.mediaItems())
    if (to === remote.player && isCurrentItemLive(from)) state.setCurrentPosition(C.TIME_UNSET)
    state.build().setToPlayer(to)
    if (from.playbackState != STATE_IDLE) to.prepare()
    from.stop()
    setPlayer(to)
  }

  private fun isCurrentItemLive(player: MediaPlayer) =
    player.currentMediaItem?.let { resolutions[it]?.live } == true

  override fun getState(): State {
    val state = super.getState()
    if (restamped.isEmpty()) return state
    val playlist =
      state.playlist.map { data ->
        restamped[data.mediaItem.mediaId]?.let { data.buildUpon().setMediaItem(it).build() } ?: data
      }
    return state.buildUpon().setPlaylist(playlist).build()
  }

  override fun handleSetMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> =
    inTurn(mediaItems) {
      restamped.clear()
      super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

  override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> =
    inTurn(mediaItems) { super.handleAddMediaItems(index, mediaItems) }

  override fun handleReplaceMediaItems(
    fromIndex: Int,
    toIndex: Int,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<*> {
    if (isRemote && queued == null && isMetadataOnly(fromIndex, toIndex, mediaItems)) {
      mediaItems.forEach { restamped[it.mediaId] = it }
      return Futures.immediateVoidFuture()
    }
    return inTurn(mediaItems) { super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems) }
  }

  override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = inTurn {
    super.handleRemoveMediaItems(fromIndex, toIndex)
  }

  override fun handleMoveMediaItems(
    fromIndex: Int,
    toIndex: Int,
    newIndex: Int,
  ): ListenableFuture<*> = inTurn { super.handleMoveMediaItems(fromIndex, toIndex, newIndex) }

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
      scope.launch {
        try {
          previous?.join()
          resolveAll(pending)
          apply()
        } finally {
          done.set(Unit)
        }
      }
    queued = job
    job.invokeOnCompletion { if (queued === job) queued = null }
    return done
  }

  private fun isMetadataOnly(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) =
    toIndex - fromIndex == mediaItems.size &&
      mediaItems.withIndex().all { (offset, item) ->
        val replaced = remote.player.getMediaItemAt(fromIndex + offset)
        replaced.mediaId == item.mediaId &&
          replaced.localConfiguration?.uri == item.localConfiguration?.uri
      }

  /**
   * Brings playback back to the local player if casting, and lets go of the remote. The local
   * player stays with its owner, unreleased.
   */
  override fun handleRelease(): ListenableFuture<*> {
    handover?.cancel()
    queued?.cancel()
    remote.setSessionListener(null)
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
