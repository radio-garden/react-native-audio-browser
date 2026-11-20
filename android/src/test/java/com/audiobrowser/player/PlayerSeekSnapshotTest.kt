package com.audiobrowser.player

import android.os.Looper
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.TrackFactory
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlayerSeekSnapshotTest {

  private val player = Player(RuntimeEnvironment.getApplication())

  @Before
  fun load() {
    player.setup(PlayerSetupOptions())
    player.sessionPlayer.setMediaItems(
      listOf(TrackFactory.toMedia3(TestFixtures.track(title = "a", src = "https://s/a.mp3"))),
      0,
      120_000,
    )
    player.sessionPlayer.prepare()
    shadowOf(Looper.getMainLooper()).idle()
  }

  @After fun destroy() = player.destroy()

  @Test
  fun aSeekWhilePausedSavesWhereItLands() {
    player.playbackStateStore.save()

    player.seekTo(30_000, TimeUnit.MILLISECONDS)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(30_000L, player.playbackStateStore.get()?.positionMs)
  }
}
