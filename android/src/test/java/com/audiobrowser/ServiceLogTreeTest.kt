package com.audiobrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
class ServiceLogTreeTest {
  @Test
  fun `a recreated service plants no second log tree`() {
    buildService().destroy()
    val trees = Timber.treeCount
    assertTrue(trees > 0)

    buildService().destroy()

    assertEquals(trees, Timber.treeCount)
  }
}
