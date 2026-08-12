package com.audiobrowser.util

import android.content.Context
import android.graphics.Bitmap
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.asImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred

/**
 * Minimal [ImageLoader] test double. Records each executed [ImageRequest] via [onRequest] and
 * returns a [SuccessResult] wrapping [fakeBitmap].
 */
class FakeImageLoader(
  private val context: Context,
  private val fakeBitmap: Bitmap,
  private val onRequest: (ImageRequest) -> Unit,
) : ImageLoader {

  override val defaults: ImageRequest.Defaults = ImageRequest.Defaults.DEFAULT

  override val components: ComponentRegistry = ComponentRegistry()

  override val memoryCache: MemoryCache? = null

  override val diskCache: DiskCache? = null

  override suspend fun execute(request: ImageRequest): ImageResult {
    onRequest(request)
    return SuccessResult(image = fakeBitmap.asImage(), request = request)
  }

  override fun enqueue(request: ImageRequest): Disposable {
    val deferred =
      CompletableDeferred<ImageResult>(
        SuccessResult(image = fakeBitmap.asImage(), request = request)
      )
    return object : Disposable {
      override val isDisposed: Boolean
        get() = true

      override fun dispose() {}

      override val job = deferred
    }
  }

  override fun shutdown() {}

  override fun newBuilder(): ImageLoader.Builder = ImageLoader.Builder(context)
}
