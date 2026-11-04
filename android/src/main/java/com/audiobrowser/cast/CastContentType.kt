package com.audiobrowser.cast

import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util

/**
 * The MIME type the receiver is told for [url]: [declared] when the item has one, else inferred
 * from the URL. A stream without a recognised extension is sent as `audio/mpeg`, since the Default
 * Media Receiver skips an item with no type and sniffs the codec of one it plays.
 */
internal fun castContentType(url: String, declared: String?): String {
  if (declared != null) return declared
  when (Util.inferContentType(url.toUri())) {
    C.CONTENT_TYPE_HLS -> return MimeTypes.APPLICATION_M3U8
    C.CONTENT_TYPE_DASH -> return MimeTypes.APPLICATION_MPD
  }
  val extension = MimeTypeMap.getFileExtensionFromUrl(url)
  return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.takeIf {
    MimeTypes.isAudio(it)
  } ?: MimeTypes.AUDIO_MPEG
}
