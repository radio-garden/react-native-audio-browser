package com.audiobrowser.player

import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Has the track's media URL resolved afresh on each reload of [source]: each time the engine
 * prepares it (a stop or an error releases it), and each period after the first, since the engine
 * keeps a queue item's source prepared while others play (a skip back to it, a repeat).
 */
class ResolvingOnReloadSource(
  source: MediaSource,
  private val urls: TransformingDataSource.Factory,
) : WrappingMediaSource(source) {

  /** Whether a period was created since the last preparation; read on the playback thread only. */
  private var periodCreated = false

  override fun prepareSourceInternal() {
    periodCreated = false
    urls.resolveOnNextMediaOpen = true
    super.prepareSourceInternal()
  }

  override fun createPeriod(
    id: MediaSource.MediaPeriodId,
    allocator: Allocator,
    startPositionUs: Long,
  ): MediaPeriod {
    // Not on the first: an HLS source has opened its playlist before it, which marking again would
    // resolve twice.
    if (periodCreated) urls.resolveOnNextMediaOpen = true
    periodCreated = true
    return super.createPeriod(id, allocator, startPositionUs)
  }
}
