package com.audiobrowser

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.player.PlaybackStateStore
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.PlaybackState
import java.time.Duration
import java.util.concurrent.TimeUnit
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

  @OptIn(UnstableApi::class)
  private fun connectController(packageName: String) {
    val application = RuntimeEnvironment.getApplication()
    shadowOf(application.packageManager)
      .setPackagesForUid(Process.myUid(), application.packageName, packageName)
    val context =
      object : ContextWrapper(application) {
        override fun getPackageName() = packageName
      }
    // Only to reach the private session: onGetSession ignores its argument.
    val session =
      service.onGetSession(
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
          packageName,
          0,
          Process.myUid(),
          0,
          0,
          false,
          Bundle.EMPTY,
          false,
        )
      )
    val future = MediaController.Builder(context, session.token).buildAsync()
    idle()
    // Throws if the session refused the controller.
    future.get()
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

  private fun playAndSavePositionAt61s() {
    playTrack("a")
    player.seekTo(61, TimeUnit.SECONDS)
    shadowOf(Looper.getMainLooper())
      .idleFor(Duration.ofMillis(PlaybackStateStore.PERIODIC_SAVE_INTERVAL_MS))
    assertEquals(61_000L, player.playbackStateStore.get()?.positionMs)
  }

  @Test
  fun `a removed task keeps the saved position while AudioBrowser is bound`() {
    controller.bind()
    playAndSavePositionAt61s()

    removeTask()

    assertEquals(61_000L, player.playbackStateStore.get()?.positionMs)
  }

  @Test
  fun `a removed task keeps the saved position when AudioBrowser never bound`() {
    playAndSavePositionAt61s()

    removeTask()

    assertEquals(61_000L, player.playbackStateStore.get()?.positionMs)
  }

  @Test
  fun `a stream swiped away before it has loaded resumes at its default position`() {
    controller.bind()
    // Unloaded, it is not yet known to be live and reports position 0, which is the window start
    // of a live stream.
    playTrack("a")

    removeTask()

    assertEquals(C.TIME_UNSET, player.playbackStateStore.get()?.positionMs)
  }

  @Test
  fun `a removed task clears the sleep timer of the player AudioBrowser kept`() {
    controller.bind()
    playTrack("a")
    player.sleepTimer.setAfter(1_800.0)

    removeTask()

    // The first variant is null: no timer.
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
    // Moves past BUFFERING only on events from a live engine.
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

  @Test
  fun `a removed task stops playback while the Bluetooth stack is connected as a controller`() {
    controller.bind()
    playTrack("a")
    connectController("com.google.android.bluetooth")

    removeTask()

    assertEquals(emptyList<String>(), player.tracks.map { it.title })
    assertEquals(PlaybackState.STOPPED, player.playbackState)
    assertTrue(shadowOf(service).isStoppedBySelf)
  }

  @Test
  fun `a removed task only pauses while Android Auto is connected`() {
    playTrack("a")
    connectController("com.google.android.projection.gearhead")

    removeTask()

    assertFalse(player.exoPlayer.isReleased)
    assertEquals(listOf("a"), player.tracks.map { it.title })
    assertFalse(player.exoPlayer.playWhenReady)
    assertTrue(shadowOf(service).isForegroundStopped)
    assertTrue(shadowOf(service).notificationShouldRemoved)
    assertFalse(shadowOf(service).isStoppedBySelf)
  }

  @Test
  fun `a removed task only pauses while Android Automotive is connected`() {
    playTrack("a")
    connectController("com.android.car.media")

    removeTask()

    assertFalse(player.exoPlayer.isReleased)
    assertEquals(listOf("a"), player.tracks.map { it.title })
  }
}
