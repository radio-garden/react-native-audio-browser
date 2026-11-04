package com.audiobrowser

import com.margelo.nitro.audiobrowser.Output
import com.margelo.nitro.audiobrowser.OutputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteOutputsTest {
  @Test
  fun `a selected route in the category is a remote output`() {
    assertEquals(
      Output(type = OutputType.REMOTE, name = "Kitchen", external = true),
      remoteOutputOf("Kitchen", offersCategory = true, isCast = false, isSonos = false),
    )
  }

  @Test
  fun `a selected route outside the category leaves the output to the local device`() {
    assertNull(
      remoteOutputOf("Living room TV", offersCategory = false, isCast = false, isSonos = false)
    )
  }

  @Test
  fun `a selected Cast route is a cast output`() {
    assertEquals(
      Output(type = OutputType.CAST, name = "Living room TV", external = true),
      remoteOutputOf("Living room TV", offersCategory = true, isCast = true, isSonos = false),
    )
  }

  @Test
  fun `a selected Sonos room is a sonos output`() {
    assertEquals(
      Output(type = OutputType.SONOS, name = "Dining Room", external = true),
      remoteOutputOf("Dining Room", offersCategory = true, isCast = false, isSonos = true),
    )
  }
}
