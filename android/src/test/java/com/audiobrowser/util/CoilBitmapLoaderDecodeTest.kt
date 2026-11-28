package com.audiobrowser.util

import android.graphics.Bitmap
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoilBitmapLoaderDecodeTest {
  private fun png(width: Int, height: Int = width): ByteArray =
    ByteArrayOutputStream()
      .also {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
          .compress(Bitmap.CompressFormat.PNG, 100, it)
      }
      .toByteArray()

  private fun loader(scope: CoroutineScope): CoilBitmapLoader {
    val context = RuntimeEnvironment.getApplication()
    return CoilBitmapLoader(
      context = context,
      imageLoader = FakeImageLoader(context, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) {},
      resolveDisplayArtwork = { _, _ -> null },
      scope = scope,
    )
  }

  private fun TestScope.decode(data: ByteArray): ListenableFuture<Bitmap> =
    loader(CoroutineScope(StandardTestDispatcher(testScheduler))).decodeBitmap(data).also {
      advanceUntilIdle()
    }

  @Test
  fun `a decode on a cancelled scope fails rather than never completing`() {
    val future = loader(CoroutineScope(Job().apply { cancel() })).decodeBitmap(png(16))

    assertThrows(CancellationException::class.java) { future.get(5, TimeUnit.SECONDS) }
  }

  @Test
  fun `cancelling the future cancels the decode`() = runTest {
    val job = Job()
    val future =
      loader(CoroutineScope(job + StandardTestDispatcher(testScheduler))).decodeBitmap(png(16))

    future.cancel(true)

    assertTrue(job.children.single().isCancelled)
  }

  @Test
  fun `a picture larger than the size hint is decoded downsampled`() = runTest {
    assertEquals(512, decode(png(2048)).get().width)
  }

  @Test
  fun `a picture smaller than the size hint is decoded at its own size`() = runTest {
    val bitmap = decode(png(300, 200)).get()

    assertEquals(300, bitmap.width)
    assertEquals(200, bitmap.height)
  }

  @Test
  fun `a corrupt picture gives no artwork`() = runTest {
    val future = decode(byteArrayOf(1, 2, 3, 4))

    val error = assertThrows(ExecutionException::class.java) { future.get() }
    assertTrue(error.cause is IllegalArgumentException)
  }

  @Test
  fun `embedded artwork is decoded on the loader's scope, not by the caller`() = runTest {
    val loader = loader(CoroutineScope(StandardTestDispatcher(testScheduler)))

    val future = loader.decodeBitmap(png(16))

    assertFalse(future.isDone)
    advanceUntilIdle()
    assertEquals(16, future.get().width)
  }
}
