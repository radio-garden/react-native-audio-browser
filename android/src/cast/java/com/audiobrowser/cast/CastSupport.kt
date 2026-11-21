package com.audiobrowser.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
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
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.PendingResult
import java.util.concurrent.TimeUnit
import timber.log.Timber

private fun receiverOrDefault(receiverApplicationId: String?) =
  receiverApplicationId ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

// Cast takes its receiver once per process; a later one waits for a restart.
private var initializedReceiver: String? = null

/**
 * The MediaRouter control category of the routes that can run the receiver: the one Cast started
 * with, else [receiverApplicationId].
 */
internal fun castRouteCategory(receiverApplicationId: String?): String? =
  CastMediaControlIntent.categoryForCast(
    initializedReceiver ?: receiverOrDefault(receiverApplicationId)
  )

/**
 * Starts Cast with the receiver (the Default Media Receiver when null) and builds its remote player,
 * or returns null without Google Play services. Only the first receiver of the process takes effect.
 */
internal fun createCastRemote(context: Context, receiverApplicationId: String?): RemotePlayback? {
  val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
  if (availability != ConnectionResult.SUCCESS) {
    Timber.w("Cast: Google Play services unavailable (%d), playing locally only", availability)
    return null
  }
  val receiver = receiverOrDefault(receiverApplicationId)
  val initialized = initializedReceiver
  if (initialized == null) {
    Cast.getSingletonInstance(context)
      .initialize(CastParams.Builder().setReceiverApplicationId(receiver).build())
    initializedReceiver = receiver
  } else if (initialized != receiver) {
    Timber.w("Cast: the receiver %s takes effect after a restart; %s stays", receiver, initialized)
  }
  val converter = SendingMediaItemConverter()
  val player = RemoteCastPlayer.Builder(context).setMediaItemConverter(converter).build()
  return SdkCastRemote(Cast.getSingletonInstance(context), player, converter::sends) {
    onRegained ->
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onRegained()
      }
    connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
    return@SdkCastRemote { connectivity.unregisterNetworkCallback(callback) }
  }
}

internal fun interface NetworkWatch {
  /** Calls [onRegained] on the main thread as each default network arrives; returns the stop. */
  fun start(onRegained: () -> Unit): () -> Unit
}

internal class SdkCastRemote(
  private val cast: Cast,
  override val player: RemoteCastPlayer,
  /** How many loads [player] has sent. */
  sends: () -> Long,
  networkWatch: NetworkWatch,
) : RemotePlayback {
  private var listener: RemotePlayback.Listener? = null
  private var client: RemoteMediaClient? = null

  /**
   * Kept through a suspension, while the receiver plays on out of reach: [player] lets go of its
   * session then as it does at an end, so only the SDK's own end or failed resume or start clears
   * this.
   */
  private var hasSession = player.isCastSessionAvailable

  private val ends = CastEnds(sends)

  /** The status request [askReceiver] waits on the answer to. */
  private var asking: PendingResult<RemoteMediaClient.MediaChannelResult>? = null

  private val statusCallback =
    object : RemoteMediaClient.Callback() {
      override fun onStatusUpdated() {
        val status = client?.mediaStatus ?: return
        if (status.playerState == MediaStatus.PLAYER_STATE_PLAYING) listener?.onPlaying()
        when (ends.report(status.currentItemId, status.playerState, status.idleReason)) {
          CastEnds.Report.ENDED -> listener?.onEnded()
          CastEnds.Report.FAILED ->
            listener?.onFailed(
              PlaybackException(
                "The Cast receiver failed to play the media",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
              )
            )
          null -> Unit
        }
      }
    }

  private val sessions =
    object : SessionManagerListener<CastSession> {
      override fun onSessionStarted(session: CastSession, sessionId: String) {
        ends.reset()
        watch(session)
      }

      override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = watch(session)

      override fun onSessionEnded(session: CastSession, error: Int) = letGo()

      override fun onSessionStarting(session: CastSession) = Unit

      /** Also how the SDK gives up on the held session after a loss it reported nothing of. */
      override fun onSessionStartFailed(session: CastSession, error: Int) {
        if (hasSession) letGo()
      }

      override fun onSessionEnding(session: CastSession) = sessionGone()

      override fun onSessionResuming(session: CastSession, sessionId: String) = Unit

      override fun onSessionResumeFailed(session: CastSession, error: Int) = letGo()

      override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

  private val stopWatchingNetwork = networkWatch.start(::askReceiver)

  init {
    cast.addSessionManagerListener(sessions)
    watch(cast.currentCastSession)
    player.setSessionAvailabilityListener(
      object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() {
          hasSession = true
          listener?.onSessionChanged()
        }

        override fun onCastSessionUnavailable() {
          listener?.onSessionChanged()
        }
      }
    )
  }

  /**
   * Gives up the session, here and in the SDK, if its receiver doesn't answer a status request: the
   * SDK can reconnect after a loss without telling the session, which then reports itself connected
   * while nothing reaches the receiver.
   */
  private fun askReceiver() {
    val asked = client ?: return
    if (!hasSession || isSuspended || asking != null) return
    val request = asked.requestStatus()
    asking = request
    request.setResultCallback(
      { result ->
        // A resume, as any session change, clears [asking]: this answer is then the old connection's.
        if (asking !== request) return@setResultCallback
        asking = null
        if (result.status.statusCode != CommonStatusCodes.TIMEOUT || isSuspended) {
          return@setResultCallback
        }
        letGo()
        cast.endCurrentSession(false)
      },
      RECEIVER_ANSWER_TIMEOUT_MS,
      TimeUnit.MILLISECONDS,
    )
  }

  private fun letGo() {
    watch(null)
    sessionGone()
  }

  private fun sessionGone() {
    if (!hasSession) return
    hasSession = false
    listener?.onSessionChanged()
  }

  private fun watch(session: CastSession?) {
    asking = null
    client?.unregisterCallback(statusCallback)
    client = session?.remoteMediaClient
    client?.registerCallback(statusCallback)
  }

  override val isSessionAvailable: Boolean
    get() = hasSession

  /** [player] has no client between letting go of the session and taking it up again. */
  override val isSuspended: Boolean
    get() = hasSession && !player.isCastSessionAvailable

  override fun setListener(listener: RemotePlayback.Listener?) {
    this.listener = listener
  }

  override fun release() {
    stopWatchingNetwork()
    cast.removeSessionManagerListener(sessions)
    watch(null)
    player.release()
  }
}

/** A receiver in reach answers a status request in well under a second. */
private const val RECEIVER_ANSWER_TIMEOUT_MS = 10_000L

/**
 * The end or failure of each load a receiver's media statuses report, once. A load is told by its
 * queue item id, which a failed load's status carries too; a status without one counts as the last
 * item seen. A report for the item the receiver held when the last load went is that earlier
 * load's, and is dropped.
 */
internal class CastEnds(private val sends: () -> Long) {
  enum class Report {
    ENDED,
    FAILED,
  }

  private var lastItemId = MediaQueueItem.INVALID_ITEM_ID
  private var sendsSeen = 0L
  private var heldAtSend = MediaQueueItem.INVALID_ITEM_ID
  private var reported: Pair<Int, Int>? = null

  fun report(itemId: Int, playerState: Int, idleReason: Int): Report? {
    val sent = sends()
    if (sent != sendsSeen) {
      sendsSeen = sent
      heldAtSend = lastItemId
    }
    if (itemId != MediaQueueItem.INVALID_ITEM_ID) lastItemId = itemId
    val report =
      when (idleReason.takeIf { playerState == MediaStatus.PLAYER_STATE_IDLE }) {
        MediaStatus.IDLE_REASON_FINISHED -> Report.ENDED
        MediaStatus.IDLE_REASON_ERROR -> Report.FAILED
        else -> return null
      }
    if (heldAtSend != MediaQueueItem.INVALID_ITEM_ID && lastItemId == heldAtSend) return null
    val key = lastItemId to idleReason
    if (key == reported) return null
    reported = key
    return report
  }

  /** A new receiver session numbers its items afresh. */
  fun reset() {
    lastItemId = MediaQueueItem.INVALID_ITEM_ID
    heldAtSend = MediaQueueItem.INVALID_ITEM_ID
    reported = null
  }
}

/**
 * Sends each item with a content id of its own: Media3's timeline tracker keys what it sent by
 * content id and forgets an id when an item with it leaves the receiver's queue, so an item sent
 * again would be forgotten with the last and show as an empty item.
 */
internal class SendingMediaItemConverter : MediaItemConverter {
  private val default = DefaultMediaItemConverter()
  var sends = 0L
    private set

  override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
    val contentId = "${mediaItem.mediaId}$SEND_SEPARATOR${sends++}"
    return default.toMediaQueueItem(mediaItem.buildUpon().setMediaId(contentId).build())
  }

  /**
   * [MediaItem.EMPTY] for an item the receiver reports without media (not yet loaded), which the
   * default converter throws on; the timeline tracker keeps an empty item's earlier data.
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
