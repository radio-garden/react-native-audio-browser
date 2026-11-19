package com.audiobrowser.player

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryFocusTest {
  private val log = mutableListOf<String>()
  private var answer = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  private var listener: AudioManager.OnAudioFocusChangeListener? = null
  private var losses = 0

  private val focus: RetryFocus =
    RetryFocus(
      object : RetryFocus.Port {
        override fun request(listener: AudioManager.OnAudioFocusChangeListener): Int {
          log += "request"
          this@RetryFocusTest.listener = listener
          return answer
        }

        override fun abandon() {
          log += "abandon"
        }
      },
      onLoss = { losses++ },
      onSuppressionChanged = { log += "suppressed=${focus.suppressed}" },
    )

  @Test
  fun `the reconnect's prepare requests the engine's focus before this one is let go`() {
    focus.hold()
    val held = listener!!

    focus.handOver { log += "prepare" }
    // ExoPlayer's request in the prepare takes the focus from this one's listener.
    held.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

    assertEquals(listOf("request", "prepare", "abandon"), log)
    assertEquals("the engine's own request read as a loss", 0, losses)
  }

  @Test
  fun `another app taking the focus during the wait is a loss`() {
    focus.hold()

    listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

    assertEquals(1, losses)
    assertEquals(listOf("request", "abandon"), log)
  }

  @Test
  fun `a transient loss suppresses until the focus returns`() {
    focus.hold()

    listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    assertTrue(focus.suppressed)
    listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

    assertFalse(focus.suppressed)
    assertEquals(0, losses)
    assertEquals(listOf("request", "suppressed=true", "suppressed=false"), log)
  }

  @Test
  fun `a request that waits, as during a call, suppresses until it is granted`() {
    answer = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
    focus.hold()
    assertTrue(focus.suppressed)

    listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

    assertFalse(focus.suppressed)
  }

  @Test
  fun `a refused request is a loss`() {
    answer = AudioManager.AUDIOFOCUS_REQUEST_FAILED

    focus.hold()

    assertEquals(1, losses)
  }

  @Test
  fun `a ducking loss changes nothing, as no audio plays`() {
    focus.hold()

    listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

    assertFalse(focus.suppressed)
    assertEquals(0, losses)
  }
}
