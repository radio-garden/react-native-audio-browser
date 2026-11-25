package com.audiobrowser

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.audiobrowser.model.EngineOptionsStore
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.RetryPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ServiceEngineOptionsTest {
  private val appOptions =
    PlayerSetupOptions(maxBuffer = 120_000.0, retryPolicy = RetryPolicy.Infinite())

  @Test
  fun `a recreated service builds its engine from the options the app last set up`() {
    buildService().apply { get().player.setup(appOptions) }.destroy()

    val controller = buildService()
    val player = controller.get().player
    assertEquals(appOptions.engineOptions(), player.engineOptions)
    val engine = player.exoPlayer
    player.setup(appOptions)

    assertSame(engine, player.exoPlayer)
    controller.destroy()
  }

  @Test
  fun `the module connecting before its setup keeps the engine`() {
    buildService().apply { get().player.setup(appOptions) }.destroy()

    val controller = buildService()
    val player = controller.get().player
    val engine = player.exoPlayer
    player.setupForModule(null)

    assertSame(engine, player.exoPlayer)
    controller.destroy()
  }

  @Test
  fun `stored options the engine cannot be built from fall back to the defaults`() {
    val withCache = PlayerSetupOptions(maxCacheSize = 10.0)
    buildService().apply { get().player.setup(withCache) }.destroy()
    val context = RuntimeEnvironment.getApplication()
    // A second SimpleCache on the folder throws, as the engine's would.
    val held =
      SimpleCache(
        File(context.cacheDir, "RNAB"),
        NoOpCacheEvictor(),
        StandaloneDatabaseProvider(context),
      )

    val controller = runCatching { buildService() }
    try {
      assertEquals(
        PlayerSetupOptions().engineOptions(),
        controller.getOrThrow().get().player.engineOptions,
      )
      assertEquals(withCache.engineOptions(), EngineOptionsStore.load(context))
    } finally {
      controller.getOrNull()?.destroy()
      held.release()
    }
  }
}
