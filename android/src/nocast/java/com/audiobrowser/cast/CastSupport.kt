package com.audiobrowser.cast

import android.content.Context
import timber.log.Timber

/** The library is built without Cast: no Cast routes. */
internal val castRouteCategory: String? = null

/** The library is built without Cast: always null, with a warning. */
@Suppress("UNUSED_PARAMETER")
internal fun createCastRemote(context: Context, resolutions: CastMediaResolutions): CastRemote? {
  Timber.w(
    "Cast: remoteOutputs.cast is on, but the library was built without AudioBrowser_castEnabled=true, so playback stays local"
  )
  return null
}
