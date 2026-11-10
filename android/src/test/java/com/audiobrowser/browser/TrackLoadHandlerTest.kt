package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.track
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.TrackLoadEvent
import com.margelo.nitro.core.Promise
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLoadHandlerTest {

  @Test
  fun `a load cancelled while the handler waits on JS runs neither branch`() =
    runTest(timeout = 5.seconds) {
      val bridge = JsPromise<Promise<Unit>>().apply { resolve(JsPromise<Unit>().promise) }
      val handler: (TrackLoadEvent) -> Promise<Promise<Unit>> = { bridge.promise }
      var ran = false
      val station = track("Station")
      val load =
        backgroundScope.async {
          handleTrackLoad(
            handler,
            station,
            arrayOf(station),
            0.0,
            intercepted = { ran = true },
            defaultBehavior = { ran = true },
          )
        }
      runCurrent()

      load.cancel()
      runCurrent()

      assertTrue(load.isCompleted)
      assertFalse(ran)
    }
}
