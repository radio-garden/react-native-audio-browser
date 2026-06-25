package com.audiobrowser.cast

import com.margelo.nitro.audiobrowser.CastState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Truth table for [CastStateResolver]: CONNECTED > CONNECTING > NOT_CONNECTED > NO_DEVICES. */
class CastStateResolverTest {

  @Test
  fun `connected wins over everything`() {
    assertEquals(
      CastState.CONNECTED,
      CastStateResolver.resolve(connected = true, connecting = true, hasDevices = true),
    )
    assertEquals(
      CastState.CONNECTED,
      CastStateResolver.resolve(connected = true, connecting = false, hasDevices = false),
    )
  }

  @Test
  fun `connecting wins over devices when not connected`() {
    assertEquals(
      CastState.CONNECTING,
      CastStateResolver.resolve(connected = false, connecting = true, hasDevices = true),
    )
    assertEquals(
      CastState.CONNECTING,
      CastStateResolver.resolve(connected = false, connecting = true, hasDevices = false),
    )
  }

  @Test
  fun `devices available but idle is not-connected`() {
    assertEquals(
      CastState.NOT_CONNECTED,
      CastStateResolver.resolve(connected = false, connecting = false, hasDevices = true),
    )
  }

  @Test
  fun `nothing available is no-devices`() {
    assertEquals(
      CastState.NO_DEVICES,
      CastStateResolver.resolve(connected = false, connecting = false, hasDevices = false),
    )
  }
}
