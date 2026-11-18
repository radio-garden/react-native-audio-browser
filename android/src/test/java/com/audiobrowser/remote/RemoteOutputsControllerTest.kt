package com.audiobrowser.remote

import android.os.Looper
import com.margelo.nitro.audiobrowser.RemoteOutputsOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RemoteOutputsControllerTest {
  private val looper = Looper.getMainLooper()
  private val local = FakeLocalPlayer(looper)
  private val casts = mutableListOf<Pair<FakeRemote, String?>>()
  private val sonoses = mutableListOf<FakeRemote>()
  private val controller =
    RemoteOutputsController(
      RuntimeEnvironment.getApplication(),
      resolveUrls = { RemoteUrls(it.src ?: "", null) },
      castRemote = { _, receiver -> FakeRemote(looper).also { casts += it to receiver } },
      sonosRemote = { _, _ -> FakeRemote(looper).also { sonoses += it } },
    )

  @After fun release() = controller.release()

  private fun options(cast: Boolean? = null, receiver: String? = null, sonos: Boolean? = null) =
    RemoteOutputsOptions(cast = cast, castReceiverApplicationId = receiver, sonos = sonos)

  @Test
  fun `no remote outputs, no switch`() {
    assertFalse(controller.apply(null, local))
    assertFalse(controller.apply(options(), local))
    assertNull(controller.player)
  }

  @Test
  fun `an output turned on builds its remote and the switch once`() {
    assertTrue(controller.apply(options(cast = true, receiver = "ABCD1234"), local))
    val switch = controller.player
    assertNotNull(switch)

    assertFalse(controller.apply(options(cast = true, receiver = "ABCD1234"), local))

    assertSame(switch, controller.player)
    assertEquals(listOf("ABCD1234"), casts.map { it.second })
  }

  @Test
  fun `turning one output off releases its remote and keeps the switch`() {
    controller.apply(options(cast = true, sonos = true), local)
    val switch = controller.player

    assertFalse(controller.apply(options(sonos = true), local))

    assertSame(switch, controller.player)
    assertTrue(casts.single().first.released)
    assertFalse(sonoses.single().released)
  }

  @Test
  fun `turning every output off releases the switch and its remotes`() {
    controller.apply(options(cast = true, sonos = true), local)

    assertTrue(controller.apply(null, local))

    assertNull(controller.player)
    assertTrue(casts.single().first.released)
    assertTrue(sonoses.single().released)
  }
}
