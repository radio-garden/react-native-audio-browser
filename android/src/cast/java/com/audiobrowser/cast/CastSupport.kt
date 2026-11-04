package com.audiobrowser.cast

import android.content.Context
import androidx.media3.cast.Cast
import androidx.media3.cast.CastParams
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import com.audiobrowser.remote.RemotePlayback
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
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
internal fun createCastRemote(context: Context): RemotePlayback? {
  val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
  if (availability != ConnectionResult.SUCCESS) {
    Timber.w("Cast: Google Play services unavailable (%d), playing locally only", availability)
    return null
  }
  Cast.getSingletonInstance(context)
    .initialize(CastParams.Builder().setReceiverApplicationId(RECEIVER_APPLICATION_ID).build())
  val player =
    RemoteCastPlayer.Builder(context)
      .setMediaItemConverter(SendingMediaItemConverter())
      .build()
  return SdkCastRemote(Cast.getSingletonInstance(context), player)
}

private class SdkCastRemote(private val cast: Cast, override val player: RemoteCastPlayer) :
  RemotePlayback {
  private var listener: RemotePlayback.Listener? = null
  private var client: RemoteMediaClient? = null

  // The receiver's last idle reason, so each end and each failure is reported once.
  private var idleReason = MediaStatus.IDLE_REASON_NONE

  private val statusCallback =
    object : RemoteMediaClient.Callback() {
      override fun onStatusUpdated() {
        val status = client?.mediaStatus ?: return
        val reason =
          if (status.playerState == MediaStatus.PLAYER_STATE_IDLE) status.idleReason
          else MediaStatus.IDLE_REASON_NONE
        if (reason == idleReason) return
        idleReason = reason
        when (reason) {
          MediaStatus.IDLE_REASON_FINISHED -> listener?.onEnded()
          MediaStatus.IDLE_REASON_ERROR ->
            listener?.onFailed(
              PlaybackException(
                "The Cast receiver failed to play the media",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
              )
            )
        }
      }
    }

  private val sessions =
    object : SessionManagerListener<CastSession> {
      override fun onSessionStarted(session: CastSession, sessionId: String) = watch(session)

      override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = watch(session)

      override fun onSessionEnded(session: CastSession, error: Int) = watch(null)

      override fun onSessionStarting(session: CastSession) = Unit

      override fun onSessionStartFailed(session: CastSession, error: Int) = Unit

      override fun onSessionEnding(session: CastSession) = Unit

      override fun onSessionResuming(session: CastSession, sessionId: String) = Unit

      override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit

      override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

  init {
    cast.addSessionManagerListener(sessions)
    watch(cast.currentCastSession)
  }

  private fun watch(session: CastSession?) {
    idleReason = MediaStatus.IDLE_REASON_NONE
    client?.unregisterCallback(statusCallback)
    client = session?.remoteMediaClient
    client?.registerCallback(statusCallback)
  }
  override val isSessionAvailable: Boolean
    get() = player.isCastSessionAvailable

  override fun setListener(listener: RemotePlayback.Listener?) {
    this.listener = listener
    player.setSessionAvailabilityListener(
      listener?.let {
        object : SessionAvailabilityListener {
          override fun onCastSessionAvailable() = it.onSessionChanged()

          override fun onCastSessionUnavailable() = it.onSessionChanged()
        }
      }
    )
  }

  override fun release() {
    cast.removeSessionManagerListener(sessions)
    watch(null)
    player.release()
  }
}

/**
 * Sends each item as it is, with a content id of its own: Media3's timeline tracker keys what it was
 * sent by content id, and forgets an id when an item with it leaves the receiver's queue, so a
 * station sent again would be forgotten with the last and show as an empty item.
 */
internal class SendingMediaItemConverter : MediaItemConverter {
  private val default = DefaultMediaItemConverter()
  private var sends = 0L

  override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem =
    default.toMediaQueueItem(
      mediaItem.buildUpon().setMediaId("${mediaItem.mediaId}$SEND_SEPARATOR${sends++}").build()
    )

  /**
   * The receiver's item as a media item, or [MediaItem.EMPTY] for one it reports without its media
   * (an item it hasn't loaded), which the default converter throws on. The timeline tracker keeps
   * an empty item's earlier data.
   */
  override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
    if (mediaQueueItem.media == null) return MediaItem.EMPTY
    val item =
      try {
        default.toMediaItem(mediaQueueItem)
      } catch (e: RuntimeException) {
        Timber.w(e, "Cast: could not read a receiver queue item")
        return MediaItem.EMPTY
      }
    return item.buildUpon().setMediaId(item.mediaId.substringBeforeLast(SEND_SEPARATOR)).build()
  }

  private companion object {
    const val SEND_SEPARATOR = "#cast-send-"
  }
}
