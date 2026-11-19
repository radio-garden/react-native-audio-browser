package com.audiobrowser.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap

/**
 * Extracts a live track's progressive stream, which never ends: once its format is recognised, an
 * end of a stream with neither a length nor a duration (media3's own test for live) is a dropped
 * connection, thrown for the load error policy to reconnect. An end during the sniff stays the
 * unrecognised format error, and a stream with a length or a declared end ends there.
 */
class LiveStreamExtractor(extractor: Extractor) : ForwardingExtractor(extractor) {
  @Volatile private var durationKnown = false

  override fun init(output: ExtractorOutput) {
    super.init(
      object : ForwardingExtractorOutput(output) {
        override fun seekMap(seekMap: SeekMap) {
          durationKnown = seekMap.durationUs != C.TIME_UNSET
          super.seekMap(seekMap)
        }
      }
    )
  }

  override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
    val result = super.read(input, seekPosition)
    if (
      result == Extractor.RESULT_END_OF_INPUT &&
        input.length == C.LENGTH_UNSET.toLong() &&
        !durationKnown
    ) {
      throw DataSourceException(
        "The server ended a live stream",
        null,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      )
    }
    return result
  }

  /** Wraps every extractor [factory] makes. */
  class Factory(factory: ExtractorsFactory) : ForwardingExtractorsFactory(factory) {
    override fun createExtractors(): Array<Extractor> = wrap(super.createExtractors())

    override fun createExtractors(
      uri: Uri,
      responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = wrap(super.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
      Array(extractors.size) { LiveStreamExtractor(extractors[it]) }
  }
}
