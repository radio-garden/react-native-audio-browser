package com.audiobrowser

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Robolectric: startForeground/stopForeground/stopSelf only exist under the framework, and
// ShadowService records each.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class ForegroundStartTest {

  class SessionlessService : MediaSessionService(), MediaSessionService.Listener {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null
  }

  private val service = Robolectric.buildService(SessionlessService::class.java).create().get()
  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val mediaNotificationId = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID

  private fun notification(title: String): Notification =
    NotificationCompat.Builder(service, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
      .setContentTitle(title)
      .build()

  @Test
  fun `a cold search goes foreground before the search runs`() {
    var foregroundIdDuringSearch: Int? = null

    service.playInForeground(scope, startId = 1, hasMedia = { false }) {
      foregroundIdDuringSearch = shadowOf(service).lastForegroundNotificationId
      true
    }

    // Media3's id, so its own notification replaces the placeholder once playback starts.
    assertEquals(mediaNotificationId, foregroundIdDuringSearch)
    assertFalse(shadowOf(service).isForegroundStopped)
    assertEquals(NEVER_STOPPED, shadowOf(service).stopSelfId)
  }

  @Test
  fun `a cold search that finds nothing stops the service`() {
    service.playInForeground(scope, startId = 7, hasMedia = { false }) { false }

    assertTrue(shadowOf(service).isForegroundStopped)
    assertTrue(shadowOf(service).notificationShouldRemoved)
    assertEquals(7, shadowOf(service).stopSelfId)
  }

  @Test
  fun `a search that finds nothing leaves the service running while the player has media`() {
    service.playInForeground(scope, startId = 2, hasMedia = { true }) { false }

    assertTrue(shadowOf(service).isForegroundStopped)
    assertEquals(NEVER_STOPPED, shadowOf(service).stopSelfId)
  }

  @Test
  fun `a search while Media3's notification shows holds that notification and leaves it to Media3`() {
    val media = notification("Now playing")
    service.getSystemService(NotificationManager::class.java).notify(mediaNotificationId, media)

    service.playInForeground(scope, startId = 3, hasMedia = { true }) {
      assertEquals(
        "Now playing",
        shadowOf(service).lastForegroundNotification.extras.getString(Notification.EXTRA_TITLE),
      )
      false
    }

    assertFalse(shadowOf(service).isForegroundStopped)
  }

  @Test
  fun `a release while Media3's notification is in the foreground leaves it there`() {
    val media = notification("Now playing")
    ServiceCompat.startForeground(
      service,
      mediaNotificationId,
      media,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
    )
    service.getSystemService(NotificationManager::class.java).notify(mediaNotificationId, media)

    service.releasePlaceholder()

    assertFalse(shadowOf(service).isForegroundStopped)
  }

  @Test
  fun `the foreground start keys are the ones MediaButtonReceiver starts the service for`() {
    fun press(keyCode: Int) =
      Intent(Intent.ACTION_MEDIA_BUTTON)
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

    assertTrue(press(KeyEvent.KEYCODE_MEDIA_PLAY).isForegroundStartKey())
    assertTrue(press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE).isForegroundStartKey())
    assertTrue(press(KeyEvent.KEYCODE_HEADSETHOOK).isForegroundStartKey())
    assertFalse(press(KeyEvent.KEYCODE_MEDIA_PAUSE).isForegroundStartKey())
    assertFalse(press(KeyEvent.KEYCODE_MEDIA_NEXT).isForegroundStartKey())
    assertFalse(Intent(Intent.ACTION_MEDIA_BUTTON).isForegroundStartKey())
  }

  @Test
  @Config(sdk = [28])
  fun `below API 29 a playing service holds Media3's notification again and keeps it`() {
    val media = notification("Now playing")
    ServiceCompat.startForeground(service, mediaNotificationId, media, 0)
    service.getSystemService(NotificationManager::class.java).notify(mediaNotificationId, media)

    service.playInForeground(scope, startId = 5, hasMedia = { true }) { false }

    assertEquals(
      "Now playing",
      shadowOf(service).lastForegroundNotification.extras.getString(Notification.EXTRA_TITLE),
    )
    assertFalse(shadowOf(service).isForegroundStopped)
  }

  @Test
  @Config(sdk = [25])
  fun `below API 26 nothing is held, as startForegroundService does not exist`() {
    service.holdForeground()

    assertEquals(0, shadowOf(service).lastForegroundNotificationId)
  }

  private companion object {
    // ShadowService's stopSelfId before any stopSelf(startId); Android's start ids begin at 1.
    const val NEVER_STOPPED = 0
  }
}
