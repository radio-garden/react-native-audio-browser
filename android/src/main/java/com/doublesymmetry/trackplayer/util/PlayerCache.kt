package com.doublesymmetry.trackplayer.util

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Singleton cache manager for ExoPlayer media caching. Provides thread-safe lazy initialization of
 * media cache.
 */
object PlayerCache {
  @Volatile private var instance: SimpleCache? = null

  /**
   * Initialize the media cache with the specified size.
   *
   * @param context Android context
   * @param sizeKb Cache size in kilobytes
   * @return Initialized SimpleCache instance
   */
  fun initCache(context: Context, sizeKb: Long): SimpleCache {
    val db: DatabaseProvider = StandaloneDatabaseProvider(context)

    instance
      ?: synchronized(this) {
        instance
          ?: SimpleCache(
              File(context.cacheDir, "RNTP"),
              LeastRecentlyUsedCacheEvictor(
                sizeKb * 1000 // kb to bytes
              ),
              db,
            )
            .also { instance = it }
      }

    return instance!!
  }
}
