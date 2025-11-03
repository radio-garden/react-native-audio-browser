package com.doublesymmetry.trackplayer.event

import com.doublesymmetry.trackplayer.model.RatingType
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent as NitroRemoteSetRatingEvent
import com.margelo.nitro.audiobrowser.Variant_String_Double

/** Event data for remote set rating command. */
data class RemoteSetRatingEvent(
  /** The rating type. */
  val rating: RatingType
) {
  fun toBridge(): WritableMap {
    return Arguments.createMap().apply { putString("rating", rating.string) }
  }

  fun toNitro(): NitroRemoteSetRatingEvent {
    return NitroRemoteSetRatingEvent(rating = Variant_String_Double.create(rating.string))
  }
}
