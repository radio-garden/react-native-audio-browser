package com.audiobrowser.sonos

import android.os.Looper
import androidx.media3.common.PlaybackException
import com.audiobrowser.remote.RemotePlayback
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SonosRemoteTest {
  private val scope = MainScope()
  private val controls = mutableMapOf<String, SonosControl>()
  private val remote =
    SonosRemote(
      RuntimeEnvironment.getApplication(),
      scope,
      controlOf = { room -> mock(SonosControl::class.java).also { controls[room.id] = it } },
      routingControllerId = { null },
    )

  private val sessions = mutableListOf<Boolean>()

  init {
    remote.setListener(
      object : RemotePlayback.Listener {
        override fun onSessionChanged() {
          sessions += remote.isSessionAvailable
        }

        override fun onEnded() = Unit

        override fun onFailed(error: PlaybackException) = Unit
      }
    )
  }

  @After
  fun tearDown() {
    SonosOutputs.selected?.let(SonosOutputs::unselect)
    remote.release()
    scope.cancel()
  }

  @Test
  fun `has a session only while a room is selected`() {
    assertFalse(remote.isSessionAvailable)

    SonosOutputs.select(room("A"))
    assertTrue(remote.isSessionAvailable)
    assertEquals(setOf("A"), controls.keys)

    SonosOutputs.unselect(room("A"))
    assertFalse(remote.isSessionAvailable)
    assertEquals(listOf(true, false), sessions)
    assertFalse(SonosOutputs.hasSession("A"))
  }

  @Test
  fun `selecting another room swaps the session in one change`() {
    SonosOutputs.select(room("A"))
    val first = remote.player

    SonosOutputs.select(room("B"))

    assertEquals(listOf(true, true), sessions)
    assertFalse(SonosOutputs.hasSession("A"))
    assertTrue(SonosOutputs.hasSession("B"))
    assertNotSame(first, remote.player)
  }

  @Test
  fun `a room's volume set from the Output Switcher goes through its session`() {
    SonosOutputs.select(room("A"))

    assertTrue(SonosOutputs.setVolumeThroughSession("A", 12))
    assertFalse(SonosOutputs.setVolumeThroughSession("B", 12))

    idleUntil("the speaker's volume set") {
      mockingDetails(controls.getValue("A")).invocations.any { it.method.name == "setVolume" }
    }
    verify(controls.getValue("A")).setVolume(12)
    assertEquals(12, remote.player.deviceVolume)
    assertEquals(12, SonosOutputs.volumeOf("A"))
  }

  @Test
  fun `once released, it no longer follows the selection`() {
    remote.release()

    SonosOutputs.select(room("A"))

    assertFalse(remote.isSessionAvailable)
    assertFalse(SonosOutputs.hasSession("A"))
    assertEquals(emptyList<Boolean>(), sessions)
  }

  /** Commands run on a background thread. */
  private fun idleUntil(what: String, condition: () -> Boolean) {
    repeat(200) {
      shadowOf(Looper.getMainLooper()).idle()
      if (condition()) return
      Thread.sleep(10)
    }
    fail("never $what")
  }

  private fun room(id: String) =
    SonosRoom(
      id = id,
      name = "Room $id",
      device = SonosDevice("uuid:$id", "Room $id", "http://h", "http://h/av", "http://h/rc", null),
    )
}
