package com.audiobrowser.util

import android.graphics.Bitmap
import com.audiobrowser.browser.BrowseArtworkRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CoilArtworkLoaderHolderTest {
  private fun deps() =
    ArtworkProviderDeps(
      loader = CoilArtworkLoader(
        RuntimeEnvironment.getApplication(),
        FakeImageLoader(RuntimeEnvironment.getApplication(), Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)) {}
      ),
      registry = BrowseArtworkRegistry(),
      scope = CoroutineScope(Dispatchers.Unconfined)
    )

  @After
  fun tearDown() =
    CoilArtworkLoaderHolder.get()?.let { CoilArtworkLoaderHolder.clearIf(it) } ?: Unit

  @Test
  fun `get returns what was set`() {
    val d = deps()
    CoilArtworkLoaderHolder.set(d)
    assertSame(d, CoilArtworkLoaderHolder.get())
  }

  @Test
  fun `clearIf only clears the matching instance`() {
    val first = deps()
    val second = deps()
    CoilArtworkLoaderHolder.set(first)
    CoilArtworkLoaderHolder.set(second)
    CoilArtworkLoaderHolder.clearIf(first) // stale instance — must NOT clear
    assertSame(second, CoilArtworkLoaderHolder.get())
    CoilArtworkLoaderHolder.clearIf(second)
    assertNull(CoilArtworkLoaderHolder.get())
  }
}
