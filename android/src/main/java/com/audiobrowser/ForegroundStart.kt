package com.audiobrowser

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.core.os.bundleOf
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Runs [play] for a start sent with `startForegroundService`, holding the service in the foreground
 * meanwhile ([holdForeground]). When [play] returns false the placeholder is released, and the
 * service stops unless [hasMedia] says the player still has something to keep it for. [play]
 * reports failure by returning false, not by throwing.
 */
internal fun <S> S.playInForeground(
  scope: CoroutineScope,
  startId: Int,
  hasMedia: () -> Boolean,
  play: suspend () -> Boolean,
) where S : MediaSessionService, S : MediaSessionService.Listener {
  holdForeground()
  scope.launch {
    if (play()) return@launch
    releasePlaceholder()
    if (!hasMedia()) stopSelf(startId)
  }
}

/**
 * Satisfies a `startForegroundService` start, which Android kills the app for unless the service
 * calls `startForeground()` within seconds, by going foreground at once under Media3's notification
 * id and channel (valid while the library sets no notification provider of its own): with the media
 * notification already showing there, else with a placeholder. Media3's next notification update
 * replaces or removes either; [releasePlaceholder] removes the placeholder when no update will
 * come. Holds nothing below API 26, when the service is already foreground (below API 29, where
 * that can't be read, it holds Media3's notification again), or when Android refuses the start,
 * which is reported to [MediaSessionService.Listener.onForegroundServiceStartNotAllowedException].
 */
internal fun <S> S.holdForeground()
  where S : MediaSessionService, S : MediaSessionService.Listener {
  if (Build.VERSION.SDK_INT < 26 || isForeground()) return
  try {
    startForeground(mediaNotification()?.notification ?: placeholder())
  } catch (e: IllegalStateException) {
    if (Build.VERSION.SDK_INT < 31 || e !is ForegroundServiceStartNotAllowedException) throw e
    Timber.w(e, "Foreground start refused")
    onForegroundServiceStartNotAllowedException()
  }
}

/**
 * Removes the placeholder [holdForeground] posted, if it is still up, and has Media3 post the
 * notification the player warrants. For a start whose play request changed nothing in the player:
 * Media3 tracks the session only once a controller has connected, so its update alone may never
 * come.
 */
@OptIn(UnstableApi::class)
internal fun MediaSessionService.releasePlaceholder() {
  if (mediaNotification()?.notification?.extras?.getBoolean(PLACEHOLDER_EXTRA) == true) {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
  }
  triggerNotificationUpdate()
}

/**
 * Whether [this] is a press `MediaButtonReceiver.onReceive` starts the service in the foreground
 * for.
 */
internal fun Intent.isForegroundStartKey(): Boolean =
  action == Intent.ACTION_MEDIA_BUTTON &&
    IntentCompat.getParcelableExtra(this, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)?.keyCode in
      PLAY_KEYS

private val PLAY_KEYS =
  setOf(
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_HEADSETHOOK,
  )

private const val PLACEHOLDER_EXTRA = "com.audiobrowser.placeholder"

private fun MediaSessionService.mediaNotification() =
  getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull {
    it.id == DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
  }

private fun MediaSessionService.isForeground(): Boolean =
  Build.VERSION.SDK_INT >= 29 && foregroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE

private fun MediaSessionService.startForeground(notification: Notification) =
  ServiceCompat.startForeground(
    this,
    DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
    notification,
    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
  )

@OptIn(UnstableApi::class)
private fun MediaSessionService.placeholder(): Notification {
  NotificationManagerCompat.from(this)
    .createNotificationChannel(
      NotificationChannelCompat.Builder(
          DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
          NotificationManagerCompat.IMPORTANCE_LOW,
        )
        .setName(getString(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID))
        .build()
    )
  return NotificationCompat.Builder(this, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
    .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
    .setCategory(Notification.CATEGORY_TRANSPORT)
    .addExtras(bundleOf(PLACEHOLDER_EXTRA to true))
    .build()
}
