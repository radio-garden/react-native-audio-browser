package com.doublesymmetry.trackplayer.model

import com.margelo.nitro.audiobrowser.TrackType

enum class MediaType {
  /** The default media type. Should be used for streams over HTTP or files */
  DEFAULT,

  /** The DASH media type for adaptive streams. Should be used with DASH manifests. */
  DASH,

  /** The HLS media type for adaptive streams. Should be used with HLS playlists. */
  HLS,

  /**
   * The SmoothStreaming media type for adaptive streams. Should be used with SmoothStreaming
   * manifests.
   */
  SMOOTH_STREAMING;

  fun toNitro(): TrackType {
    return when (this) {
      DEFAULT -> TrackType.DEFAULT
      DASH -> TrackType.DASH
      HLS -> TrackType.HLS
      SMOOTH_STREAMING -> TrackType.SMOOTHSTREAMING
    }
  }

  companion object {
    fun fromNitro(trackType: TrackType): MediaType {
      return when (trackType) {
        TrackType.DEFAULT -> DEFAULT
        TrackType.DASH -> DASH
        TrackType.HLS -> HLS
        TrackType.SMOOTHSTREAMING -> SMOOTH_STREAMING
      }
    }
  }
}
