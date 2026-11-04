package com.audiobrowser.cast

import android.content.Context
import com.audiobrowser.remote.RemotePlayback
import timber.log.Timber

/** The library is built without Cast: no Cast routes. */
@Suppress("UNUSED_PARAMETER")
internal fun castRouteCategory(receiverApplicationId: String?): String? = null

/** The library is built without Cast: always null, with a warning. */
@Suppress("UNUSED_PARAMETER")
internal fun createCastRemote(context: Context, receiverApplicationId: String?): RemotePlayback? {
  Timber.w(
    "Cast: remoteOutputs.cast is on, but the library was built without AudioBrowser_castEnabled=true, so playback stays local"
  )
  return null
}
