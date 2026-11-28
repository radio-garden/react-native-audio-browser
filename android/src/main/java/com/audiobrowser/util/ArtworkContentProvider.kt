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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Serves browse artwork to Android Auto and AAOS as PNG files. It is exported because both run in
 * another uid and Media3 issues no URI grant. So it never fetches a URL the caller supplies: the
 * URI carries an opaque token, and only a token in [com.audiobrowser.browser.BrowseArtworkRegistry]
 * whose URL is [ArtworkUris.isServable] resolves; anything else returns null.
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
    val context = context ?: return null
    if (!ArtworkUris.isServable(Uri.parse(art.finalUrl), context.packageName)) return null

    val artworkDir = File(context.cacheDir, ARTWORK_SUBDIR)
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
              val sizeHint = deps.artworkSizeHint()
              val sized = sizedArtwork(deps, art, sizeHint, token, context.packageName)
              deps.loader.load(sized.finalUrl, sized.headers, sizeHint, sized.isSvg)
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

  /**
   * [art] re-resolved for [sizeHint], so the request asks for the size the car draws; [art] itself
   * when there is no hint, nothing resolves it within [RESOLVE_TIMEOUT_MS], the resolution fails,
   * or it resolves to a URL this provider would not serve.
   */
  private suspend fun sizedArtwork(
    deps: ArtworkProviderDeps,
    art: ResolvedArtwork,
    sizeHint: Int?,
    token: String,
    packageName: String,
  ): ResolvedArtwork {
    if (sizeHint == null) return art
    val source =
      try {
        withTimeoutOrNull(resolveTimeoutMsOverride ?: RESOLVE_TIMEOUT_MS) {
          deps.resolveBrowseArtwork(art.finalUrl, sizeHint)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Timber.w(e, "Artwork resolution failed for token=$token; fetching it as registered")
        null
      } ?: return art
    if (!ArtworkUris.isServable(Uri.parse(source.uri), packageName)) return art
    // A URL resolved at size is its own source: a registered SVG may resolve to a raster.
    val isSvg =
      if (source.uri == art.finalUrl) art.isSvg else SvgArtworkRenderer.isSvgUrl(source.uri)
    return ResolvedArtwork(source.uri, source.headers, isSvg)
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
    /** Within [LOAD_TIMEOUT_MS], leaving the fetch most of it. */
    internal const val RESOLVE_TIMEOUT_MS = 2_000L
    internal const val ARTWORK_SUBDIR = "audiobrowser-artwork"
    const val MAX_CACHE_FILES = 512
    /** Tests only. */
    @JvmField internal var maxCacheFilesOverride: Int? = null
    /** Tests only. */
    @JvmField internal var resolveTimeoutMsOverride: Long? = null

    /**
     * Discards the previous process's artwork files, which are stale. Best-effort. The directory is
     * renamed at once, so files the provider writes next never land in it, and deleted on [scope],
     * along with any stale directory a killed process left behind.
     */
    internal fun discardStaleArtwork(cacheDir: File, scope: CoroutineScope) {
      val stalePrefix = "$ARTWORK_SUBDIR-stale-"
      val artworkDir = File(cacheDir, ARTWORK_SUBDIR)
      val renamed = runCatching {
        artworkDir.renameTo(File(cacheDir, stalePrefix + System.nanoTime()))
      }
      if (renamed.getOrNull() != true && artworkDir.exists()) {
        Timber.w(
          "Could not rename the stale artwork in $artworkDir; it stays, pruned to the cache limit"
        )
      }
      scope.launch {
        cacheDir
          .listFiles { f -> f.name.startsWith(stalePrefix) }
          ?.forEach { runCatching { it.deleteRecursively() } }
      }
    }
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
