package com.audiobrowser.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.audiobrowser.browser.BrowseArtworkRegistry
import com.audiobrowser.browser.ResolvedArtwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ArtworkContentProviderTest {
  private lateinit var provider: ArtworkContentProvider
  private lateinit var registry: BrowseArtworkRegistry

  @Before fun setUp() {
    provider = Robolectric.setupContentProvider(ArtworkContentProvider::class.java)
    registry = BrowseArtworkRegistry()
    val context = RuntimeEnvironment.getApplication()
    val fakeBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val loader = CoilArtworkLoader(context, FakeImageLoader(context, fakeBitmap) {})
    CoilArtworkLoaderHolder.set(
      ArtworkProviderDeps(loader, registry, CoroutineScope(Dispatchers.IO))
    )
  }

  @After fun tearDown() {
    CoilArtworkLoaderHolder.get()?.let { CoilArtworkLoaderHolder.clearIf(it) }
  }

  private fun uri(token: String) =
    Uri.parse(ArtworkUris.contentUri(ArtworkUris.authorityFor("com.test"), token))

  @Test fun `getType is png`() {
    assertEquals("image/png", provider.getType(uri("anything")))
  }

  @Test fun `openFile returns a readable PNG for a registered http token`() {
    val token = ArtworkUris.tokenFor("https://cdn/a.png")
    registry.register(token, ResolvedArtwork("https://cdn/a.png", null, isSvg = false))
    val pfd = provider.openFile(uri(token), "r")
    assertNotNull(pfd)
    val bytes = java.io.FileInputStream(pfd!!.fileDescriptor).readBytes()
    assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) // valid image
  }

  @Test fun `openFile returns null for an unknown token`() {
    assertNull(provider.openFile(uri("nope"), "r"))
  }

  @Test fun `openFile returns null for a non-http registered url`() {
    val token = ArtworkUris.tokenFor("android.resource://com.test/drawable/ic")
    registry.register(token, ResolvedArtwork("android.resource://com.test/drawable/ic", null, false))
    assertNull(provider.openFile(uri(token), "r"))
  }

  @Test fun `openFile returns null when holder absent`() {
    CoilArtworkLoaderHolder.get()?.let { CoilArtworkLoaderHolder.clearIf(it) }
    val token = ArtworkUris.tokenFor("https://cdn/a.png")
    registry.register(token, ResolvedArtwork("https://cdn/a.png", null, false))
    assertNull(provider.openFile(uri(token), "r"))
  }

  // LRU anti-flicker regression test: two openFile calls for the same token both return valid PNGs.
  // The second call should hit the LRU cache (no re-decode needed).
  @Test fun `openFile LRU cache hit returns valid PNG on second call`() {
    var loadCount = 0
    val context = RuntimeEnvironment.getApplication()
    val fakeBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val countingLoader = CoilArtworkLoader(
      context,
      FakeImageLoader(context, fakeBitmap) { loadCount++ }
    )
    CoilArtworkLoaderHolder.set(
      ArtworkProviderDeps(countingLoader, registry, CoroutineScope(Dispatchers.IO))
    )

    val token = ArtworkUris.tokenFor("https://cdn/b.png")
    registry.register(token, ResolvedArtwork("https://cdn/b.png", null, isSvg = false))

    // First call
    val pfd1 = provider.openFile(uri(token), "r")
    assertNotNull(pfd1)
    val bytes1 = java.io.FileInputStream(pfd1!!.fileDescriptor).readBytes()
    assertNotNull(BitmapFactory.decodeByteArray(bytes1, 0, bytes1.size))

    // Second call — should use LRU cache
    val pfd2 = provider.openFile(uri(token), "r")
    assertNotNull(pfd2)
    val bytes2 = java.io.FileInputStream(pfd2!!.fileDescriptor).readBytes()
    assertNotNull(BitmapFactory.decodeByteArray(bytes2, 0, bytes2.size))

    // Loader should have been called only once (cache hit on second call)
    assertEquals(1, loadCount)
  }
}
