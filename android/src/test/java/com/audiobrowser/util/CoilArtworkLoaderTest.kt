package com.audiobrowser.util

import android.graphics.Bitmap
import android.graphics.Color
import coil3.ImageLoader
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Size
import coil3.svg.SvgDecoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class CoilArtworkLoaderTest {
  @Test
  fun `load applies size hint and headers to the request`() = runTest {
    val context = RuntimeEnvironment.getApplication()
    var captured: ImageRequest? = null
    val fakeBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val imageLoader = FakeImageLoader(context, fakeBitmap) { captured = it }

    val loader = CoilArtworkLoader(context, imageLoader, defaultSizePixels = 512)
    val bmp =
      loader.load(
        finalUrl = "https://cdn.example.com/a.png",
        headers = mapOf("Authorization" to "Bearer t"),
        sizeHintPixels = 256,
        isSvg = false,
      )

    assertEquals(fakeBitmap, bmp)
    assertNotNull(captured)
    assertEquals("https://cdn.example.com/a.png", captured!!.data)
    assertEquals(Size(Dimension.Pixels(256), Dimension.Pixels(256)), captured!!.sizeResolver.size())
    assertEquals("Bearer t", captured!!.httpHeaders["Authorization"])
  }

  @Test
  fun `load falls back to default size when hint is null`() = runTest {
    val context = RuntimeEnvironment.getApplication()
    var captured: ImageRequest? = null
    val fakeBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val imageLoader = FakeImageLoader(context, fakeBitmap) { captured = it }
    val loader = CoilArtworkLoader(context, imageLoader, defaultSizePixels = 512)
    val bmp = loader.load("https://cdn.example.com/a.png", null, null, false)
    assertEquals(fakeBitmap, bmp)
    assertNotNull(captured)
    assertEquals(Size(Dimension.Pixels(512), Dimension.Pixels(512)), captured!!.sizeResolver.size())
  }

  @Test
  fun `load decodes a data URL through a real Coil loader`() = runTest {
    val context = RuntimeEnvironment.getApplication()
    val loader = CoilArtworkLoader(context, ImageLoader.Builder(context).build())

    val bmp =
      loader.load(
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGP4z8AARwzEcQCukw/x0F8jngAAAABJRU5ErkJggg==",
        null,
        null,
        false,
      )

    assertEquals(Color.RED, bmp.getPixel(0, 0))
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `load decodes an SVG data URL through the SVG decoder the service registers`() = runTest {
    val context = RuntimeEnvironment.getApplication()
    val imageLoader = ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build()
    val loader = CoilArtworkLoader(context, imageLoader)

    val bmp =
      loader.load(
        "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI0IiBoZWlnaHQ9IjQiPjxyZWN0IHdpZHRoPSI0IiBoZWlnaHQ9IjQiIGZpbGw9IiNmZjAwMDAiLz48L3N2Zz4=",
        null,
        16,
        false,
      )

    assertEquals(Color.RED, bmp.getPixel(8, 8))
  }
}
