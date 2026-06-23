package com.audiobrowser.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.audiobrowser.browser.ResolvedArtwork
import java.io.File
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Exported, token-gated provider serving browse artwork to Android Auto / AAOS (which run in a
 * different uid and cannot read a non-exported provider; Media3 issues no URI grant — verified).
 * It NEVER fetches a caller-supplied URL: the content URI carries an opaque token, looked up in
 * [com.audiobrowser.browser.BrowseArtworkRegistry]; an unknown token returns null. So it cannot be
 * used as a fetch proxy / SSRF vector. http(s)-only on the registered finalUrl is defense-in-depth.
 *
 * Serve design: a real on-disk file via [ParcelFileDescriptor.open] — seekable, no pipe, no writer
 * coroutine. The file is keyed by SHA-256 token so identical URLs always reuse the same file.
 *
 * Bug fixes over the previous pipe/coroutine design:
 *   B (FD leak): eliminated — no coroutine launched from deps.scope; no writer that could fail to
 *     close the write-end of a pipe when the scope is already cancelled.
 *   C (wedge DoS): eliminated — the Semaphore permit is released in a try/finally BEFORE the file
 *     is opened for the caller; no blocking write to a pipe while holding the permit.
 *   D (per-request re-encode): eliminated — on-disk file IS the cache; re-requests are a cheap
 *     file open (fast path, zero decode, zero network).
 *   E (non-seekable pipe): eliminated — ParcelFileDescriptor.open returns a regular file FD;
 *     statSize is valid; the car's image loader can seek.
 */
class ArtworkContentProvider : ContentProvider() {

  // java.util.concurrent.Semaphore — works on binder threads without a coroutine scope.
  private val gate = Semaphore(MAX_CONCURRENT)

  override fun onCreate(): Boolean = true

  override fun getType(uri: Uri): String = "image/png"

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
    val token = ArtworkUris.parseToken(uri) ?: return null
    val deps = CoilArtworkLoaderHolder.get() ?: return null
    val art: ResolvedArtwork = deps.registry.lookup(token) ?: return null
    val scheme = Uri.parse(art.finalUrl).scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null

    val artworkDir = File(context!!.cacheDir, ARTWORK_SUBDIR)
    val file = File(artworkDir, "$token.png")

    // Fast path: file already cached on disk — no permit, no decode, no network.
    if (file.exists() && file.length() > 0) {
      return try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
      } catch (e: Throwable) {
        Timber.w(e, "Artwork fast-path open failed for token=$token")
        null
      }
    }

    // Produce path (cache miss): bound concurrency, block on binder thread (UAMP pattern).
    if (!gate.tryAcquire(1, GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      Timber.w("Artwork gate timeout for token=$token — returning null")
      return null
    }
    try {
      // Re-check after acquiring — a concurrent producer may have written the file.
      if (file.exists() && file.length() > 0) {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
      }

      artworkDir.mkdirs()
      val tmp = File.createTempFile(token, ".png.tmp", artworkDir)

      val bitmap: Bitmap =
        try {
          runBlocking {
            withTimeout(LOAD_TIMEOUT_MS) {
              deps.loader.load(art.finalUrl, art.headers, deps.artworkSizeHint(), art.isSvg)
            }
          }
        } catch (e: Throwable) {
          Timber.w(e, "Artwork load failed for token=$token")
          tmp.delete()
          return null
        }

      try {
        val ok = tmp.outputStream().use { out ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (!ok) throw IOException("PNG encode failed for token=$token")
        if (!tmp.renameTo(file)) {
          Timber.w("Artwork rename failed for token=$token")
          tmp.delete()
          return null
        }
        pruneCache(artworkDir)
      } catch (e: Throwable) {
        Timber.w(e, "Artwork write failed for token=$token")
        tmp.delete()
        return null
      }

      return try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
      } catch (e: Throwable) {
        Timber.w(e, "Artwork final open failed for token=$token")
        null
      }
    } finally {
      gate.release()
    }
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
    private const val GATE_TIMEOUT_SECONDS = 5L
    private const val LOAD_TIMEOUT_MS = 8_000L
    internal const val ARTWORK_SUBDIR = "audiobrowser-artwork"
    const val MAX_CACHE_FILES = 512
    /** Overridable in tests only. Production code must not change this. */
    @JvmField internal var maxCacheFilesOverride: Int? = null
  }

  private fun pruneCache(dir: File) {
    val limit = maxCacheFilesOverride ?: MAX_CACHE_FILES
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
    if (files.size <= limit) return
    files.sortedBy { it.lastModified() }
      .take(files.size - limit)
      .forEach { runCatching { it.delete() } }
  }
}
