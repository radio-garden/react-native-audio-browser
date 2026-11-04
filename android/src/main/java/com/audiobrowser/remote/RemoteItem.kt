package com.audiobrowser.remote

import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util

/**
 * [item] as a receiver loads it, which fetches its URL and artwork itself, without request headers:
 * [url], its MIME type, artwork only over http(s), and a [live] item as a live stream (its live
 * configuration set, a radio station in its metadata) that loads at its live edge.
 */
internal fun remoteItemOf(
  item: MediaItem,
  url: String,
  artworkUrl: String?,
  live: Boolean,
): MediaItem {
  val artwork =
    (artworkUrl?.toUri() ?: item.mediaMetadata.artworkUri)?.takeIf {
      it.scheme == "http" || it.scheme == "https"
    }
  val metadata =
    item.mediaMetadata
      .buildUpon()
      .setArtworkUri(artwork)
      .apply { if (live) setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION) }
      .build()
  return item
    .buildUpon()
    .setUri(url)
    .setMimeType(remoteMimeType(url, item.localConfiguration?.mimeType))
    .setMediaMetadata(metadata)
    .setLiveConfiguration(if (live) LIVE else MediaItem.LiveConfiguration.UNSET)
    .build()
}

/**
 * The MIME type a receiver is told for [url]: [declared] when the item has one, else inferred from
 * the URL. A stream without a recognised extension is `audio/mpeg`: a Cast receiver skips an item
 * with no type and sniffs the codec of one it plays.
 */
internal fun remoteMimeType(url: String, declared: String?): String {
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

/** Whether a receiver loads [item] as a live stream. */
internal val MediaItem.isRemoteLive: Boolean
  get() = liveConfiguration != MediaItem.LiveConfiguration.UNSET

// No speed adjustment at the live edge.
private val LIVE: MediaItem.LiveConfiguration =
  MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()
