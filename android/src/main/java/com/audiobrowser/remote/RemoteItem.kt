package com.audiobrowser.remote

import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util

/**
 * [item] as a receiver loads it: from [url], with [artworkUrl] or else the item's own artwork. The
 * receiver fetches both itself, without the app's request headers, so artwork that isn't http(s) is
 * dropped. A [live] item is marked so (see [isRemoteLive]) and loads at its live edge.
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
 * [declared] if set, else HLS or DASH inferred from [url], else the audio type of its extension,
 * else `audio/mpeg`: a Cast receiver skips an item with no type, and sniffs the codec of one it
 * plays.
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

/** Whether [remoteItemOf] built this item as live. */
internal val MediaItem.isRemoteLive: Boolean
  get() = liveConfiguration != MediaItem.LiveConfiguration.UNSET

// Speed fixed at 1, so the receiver never speeds up or slows down to hold the live edge.
private val LIVE: MediaItem.LiveConfiguration =
  MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()
