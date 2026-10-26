package com.audiobrowser

import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Looper
import com.audiobrowser.model.PlayerUpdateOptions
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.PlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// Robolectric: the Service builds a real ExoPlayer and MediaLibrarySession, and ServiceController
// drives onBind/onUnbind/onRebind as the framework would.
@RunWith(RobolectricTestRunner::class)
class ServiceTaskRemovedTest {

  private val controller = run {
    // Robolectric connects an unregistered binding with a null ComponentName, which the
    // HeadlessTaskService connection's non-null parameter rejects.
    val application = RuntimeEnvironment.getApplication()
    shadowOf(application)
      .setComponentNameAndServiceForBindService(
        ComponentName(application, HeadlessTaskService::class.java),
        Binder(),
      )
    Robolectric.buildService(Service::class.java).create()
  }
  private val service = controller.get()
  private val player = service.player

  @After
  fun destroy() {
    controller.destroy()
  }

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  private fun playTrack(title: String) {
    player.load(TestFixtures.track(title = title, src = "https://s/$title.mp3"))
    player.play()
    idle()
  }

  private fun removeTask(behavior: AppKilledPlaybackBehavior? = null) {
    behavior?.let { player.applyOptions(PlayerUpdateOptions(appKilledPlaybackBehavior = it)) }
    service.onTaskRemoved(null)
    idle()
  }

  @Test
  fun `a removed task stops playback and keeps the player while AudioBrowser is bound`() {
    controller.bind()
    playTrack("a")

    removeTask()

    assertFalse(player.exoPlayer.isReleased)
    assertEquals(emptyList<String>(), player.tracks.map { it.title })
    assertFalse(player.exoPlayer.playWhenReady)
    assertEquals(PlaybackState.STOPPED, player.playbackState)
    assertTrue(shadowOf(service).isForegroundStopped)
    assertTrue(shadowOf(service).notificationShouldRemoved)
    assertTrue(shadowOf(service).isStoppedBySelf)
  }

  @Test
  fun `a removed task clears the sleep timer of the player AudioBrowser kept`() {
    controller.bind()
    playTrack("a")
    player.sleepTimer.setAfter(1_800.0)

    removeTask()

    assertTrue(player.sleepTimer.get().isFirst)
  }

  @Test
  fun `the player AudioBrowser kept plays again after the task is removed`() {
    controller.bind()
    playTrack("a")
    removeTask()

    playTrack("b")

    assertFalse(player.exoPlayer.isReleased)
    assertEquals(listOf("b"), player.tracks.map { it.title })
    // Moves only on events from a live engine.
    assertEquals(PlaybackState.BUFFERING, player.playbackState)
  }

  @Test
  fun `a removed task destroys the player when AudioBrowser never bound`() {
    playTrack("a")

    removeTask()

    assertTrue(player.exoPlayer.isReleased)
    assertTrue(shadowOf(service).isStoppedBySelf)
  }

  @Test
  fun `a removed task destroys the player once AudioBrowser has unbound`() {
    controller.bind()
    controller.unbind()
    playTrack("a")

    removeTask()

    assertTrue(player.exoPlayer.isReleased)
  }

  @Test
  fun `AudioBrowser binding again after an unbind keeps the player through a removed task`() {
    controller.bind()
    // Called directly: ServiceController.unbind() drops the result, and rebind() calls onRebind
    // whatever it was, where Android calls it only after true.
    assertTrue(service.onUnbind(Intent(service, Service::class.java)))
    controller.rebind()
    playTrack("a")

    removeTask()

    assertFalse(player.exoPlayer.isReleased)
  }

  @Test
  fun `a removed task pauses and keeps the queue with PAUSE_PLAYBACK`() {
    controller.bind()
    playTrack("a")

    removeTask(AppKilledPlaybackBehavior.PAUSE_PLAYBACK)

    assertFalse(player.exoPlayer.isReleased)
    assertEquals(listOf("a"), player.tracks.map { it.title })
    assertFalse(player.exoPlayer.playWhenReady)
    assertFalse(shadowOf(service).isStoppedBySelf)
  }

  @Test
  fun `a removed task leaves playback running with CONTINUE_PLAYBACK`() {
    controller.bind()
    playTrack("a")

    removeTask(AppKilledPlaybackBehavior.CONTINUE_PLAYBACK)

    assertEquals(listOf("a"), player.tracks.map { it.title })
    assertTrue(player.exoPlayer.playWhenReady)
    assertFalse(shadowOf(service).isStoppedBySelf)
  }
}
