package com.audiobrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteOutputRoutesTest {
  @Test
  fun `a remote output's route is in the category and visible only to this app`() {
    val route = RemoteOutputs.routeDescriptorBuilder("speaker", "Speaker").build()

    assertTrue(route.controlFilters.single().hasCategory(RemoteOutputs.CATEGORY))
    assertFalse(route.isVisibilityPublic)
    assertEquals(emptySet<String>(), route.allowedPackages)
  }

  @Test
  fun `a remote output's route carries the further categories given`() {
    val route = RemoteOutputs.routeDescriptorBuilder("speaker", "Speaker", "other").build()

    val filter = route.controlFilters.single()
    assertTrue(filter.hasCategory(RemoteOutputs.CATEGORY))
    assertTrue(filter.hasCategory("other"))
  }
}
