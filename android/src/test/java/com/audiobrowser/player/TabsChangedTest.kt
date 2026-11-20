package com.audiobrowser.player

import android.os.Looper
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.LibraryParams
import com.audiobrowser.buildService
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.session
import com.audiobrowser.util.BrowserPathHelper
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TabsChangedTest {

  private val controller = buildService()
  private val service = controller.get()

  @After
  fun destroy() {
    controller.destroy()
  }

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  // Some notifies hop through a background dispatcher before they reach main.
  private fun idleUntil(done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 2_000
    while (!done() && System.currentTimeMillis() < deadline) {
      idle()
      Thread.sleep(10)
    }
    idle()
  }

  @Test
  fun `a tabs change tells a browser subscribed to the root to re-query it`() {
    // Hands the session to the callback, which notifies through it.
    service.player.applyOptions(
      PlayerUpdateOptions().let { it.copy(capabilities = it.capabilities.copy(favorite = true)) }
    )
    val changed = CopyOnWriteArrayList<String>()
    val future =
      MediaBrowser.Builder(RuntimeEnvironment.getApplication(), service.session.token)
        .setListener(
          object : MediaBrowser.Listener {
            override fun onChildrenChanged(
              browser: MediaBrowser,
              parentId: String,
              itemCount: Int,
              params: LibraryParams?,
            ) {
              changed += parentId
            }
          }
        )
        .buildAsync()
    idle()
    val browser = future.get()
    try {
      browser.subscribe(BrowserPathHelper.ROOT_PATH, null)
      // A Media3 browser is told to fetch as it subscribes.
      idleUntil { changed.isNotEmpty() }
      assertEquals(listOf(BrowserPathHelper.ROOT_PATH), changed.toList())
      changed.clear()

      service.player.notifyTabsChanged()
      idleUntil { changed.isNotEmpty() }

      assertEquals(listOf(BrowserPathHelper.ROOT_PATH), changed.toList())
    } finally {
      browser.release()
    }
  }
}
