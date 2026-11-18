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
 * Serves browse artwork to Android Auto and AAOS as PNG files. It is exported because both run in
 * another uid and Media3 issues no URI grant. So it never fetches a URL the caller supplies: the
 * URI carries an opaque token, and only a token in [com.audiobrowser.browser.BrowseArtworkRegistry]
 * whose URL is http(s) resolves; anything else returns null.
 *
 * The response is an on-disk file in [ARTWORK_SUBDIR], not a pipe: the car's image loader seeks and
 * reads statSize, which a pipe reports as -1. The file doubles as the cache, keyed by token.
 */
class ArtworkContentProvider : ContentProvider() {

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

    if (file.exists() && file.length() > 0) {
      return try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
      } catch (e: Throwable) {
        Timber.w(e, "Artwork fast-path open failed for token=$token")
        null
      }
    }

    // openFile runs on a binder thread, which may block; the gate keeps loads from taking them all.
    if (!gate.tryAcquire(1, GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      Timber.w("Artwork gate timeout for token=$token — returning null")
      return null
    }
    try {
      // Another request may have produced the file while this one waited; if pruned since, produce.
      if (file.exists() && file.length() > 0) {
        val fd =
          try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
          } catch (e: Throwable) {
            Timber.w(e, "Artwork cache file vanished before re-check open; producing")
            null
          }
        if (fd != null) return fd
      }

      artworkDir.mkdirs()
      // Written aside and renamed, so the unlocked fast path never opens a half-written file.
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
        val ok =
          tmp.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
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
    /** Tests only. */
    @JvmField internal var maxCacheFilesOverride: Int? = null
  }

  private fun pruneCache(dir: File) {
    val limit = maxCacheFilesOverride ?: MAX_CACHE_FILES
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
    if (files.size <= limit) return
    files
      .sortedBy { it.lastModified() }
      .take(files.size - limit)
      .forEach { runCatching { it.delete() } }
  }
}
