package com.audiobrowser.cast

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.cast.Cast
import androidx.media3.cast.CastParams
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import timber.log.Timber

private const val RECEIVER_APPLICATION_ID =
  CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

/** The MediaRouter control category of the receiver's routes. */
internal val castRouteCategory: String? =
  CastMediaControlIntent.categoryForCast(RECEIVER_APPLICATION_ID)

/**
 * Starts Cast with the Default Media Receiver and builds its remote player, or returns null on a
 * device without Google Play services.
 */
internal fun createCastRemote(context: Context, resolutions: CastMediaResolutions): CastRemote? {
  val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
  if (availability != ConnectionResult.SUCCESS) {
    Timber.w("Cast: Google Play services unavailable (%d), playing locally only", availability)
    return null
  }
  Cast.getSingletonInstance(context)
    .initialize(CastParams.Builder().setReceiverApplicationId(RECEIVER_APPLICATION_ID).build())
  val player =
    RemoteCastPlayer.Builder(context)
      .setMediaItemConverter(ResolvingMediaItemConverter(resolutions))
      .build()
  return SdkCastRemote(player)
}

private class SdkCastRemote(override val player: RemoteCastPlayer) : CastRemote {
  override val isSessionAvailable: Boolean
    get() = player.isCastSessionAvailable

  override fun setSessionListener(listener: (() -> Unit)?) {
    player.setSessionAvailabilityListener(
      listener?.let {
        object : SessionAvailabilityListener {
          override fun onCastSessionAvailable() = it()

          override fun onCastSessionUnavailable() = it()
        }
      }
    )
  }

  override fun release() = player.release()
}

/**
 * Sends each item as its [CastMedia]: the resolved URL and MIME type, artwork the receiver can
 * fetch, and live items as live streams. An item without one goes as it is.
 */
private class ResolvingMediaItemConverter(private val resolutions: CastMediaResolutions) :
  MediaItemConverter {
  private val default = DefaultMediaItemConverter()

  override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
    val media = resolutions[mediaItem] ?: return default.toMediaQueueItem(mediaItem)
    val artwork =
      media.artworkUrl?.toUri()
        ?: mediaItem.mediaMetadata.artworkUri?.takeIf { it.scheme == "http" || it.scheme == "https" }
    val metadata =
      mediaItem.mediaMetadata
        .buildUpon()
        .setArtworkUri(artwork)
        .apply { if (media.live) setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION) }
        .build()
    val item =
      mediaItem
        .buildUpon()
        .setUri(media.url)
        .setMimeType(media.contentType)
        .setMediaMetadata(metadata)
        .apply { if (media.live) setLiveConfiguration(LIVE) }
        .build()
    return default.toMediaQueueItem(item)
  }

  override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
    default.toMediaItem(mediaQueueItem)

  private companion object {
    // Any configuration other than UNSET makes the converter send STREAM_TYPE_LIVE; this one also
    // asks for no speed adjustment.
    val LIVE: MediaItem.LiveConfiguration =
      MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()
  }
}
