package com.audiobrowser.cast

import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.util.Util
import java.util.concurrent.ConcurrentHashMap

/**
 * The Cast session's player, from the `cast` source set when the library is built with
 * `AudioBrowser_castEnabled`. Kept apart from the Cast SDK's types so the rest of the library
 * compiles without them.
 */
internal interface CastRemote {
  /** Plays on the receiver; empty and idle while no session is up. */
  val player: MediaPlayer

  val isSessionAvailable: Boolean

  /** Calls [listener] on the main thread whenever [isSessionAvailable] changes. */
  fun setSessionListener(listener: (() -> Unit)?)

  /**
   * Calls [listener] on the main thread when the receiver fails to play what it was sent: [player]
   * reports no error of its own.
   */
  fun setErrorListener(listener: ((PlaybackException) -> Unit)?)

  /** Ends the session on this side; the receiver stops or plays on as it was told. */
  fun release()
}

/**
 * What the receiver loads for a media item: it fetches [url] and [artworkUrl] itself, without
 * request headers. A [live] item loads as a live stream, at its live edge.
 */
internal data class CastMedia(
  val url: String,
  val contentType: String,
  val artworkUrl: String?,
  val live: Boolean,
)

/**
 * The [CastMedia] of each media item handed to the receiver. The receiver's media item converter
 * runs synchronously on the main thread, and resolving a URL can need the JS thread, so items are
 * resolved into here first and the converter only reads.
 */
internal class CastMediaResolutions {
  private val byKey = ConcurrentHashMap<String, CastMedia>()

  operator fun get(item: MediaItem): CastMedia? = byKey[key(item)]

  operator fun contains(item: MediaItem): Boolean = byKey.containsKey(key(item))

  operator fun set(item: MediaItem, media: CastMedia) {
    byKey[key(item)] = media
  }

  fun clear() = byKey.clear()

  private fun key(item: MediaItem) = "${item.mediaId}\u0000${item.localConfiguration?.uri}"
}

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
