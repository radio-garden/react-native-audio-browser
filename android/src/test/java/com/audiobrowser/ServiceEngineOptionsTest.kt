package com.audiobrowser

import android.content.ContextWrapper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import com.audiobrowser.model.EngineOptionsStore
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.player.Player
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
  fun `a locked cache folder builds the defaults and keeps the stored options`() {
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

  @Test
  fun `stored options the engine throws on are replaced by the defaults`() =
    assertReplacedAfter(SecurityException("no cache folder"))

  @Test
  fun `stored options failing a state check are replaced by the defaults`() =
    assertReplacedAfter(IllegalStateException("no cache folder"))

  private fun assertReplacedAfter(failure: Exception) {
    val context = RuntimeEnvironment.getApplication()
    EngineOptionsStore.save(context, PlayerSetupOptions(maxCacheSize = 10.0))
    val unreadableCache =
      object : ContextWrapper(context) {
        override fun getApplicationContext() = this

        override fun getCacheDir(): File = throw failure
      }
    val player = Player(unreadableCache)
    try {
      player.setupFromStore()

      assertEquals(PlayerSetupOptions().engineOptions(), player.engineOptions)
      assertEquals(PlayerSetupOptions().engineOptions(), EngineOptionsStore.load(context))
    } finally {
      player.destroy()
    }
  }

  @Test
  fun `stored options failing after the engine is built release that engine`() {
    val context = RuntimeEnvironment.getApplication()
    EngineOptionsStore.save(context, appOptions)
    lateinit var player: Player
    var failedEngine: ExoPlayer? = null
    // The retry policy's locks ask for Wi-Fi once the engine exists.
    val noWifi =
      object : ContextWrapper(context) {
        override fun getApplicationContext() = this

        override fun getSystemService(name: String): Any? {
          if (name != WIFI_SERVICE) return super.getSystemService(name)
          failedEngine = player.exoPlayer
          throw SecurityException("no Wi-Fi")
        }
      }
    player = Player(noWifi)
    try {
      player.setupFromStore()

      assertTrue(checkNotNull(failedEngine).isReleased)
      assertFalse(player.exoPlayer.isReleased)
    } finally {
      player.destroy()
    }
  }
}
