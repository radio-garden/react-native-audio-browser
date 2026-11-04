package com.audiobrowser.sonos

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.mediarouter.media.MediaRouter
import com.audiobrowser.RemoteOutputs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SonosRoutesTest {

  private fun room(id: String, name: String) =
    SonosRoom(
      id = id,
      name = name,
      device =
        SonosDevice(
          udn = "uuid:$id",
          name = name,
          baseUrl = "http://10.0.0.1:1400",
          avTransportControlUrl = "http://10.0.0.1:1400/MediaRenderer/AVTransport/Control",
          groupRenderingControlUrl =
            "http://10.0.0.1:1400/MediaRenderer/GroupRenderingControl/Control",
          zoneGroupTopologyControlUrl = "http://10.0.0.1:1400/ZoneGroupTopology/Control",
        ),
    )

  private val dining = room("RINCON_A", "Dining Room")
  private val living = room("RINCON_C", "Living Room")

  @After
  fun tearDown() {
    SonosOutputs.selected?.let { SonosOutputs.unselect(it) }
  }

  @Test
  fun `offers each room as a remote output and as a Sonos room`() {
    val routes = sonosRouteDescriptors(listOf(dining, living), null, emptyMap()).routes

    assertEquals(listOf("RINCON_A", "RINCON_C"), routes.map { it.id })
    assertEquals(listOf("Dining Room", "Living Room"), routes.map { it.name })
    for (route in routes) {
      val filter = route.controlFilters.single()
      assertTrue(filter.hasCategory(RemoteOutputs.CATEGORY))
      assertTrue(filter.hasCategory(SonosOutputs.CATEGORY))
      assertEquals(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE, route.playbackType)
    }
  }

  @Test
  fun `reports the selected room connected, and only that one`() {
    val routes = sonosRouteDescriptors(listOf(dining, living), "RINCON_C", emptyMap()).routes

    assertEquals(
      listOf(
        MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED,
        MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED,
      ),
      routes.map { it.connectionState },
    )
  }

  @Test
  fun `carries each room's speaker volume`() {
    val routes = sonosRouteDescriptors(listOf(dining, living), null, mapOf("RINCON_C" to 12)).routes

    assertEquals(listOf(0, 12), routes.map { it.volume })
    assertEquals(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE, routes[0].volumeHandling)
    assertEquals(100, routes[0].volumeMax)
  }

  @Test
  fun `a late unselect of a room left behind keeps the room selected since`() {
    SonosOutputs.select(dining)
    SonosOutputs.select(living)
    SonosOutputs.unselect(dining)

    assertEquals(living, SonosOutputs.selected)
  }

  @Test
  fun `unselecting the selected room tells the listeners`() {
    var calls = 0
    val listener: () -> Unit = { calls++ }
    SonosOutputs.addListener(listener)
    try {
      SonosOutputs.select(dining)
      SonosOutputs.unselect(dining)
    } finally {
      SonosOutputs.removeListener(listener)
    }

    assertNull(SonosOutputs.selected)
    assertEquals(2, calls)
  }

  @Test
  fun `enabling turns the provider service on, disabling off`() {
    val context = RuntimeEnvironment.getApplication()
    val component = ComponentName(context, SonosRouteProviderService::class.java)

    SonosOutputs.setEnabled(context, true)
    assertEquals(
      PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
      context.packageManager.getComponentEnabledSetting(component),
    )

    SonosOutputs.setEnabled(context, false)
    assertEquals(
      PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
      context.packageManager.getComponentEnabledSetting(component),
    )
  }

  @Test
  fun `the multicast lock is taken only with the permission the app declares`() {
    val context = RuntimeEnvironment.getApplication()
    assertFalse(SonosOutputs.canHoldMulticastLock(context))

    shadowOf(context).grantPermissions(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)

    assertTrue(SonosOutputs.canHoldMulticastLock(context))
  }

  @Test
  fun `disabling forgets the selected room, so turning back on doesn't move playback there`() {
    val context = RuntimeEnvironment.getApplication()
    SonosOutputs.select(dining)

    SonosOutputs.setEnabled(context, false)

    assertNull(SonosOutputs.selected)
  }
}
