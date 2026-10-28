package com.audiobrowser

import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.HeartRating
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Media3 hands a legacy controller's `setRating` to the callback that names a media id, with the
 * current item's id. Robolectric cannot route a MediaControllerCompat to the session, so these call
 * that callback as Media3 does.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyRatingTest {

  private val controller = run {
    val application = RuntimeEnvironment.getApplication()
    shadowOf(application)
      .setComponentNameAndServiceForBindService(
        ComponentName(application, HeadlessTaskService::class.java),
        Binder(),
      )
    Robolectric.buildService(Service::class.java).create()
  }
  private val service = controller.get()
  private val player = service.player
  private val legacyController =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "legacy",
      0,
      0,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
      false,
      Bundle.EMPTY,
      false,
    )
  private val session = service.onGetSession(legacyController)

  @After
  fun destroy() {
    controller.destroy()
  }

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  private fun rate(mediaId: String, heart: Boolean): Int {
    val result =
      player
        .getMediaSessionCallback()
        .onSetRating(session, legacyController, mediaId, HeartRating(heart))
        .get()
    idle()
    return result.resultCode
  }

  private fun currentMediaId() = session.player.currentMediaItem!!.mediaId

  private fun loadTrack() {
    controller.bind()
    player.load(TestFixtures.track(id = "a", favorited = false))
    idle()
  }

  @Test
  fun `a rating of the current item sets its favorite and USER_RATING`() {
    loadTrack()

    assertEquals(SessionResult.RESULT_SUCCESS, rate(currentMediaId(), true))
    assertEquals(true, player.currentTrack?.favorited)
    assertEquals(HeartRating(true), session.player.mediaMetadata.userRating)

    assertEquals(SessionResult.RESULT_SUCCESS, rate(currentMediaId(), false))
    assertEquals(false, player.currentTrack?.favorited)
    assertEquals(HeartRating(false), session.player.mediaMetadata.userRating)
  }

  @Test
  fun `a rating of another item leaves the favorite`() {
    loadTrack()

    assertEquals(SessionResult.RESULT_ERROR_NOT_SUPPORTED, rate("b", true))
    assertEquals(false, player.currentTrack?.favorited)
    assertEquals(HeartRating(false), session.player.mediaMetadata.userRating)
  }
}
