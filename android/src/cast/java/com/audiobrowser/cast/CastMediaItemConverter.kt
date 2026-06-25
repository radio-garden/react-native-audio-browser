package com.audiobrowser.cast

import android.net.Uri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.audiobrowser.util.TrackFactory
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata as CastMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import com.margelo.nitro.audiobrowser.Track
import org.json.JSONObject
import timber.log.Timber

/**
 * Media3 [MediaItemConverter] for the Cast queue. Maps our [MediaItem] (queue item, whose URI has
 * already been `target:'cast'`-resolved by [CastSessionController]) to a [MediaQueueItem]/
 * [MediaInfo] the receiver fetches itself, and back for rehydration.
 *
 * The app's stable Track identity travels in the MediaInfo `customData` JSON (serialized by
 * [CastTrackCodec]), so a relaunch-while-casting can re-resolve full Tracks via `BrowserManager`
 * (see [CastReSign] and ADR 0003 "cold-relaunch-while-casting"). Title/subtitle/artwork are also
 * pushed into the Cast [CastMetadata] so the receiver's now-playing card renders without our app.
 */
@UnstableApi
class CastMediaItemConverter : MediaItemConverter {

  override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
    val track = mediaItem.localConfiguration?.tag as? Track
    val uri =
      mediaItem.localConfiguration?.uri
        ?: throw IllegalArgumentException("Cast MediaItem is missing a resolved media URI")

    val metadata =
      CastMetadata(CastMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
        mediaItem.mediaMetadata.title?.let { putString(CastMetadata.KEY_TITLE, it.toString()) }
        (mediaItem.mediaMetadata.subtitle ?: mediaItem.mediaMetadata.artist)?.let {
          putString(CastMetadata.KEY_SUBTITLE, it.toString())
        }
        mediaItem.mediaMetadata.albumTitle?.let {
          putString(CastMetadata.KEY_ALBUM_TITLE, it.toString())
        }
        mediaItem.mediaMetadata.artworkUri?.let { addImage(WebImage(it)) }
      }

    val customData =
      JSONObject().apply {
        track?.let { put(CastTrackCodec.KEY_TRACK, CastTrackCodec.toJson(it)) }
      }

    // Treat an unset `live` flag as live (only an explicit false opts into BUFFERED) so an unbounded
    // stream is never mistaken for a finite file — kept in sync with the iOS CastMediaItemConverter.
    val isLive = track?.live != false
    val mediaInfo =
      MediaInfo.Builder(uri.toString())
        // Live streams dominate, but the queue may carry on-demand items too: honor track.live so the
        // receiver renders a live UI (no scrubber/duration) and doesn't treat the unbounded stream
        // as a finite buffered file (a common source of false IDLE/error → spurious re-signs).
        .setStreamType(
          if (isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED
        )
        .setContentType(contentTypeFor(uri.toString()))
        .setMetadata(metadata)
        .setCustomData(customData)
        // Don't set a bogus duration for LIVE — its length is unknown/unbounded.
        .build()

    return MediaQueueItem.Builder(mediaInfo).build()
  }

  override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
    val mediaInfo = mediaQueueItem.media
    val contentUri = mediaInfo?.contentId?.let { Uri.parse(it) } ?: Uri.EMPTY

    // Rehydrate the app Track from customData when present so the queue keeps its stable identity
    // across a process relaunch; fall back to a thin Track built from the Cast metadata otherwise.
    val track =
      CastTrackCodec.fromCustomData(mediaInfo?.customData) ?: trackFromCastMetadata(mediaInfo)

    val media3Metadata =
      MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.subtitle ?: track.artist)
        .setAlbumTitle(track.album)
        .apply { track.artwork?.let { setArtworkUri(Uri.parse(it)) } }
        .build()

    // Set the URI to the RAW Track src (re-resolvable), not the receiver's signed contentId, which
    // may be stale (multi-hour live sessions). When this rehydrated item is replayed — e.g. handed
    // back to the local player on session end, or re-signed for the receiver — resolution re-runs
    // through BrowserManager (TransformingDataSource locally, CastReSign on the receiver) from the
    // raw src. Falls back to the contentId only when the Track carries no src.
    val playbackUri = track.src?.let { Uri.parse(it) } ?: contentUri

    return MediaItem.Builder()
      .setMediaId(track.url ?: track.src ?: contentUri.toString())
      .setUri(playbackUri)
      .setMediaMetadata(media3Metadata)
      .setTag(track)
      .build()
  }

  private fun trackFromCastMetadata(mediaInfo: MediaInfo?): Track {
    val md = mediaInfo?.metadata
    val title = md?.getString(CastMetadata.KEY_TITLE) ?: ""
    val subtitle = md?.getString(CastMetadata.KEY_SUBTITLE)
    val artwork = md?.images?.firstOrNull()?.url?.toString()
    return CastTrackCodec.blankTrack()
      .copy(src = mediaInfo?.contentId, title = title, subtitle = subtitle, artwork = artwork)
  }

  companion object {
    /** Convenience: build a Cast-queue MediaItem from a Track + its resolved media URI. */
    fun mediaItemFor(track: Track, resolvedUri: Uri): MediaItem {
      // Reuse the canonical metadata mapping, then force the resolved (target:'cast') URI + tag.
      val base = TrackFactory.toMedia3(track)
      return base.buildUpon().setUri(resolvedUri).setTag(track).build()
    }

    /**
     * Infers a concrete MIME content type for the Cast receiver from the resolved URL's extension.
     * A bare wildcard `audio` content type is not a real MIME and confuses some receivers, so map
     * the common stream containers
     * explicitly; default to `audio/mpeg`. Query string is stripped before matching. Shared by the
     * converter and [CastReSign] so a re-signed item keeps a consistent content type.
     */
    fun contentTypeFor(url: String): String {
      val path = url.substringBefore('?').substringBefore('#').lowercase()
      return when {
        path.endsWith(".m3u8") || path.endsWith(".m3u") -> "application/x-mpegURL"
        path.endsWith(".mpd") -> "application/dash+xml"
        path.endsWith(".aac") -> "audio/aac"
        path.endsWith(".ogg") || path.endsWith(".oga") -> "audio/ogg"
        path.endsWith(".flac") -> "audio/flac"
        path.endsWith(".wav") -> "audio/wav"
        path.endsWith(".mp4") || path.endsWith(".m4a") -> "audio/mp4"
        else -> "audio/mpeg"
      }
    }
  }
}
