package com.audiobrowser.sonos

import com.audiobrowser.remote.RemoteMediaResolutions
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SonosRemoteTest {
  private val scope = MainScope()
  private val controlled = mutableListOf<String>()
  private val remote =
    SonosRemote(
      RuntimeEnvironment.getApplication(),
      RemoteMediaResolutions(),
      scope,
      controlOf = { room ->
        controlled += room.id
        mock(SonosControl::class.java)
      },
      routingControllerId = { null },
    )
  private val sessions = mutableListOf<Boolean>()

  init {
    remote.setSessionListener { sessions += remote.isSessionAvailable }
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
    assertEquals(listOf("A"), controlled)

    SonosOutputs.unselect(room("A"))
    assertFalse(remote.isSessionAvailable)
    assertEquals(listOf(true, false), sessions)
  }

  @Test
  fun `selecting another room ends the session before the next one starts`() {
    SonosOutputs.select(room("A"))
    val first = remote.player

    SonosOutputs.select(room("B"))

    assertEquals(listOf(true, false, true), sessions)
    assertEquals(listOf("A", "B"), controlled)
    assertNotSame(first, remote.player)
  }

  @Test
  fun `once released, it no longer follows the selection`() {
    remote.release()

    SonosOutputs.select(room("A"))

    assertFalse(remote.isSessionAvailable)
    assertEquals(emptyList<Boolean>(), sessions)
  }

  private fun room(id: String) =
    SonosRoom(
      id = id,
      name = "Room $id",
      device = SonosDevice("uuid:$id", "Room $id", "http://h", "http://h/av", "http://h/rc", null),
    )
}
