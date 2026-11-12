package com.audiobrowser

import com.audiobrowser.sonos.SonosOutputs
import com.margelo.nitro.audiobrowser.Output
import com.margelo.nitro.audiobrowser.OutputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteOutputsTest {
  private val types = mapOf("cast" to OutputType.CAST, SonosOutputs.CATEGORY to OutputType.SONOS)

  private fun outputOf(name: String, vararg categories: String, sonosSelected: Boolean = true) =
    remoteOutputOf(name, types, sonosSelected) { it in categories }

  @Test
  fun `a selected route in the category is a remote output`() {
    assertEquals(
      Output(type = OutputType.REMOTE, name = "Kitchen", external = true),
      outputOf("Kitchen", RemoteOutputs.CATEGORY),
    )
  }

  @Test
  fun `a selected route outside every category leaves the output to the local device`() {
    assertNull(outputOf("Living room TV", "other"))
  }

  @Test
  fun `a route of a category turned on has that category's type`() {
    assertEquals(
      Output(type = OutputType.CAST, name = "Living room TV", external = true),
      outputOf("Living room TV", "cast"),
    )
    assertEquals(
      Output(type = OutputType.SONOS, name = "Dining Room", external = true),
      outputOf("Dining Room", RemoteOutputs.CATEGORY, SonosOutputs.CATEGORY),
    )
  }

  @Test
  fun `a Sonos route while this app's provider has no room selected stays local`() {
    assertNull(
      outputOf("Dining Room", RemoteOutputs.CATEGORY, SonosOutputs.CATEGORY, sonosSelected = false)
    )
  }

  @Test
  fun `a Sonos route with Sonos off here and no room selected stays local`() {
    assertNull(
      remoteOutputOf("Dining Room", mapOf("cast" to OutputType.CAST), sonosSelected = false) {
        it == RemoteOutputs.CATEGORY || it == SonosOutputs.CATEGORY
      }
    )
  }

  @Test
  fun `other routes are remote outputs while no Sonos room is selected`() {
    assertEquals(
      Output(type = OutputType.CAST, name = "Living room TV", external = true),
      outputOf("Living room TV", "cast", sonosSelected = false),
    )
    assertEquals(
      Output(type = OutputType.REMOTE, name = "Kitchen", external = true),
      outputOf("Kitchen", RemoteOutputs.CATEGORY, sonosSelected = false),
    )
  }
}
