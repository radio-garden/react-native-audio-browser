package com.audiobrowser.player

import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.io.IOException

/**
 * A progressive source whose fatal load error, such as a connection's end that
 * [RetryLoadErrorHandlingPolicy] does not retry in place, surfaces only once the audio the
 * connection delivered has been read. media3 surfaces a load error whenever it is buffering or a
 * renderer is not ready, and before it has handled the period's preparation, so a burst that
 * arrives with its close would otherwise never play. Once the connection has ended, the period has
 * nothing more to load: playback starts on what it holds, short of the play buffer.
 */
class ConnectionEndSource(source: MediaSource) : WrappingMediaSource(source) {

  override fun createPeriod(
    id: MediaSource.MediaPeriodId,
    allocator: Allocator,
    startPositionUs: Long,
  ): MediaPeriod = Period(super.createPeriod(id, allocator, startPositionUs))

  override fun releasePeriod(mediaPeriod: MediaPeriod) {
    super.releasePeriod((mediaPeriod as Period).period)
  }

  private class Period(val period: MediaPeriod) : MediaPeriod {
    /** Read on the playback thread only, where media3 calls back. */
    private var prepared = false

    private val connectionEnded: Boolean
      get() =
        try {
          period.maybeThrowPrepareError()
          false
        } catch (_: IOException) {
          true
        }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
      period.prepare(
        object : MediaPeriod.Callback {
          override fun onPrepared(mediaPeriod: MediaPeriod) {
            prepared = true
            callback.onPrepared(this@Period)
          }

          override fun onContinueLoadingRequested(source: MediaPeriod) {
            callback.onContinueLoadingRequested(this@Period)
          }
        },
        positionUs,
      )
    }

    override fun maybeThrowPrepareError() {
      if (!prepared) period.maybeThrowPrepareError()
    }

    override fun getTrackGroups(): TrackGroupArray = period.trackGroups

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> =
      period.getStreamKeys(trackSelections)

    override fun selectTracks(
      selections: Array<out ExoTrackSelection?>,
      mayRetainStreamFlags: BooleanArray,
      streams: Array<SampleStream?>,
      streamResetFlags: BooleanArray,
      positionUs: Long,
    ): Long {
      val held = Array(streams.size) { streams[it] as Stream? }
      val inner = Array(streams.size) { held[it]?.stream }
      val enabledAtUs =
        period.selectTracks(selections, mayRetainStreamFlags, inner, streamResetFlags, positionUs)
      for (i in streams.indices) {
        val stream = inner[i]
        held[i] =
          when {
            stream == null -> null
            held[i]?.stream === stream -> held[i]
            else -> Stream(stream)
          }
        streams[i] = held[i]
      }
      return enabledAtUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) =
      period.discardBuffer(positionUs, toKeyframe)

    override fun readDiscontinuity(): Long = period.readDiscontinuity()

    override fun setUsesStreamPrerollFlags() = period.setUsesStreamPrerollFlags()

    override fun seekToUs(positionUs: Long): Long = period.seekToUs(positionUs)

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
      period.getAdjustedSeekPositionUs(positionUs, seekParameters)

    override fun getBufferedPositionUs(): Long = period.bufferedPositionUs

    override fun getNextLoadPositionUs(): Long =
      if (connectionEnded) C.TIME_END_OF_SOURCE else period.nextLoadPositionUs

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean =
      period.continueLoading(loadingInfo)

    override fun isLoading(): Boolean = period.isLoading

    override fun reevaluateBuffer(positionUs: Long) = period.reevaluateBuffer(positionUs)

    override fun setEndPositionUs(endPositionUs: Long): Long =
      period.setEndPositionUs(endPositionUs)
  }

  private class Stream(val stream: SampleStream) : SampleStream {
    override fun isReady(): Boolean = stream.isReady

    override fun maybeThrowError() {
      if (!stream.isReady) stream.maybeThrowError()
    }

    override fun readData(
      formatHolder: FormatHolder,
      buffer: DecoderInputBuffer,
      readFlags: Int,
    ): Int = stream.readData(formatHolder, buffer, readFlags)

    override fun skipData(positionUs: Long): Int = stream.skipData(positionUs)

    override fun getFlags(): Int = stream.flags
  }
}
