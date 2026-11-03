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
      remoteOutputOf("Kitchen", offersCategory = true),
    )
  }

  @Test
  fun `a selected route outside the category leaves the output to the local device`() {
    assertNull(remoteOutputOf("Living room TV", offersCategory = false))
  }
}
