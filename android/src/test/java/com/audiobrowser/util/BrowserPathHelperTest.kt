package com.audiobrowser.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPathHelperTest {

  @Test
  fun `containsSegment matches a uid as a full path segment`() {
    val path = "/listen/amsterdam-funk-channel/Gw0LGB8j"
    assertTrue(BrowserPathHelper.containsSegment(path, "Gw0LGB8j"))
    assertTrue(BrowserPathHelper.containsSegment(path, "amsterdam-funk-channel"))
    assertTrue(BrowserPathHelper.containsSegment(path, "listen"))
    assertTrue(BrowserPathHelper.containsSegment("/listen/x/Gw0LGB8j?hl=en", "Gw0LGB8j"))
    assertTrue(BrowserPathHelper.containsSegment("/listen/x/Gw0LGB8j#frag", "Gw0LGB8j"))
  }

  @Test
  fun `containsSegment rejects partial-segment and missing matches`() {
    val path = "/listen/amsterdam-funk-channel/Gw0LGB8j"
    assertFalse(BrowserPathHelper.containsSegment(path, "Gw0LGB8"))
    assertFalse(BrowserPathHelper.containsSegment(path, "funk"))
    assertFalse(BrowserPathHelper.containsSegment(path, "msterdam-funk-channe"))
    assertFalse(BrowserPathHelper.containsSegment(path, "NopeNope"))
    assertFalse(BrowserPathHelper.containsSegment(path, ""))
  }
}
