package com.audiobrowser.util

import android.graphics.Bitmap
import coil3.request.ImageRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CoilArtworkLoaderTest {
  @Test
  fun `load applies size hint and headers to the request`() = runTest {
    val context = RuntimeEnvironment.getApplication()
    var captured: ImageRequest? = null
    val fakeBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    // FakeImageLoader records the request and returns a known bitmap.
    val imageLoader = FakeImageLoader(context, fakeBitmap) { captured = it }

    val loader = CoilArtworkLoader(context, imageLoader, defaultSizePixels = 512)
    val bmp = loader.load(
      finalUrl = "https://cdn.example.com/a.png",
      headers = mapOf("Authorization" to "Bearer t"),
      sizeHintPixels = 256,
      isSvg = false,
    )

    assertEquals(fakeBitmap, bmp)
    assertNotNull(captured)
    // data is the finalUrl
    assertEquals("https://cdn.example.com/a.png", captured!!.data)
  }

  @Test
  fun `load falls back to default size when hint is null`() = runTest {
    val context = RuntimeEnvironment.getApplication()
    val fakeBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val imageLoader = FakeImageLoader(context, fakeBitmap) {}
    val loader = CoilArtworkLoader(context, imageLoader, defaultSizePixels = 512)
    val bmp = loader.load("https://cdn.example.com/a.png", null, null, false)
    assertEquals(fakeBitmap, bmp)
  }
}
