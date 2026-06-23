package com.audiobrowser.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.audiobrowser.browser.ResolvedArtwork
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

/**
 * Exported, token-gated provider serving browse artwork to Android Auto / AAOS (which run in a
 * different uid and cannot read a non-exported provider; Media3 issues no URI grant — verified).
 * It NEVER fetches a caller-supplied URL: the content URI carries an opaque token, looked up in
 * [com.audiobrowser.browser.BrowseArtworkRegistry]; an unknown token returns null. So it cannot be
 * used as a fetch proxy / SSRF vector. http(s)-only on the registered finalUrl is defense-in-depth.
 */
class ArtworkContentProvider : ContentProvider() {

  private val gate = Semaphore(MAX_CONCURRENT)

  // Encoded-bytes LRU keyed by "token:sizeHint" — avoids re-decoding on every scroll event.
  // Capacity is intentionally small (64 entries); artwork is typically 30–100 KB each.
  private val lruCache: LinkedHashMap<String, ByteArray> =
    object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
        size > LRU_CAPACITY
    }

  override fun onCreate(): Boolean = true

  override fun getType(uri: Uri): String = "image/png"

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
    val token = ArtworkUris.parseToken(uri) ?: return null
    val deps = CoilArtworkLoaderHolder.get() ?: return null
    val art: ResolvedArtwork = deps.registry.lookup(token) ?: return null
    val scheme = Uri.parse(art.finalUrl).scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null

    val sizeHint = deps.artworkSizeHint()
    val cacheKey = "$token:$sizeHint"

    // Cache hit: write cached bytes without re-decoding.
    val cached: ByteArray? = synchronized(lruCache) { lruCache[cacheKey] }
    if (cached != null) {
      return writeBytesToPipe(token, deps, cached)
    }

    val pipe = ParcelFileDescriptor.createReliablePipe()
    val readSide = pipe[0]
    val writeSide = pipe[1]

    // Return the read end immediately; fetch/decode/encode happens off the binder thread.
    deps.scope.launch {
      ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
        try {
          gate.withPermit {
            val bitmap: Bitmap =
              deps.loader.load(art.finalUrl, art.headers, sizeHint, art.isSvg)
            ByteArrayOutputStream().use { buf ->
              bitmap.compress(Bitmap.CompressFormat.PNG, 100, buf)
              val bytes = buf.toByteArray()
              synchronized(lruCache) { lruCache[cacheKey] = bytes }
              out.write(bytes)
            }
          }
        } catch (e: Throwable) {
          // Closing with no/partial data → car shows its placeholder. Never propagate across binder.
          Timber.w(e, "Artwork stream failed for token=$token")
        }
      } // AutoCloseOutputStream.use guarantees the write FD is closed on every path
    }

    return readSide
  }

  /** Writes [bytes] to a new reliable pipe on [deps].scope and returns the read end. */
  private fun writeBytesToPipe(
    token: String,
    deps: ArtworkProviderDeps,
    bytes: ByteArray,
  ): ParcelFileDescriptor? {
    val pipe = ParcelFileDescriptor.createReliablePipe()
    val readSide = pipe[0]
    val writeSide = pipe[1]
    deps.scope.launch {
      ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
        try {
          out.write(bytes)
        } catch (e: Throwable) {
          Timber.w(e, "Artwork cache-hit stream failed for token=$token")
        }
      }
    }
    return readSide
  }

  override fun query(
    uri: Uri,
    p: Array<String>?,
    s: String?,
    a: Array<String>?,
    o: String?,
  ): Cursor? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

  override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0

  companion object {
    private const val MAX_CONCURRENT = 6
    private const val LRU_CAPACITY = 64
  }
}
