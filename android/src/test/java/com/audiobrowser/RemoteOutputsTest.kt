package com.audiobrowser

import com.margelo.nitro.audiobrowser.Output
import com.margelo.nitro.audiobrowser.OutputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteOutputsTest {
  private val types = mapOf("cast" to OutputType.CAST, "sonos" to OutputType.SONOS)

  private fun outputOf(name: String, vararg categories: String) =
    remoteOutputOf(name, types) { it in categories }

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
      outputOf("Dining Room", RemoteOutputs.CATEGORY, "sonos"),
    )
  }
}
