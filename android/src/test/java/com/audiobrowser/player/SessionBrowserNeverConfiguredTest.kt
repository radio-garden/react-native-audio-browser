package com.audiobrowser.player

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.BrowserPathHelper
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** A play, browse or item lookup whose configuration never arrives fails, not hangs or vanishes. */
@RunWith(RobolectricTestRunner::class)
class SessionBrowserNeverConfiguredTest {

  private val player =
    Player(RuntimeEnvironment.getApplication(), browserRegistrationTimeout = 100.milliseconds)
      .apply { setup(PlayerSetupOptions()) }
  private val car =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "car",
      0,
      0,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
      false,
      Bundle.EMPTY,
      false,
    )
  private val session = mock(MediaLibraryService.MediaLibrarySession::class.java)

  @After fun release() = player.exoPlayer.release()

  /** The error codes sent to the car, waiting up to 5 s for the first. */
  private fun errorsSentToTheCar(): List<Int> {
    repeat(250) {
      shadowOf(Looper.getMainLooper()).idle()
      val sent =
        mockingDetails(session).invocations.filter {
          it.method.name == "sendError" && it.arguments[0] == car
        }
      if (sent.isNotEmpty()) return sent.map { (it.arguments[1] as SessionError).code }
      Thread.sleep(20)
    }
    return emptyList()
  }

  @Test
  fun `a play whose browser is never configured fails and tells the car`() {
    val request =
      player
        .getMediaSessionCallback()
        .onSetMediaItems(
          session,
          car,
          mutableListOf(MediaItem.Builder().setMediaId("U1eRRwaa").build()),
          C.INDEX_UNSET,
          C.TIME_UNSET,
        )

    assertThrows(ExecutionException::class.java) { request.get(5, TimeUnit.SECONDS) }
    assertEquals(listOf(SessionError.ERROR_UNKNOWN), errorsSentToTheCar())
  }

  @Test
  fun `a browse whose browser is never configured serves the error tile and tells the car`() {
    val result =
      player
        .getMediaSessionCallback()
        .onGetChildren(session, car, "/library", 0, 100, null)
        .get(5, TimeUnit.SECONDS)

    assertEquals(BrowserPathHelper.ERROR_PATH, result.value!!.single().mediaId)
    assertEquals(listOf(SessionError.ERROR_UNKNOWN), errorsSentToTheCar())
  }

  @Test
  fun `an item lookup whose browser is never configured answers an error`() {
    val result =
      player.getMediaSessionCallback().onGetItem(session, car, "/library").get(5, TimeUnit.SECONDS)

    assertEquals(SessionError.ERROR_UNKNOWN, result.resultCode)
  }
}
