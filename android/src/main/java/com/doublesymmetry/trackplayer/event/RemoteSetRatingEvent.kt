package com.doublesymmetry.trackplayer.event

import com.doublesymmetry.trackplayer.model.RatingType
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Event data for remote set rating command. */
data class RemoteSetRatingEvent(
  /** The rating type. */
  val rating: RatingType
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply { putString("rating", rating.string) }
  }
}
