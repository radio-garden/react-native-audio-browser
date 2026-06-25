package com.audiobrowser.cast

import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.browser.resolveMediaUrl
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.margelo.nitro.audiobrowser.MediaResolveTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/**
 * Bounded, reactive re-signing of stale Cast queue URLs (see ADR 0003). Signed stream URLs
 * expire over multi-hour live sessions, so an item deep in the mirrored queue can be dead by the
 * time the receiver reaches it. When the receiver hits a load error, we JIT re-resolve *that one
 * item* via [BrowserManager.resolveMediaUrl] with `target=cast` and update it on the receiver with
 * [RemoteMediaClient.queueUpdateItems].
 *
 * Attempts are capped per item id (mirroring `StuckRecoveryPolicy`'s philosophy) so a genuinely
 * dead stream surfaces a real error instead of looping forever.
 */
class CastReSign(
  private val browserManager: () -> BrowserManager?,
  private val scope: CoroutineScope,
  maxAttemptsPerItem: Int = 3,
) {
  // Pure per-item cap + in-flight dedup bookkeeping (testable, in main). This class keeps only the
  // SDK glue (resolve + queueUpdateItems).
  private val budget = CastReSignBudget(maxAttemptsPerItem)

  /** Resets the per-item attempt budget — call on a fresh queue load / new Cast session. */
  fun reset() {
    budget.reset()
  }

  /**
   * Attempts a re-sign of the receiver's currently-loading item. Returns true if a re-sign was
   * dispatched OR one is already in flight for this item (caller should NOT surface a terminal
   * error yet), false if the budget is exhausted or the item can't be re-resolved (caller surfaces
   * the real error). Call on the main thread (RemoteMediaClient callback thread) — the budget is
   * not synchronized.
   */
  fun onLoadError(remoteMediaClient: RemoteMediaClient): Boolean {
    val item = remoteMediaClient.currentItem ?: return false
    val track = CastTrackCodec.fromCustomData(item.media?.customData) ?: return false
    // Re-resolution needs a raw src — bail (do NOT touch the budget) when there isn't one.
    val originalUrl = track.src ?: return false
    // Capture the failing item id now, at detection — not inside the coroutine, where currentItem
    // may have moved on.
    val key = track.src ?: track.url ?: track.id ?: return false

    when (budget.shouldAttempt(key)) {
      CastReSignBudget.Decision.IN_FLIGHT -> return true
      CastReSignBudget.Decision.EXHAUSTED -> {
        Timber.w("Cast re-sign budget exhausted for item key=$key — surfacing error")
        return false
      }
      CastReSignBudget.Decision.ATTEMPT -> Unit
    }
    val manager = browserManager() ?: return false

    scope.launch {
      try {
        val resolved = manager.resolveMediaUrl(originalUrl, MediaResolveTarget.CAST)
        val freshUrl = resolved?.path ?: originalUrl
        val updated = reSignedQueueItem(item, freshUrl)
        remoteMediaClient.queueUpdateItems(arrayOf(updated), JSONObject())
        Timber.d("Cast re-sign dispatched for item key=$key")
      } catch (e: Exception) {
        Timber.e(e, "Cast re-sign failed for item key=$key")
      } finally {
        budget.markDone(key)
      }
    }
    return true
  }

  /** Rebuilds a queue item with a fresh content id (the re-resolved, self-contained URL). */
  private fun reSignedQueueItem(item: MediaQueueItem, freshUrl: String): MediaQueueItem {
    val oldInfo = item.media
    val newInfo =
      MediaInfo.Builder(freshUrl)
        // Preserve the original stream type — for a live stream this stays STREAM_TYPE_LIVE so the
        // re-signed item isn't downgraded to a finite buffered file.
        .setStreamType(oldInfo?.streamType ?: MediaInfo.STREAM_TYPE_BUFFERED)
        // Concrete MIME from the fresh URL (shared helper); never the bogus "audio/*".
        .setContentType(oldInfo?.contentType ?: CastMediaItemConverter.contentTypeFor(freshUrl))
        .setMetadata(oldInfo?.metadata)
        .setCustomData(oldInfo?.customData)
        .build()
    // MediaQueueItem.Builder has no setMedia(): rebuild from the fresh MediaInfo and carry over the
    // item-level fields. Skip startTime when unset (NaN) — Builder.setStartTime(NaN) throws.
    return MediaQueueItem.Builder(newInfo)
      .setAutoplay(item.autoplay)
      .setPreloadTime(item.preloadTime)
      .apply {
        item.customData?.let { setCustomData(it) }
        if (!item.startTime.isNaN()) setStartTime(item.startTime)
      }
      .build()
  }
}
