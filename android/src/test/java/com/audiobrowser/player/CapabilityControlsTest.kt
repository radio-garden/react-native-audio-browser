package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.NotificationButton
import com.margelo.nitro.audiobrowser.NotificationButtonLayout
import com.margelo.nitro.audiobrowser.PlayerCapabilities
import com.margelo.nitro.audiobrowser.Variant_Boolean_FavoriteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth tables for the pure Capability decision core: [PlayerCapabilities.isEnabled] (one rule for
 * "available by default, only false disables; favorite is opt-in; play/pause is one control") and
 * [deriveNotificationSlots] (the single notification slot derivation that both the button layout
 * and the notification player commands consume).
 */
class CapabilityControlsTest {

  private fun capabilities(
    play: Boolean? = null,
    pause: Boolean? = null,
    stop: Boolean? = null,
    seekTo: Boolean? = null,
    skipToNext: Boolean? = null,
    skipToPrevious: Boolean? = null,
    jumpForward: Boolean? = null,
    jumpBackward: Boolean? = null,
    favorite: Boolean? = null,
  ) =
    PlayerCapabilities(
      play = play,
      pause = pause,
      stop = stop,
      seekTo = seekTo,
      skipToNext = skipToNext,
      skipToPrevious = skipToPrevious,
      jumpForward = jumpForward,
      jumpBackward = jumpBackward,
      favorite = favorite?.let { Variant_Boolean_FavoriteConfig.First(it) },
      shuffleMode = null,
      repeatMode = null,
      playbackRate = null,
    )

  private fun slots(
    capabilities: PlayerCapabilities,
    layout: NotificationButtonLayout? = null,
  ): List<Pair<NotificationButton, NotificationSlot>> =
    deriveNotificationSlots(capabilities, layout).map { it.button to it.slot }

  // MARK: isEnabled

  @Test
  fun `everything but favorite is enabled by default`() {
    val defaults = capabilities()
    for (control in Control.entries) {
      assertEquals("$control", control != Control.FAVORITE, defaults.isEnabled(control))
    }
  }

  @Test
  fun `only an explicit false disables a control`() {
    val caps = capabilities(skipToNext = false, jumpBackward = false)
    assertFalse(caps.isEnabled(Control.SKIP_TO_NEXT))
    assertFalse(caps.isEnabled(Control.JUMP_BACKWARD))
    assertTrue(caps.isEnabled(Control.SKIP_TO_PREVIOUS))
    assertTrue(caps.isEnabled(Control.JUMP_FORWARD))
  }

  @Test
  fun `play pause is one control - it survives a single half being disabled`() {
    assertTrue(capabilities(play = false).isEnabled(Control.PLAY_PAUSE))
    assertTrue(capabilities(pause = false).isEnabled(Control.PLAY_PAUSE))
    assertFalse(capabilities(play = false, pause = false).isEnabled(Control.PLAY_PAUSE))
  }

  @Test
  fun `favorite is opt-in`() {
    assertFalse(capabilities().isEnabled(Control.FAVORITE))
    assertFalse(capabilities(favorite = false).isEnabled(Control.FAVORITE))
    assertTrue(capabilities(favorite = true).isEnabled(Control.FAVORITE))
  }

  // MARK: deriveNotificationSlots — capability defaults

  @Test
  fun `default layout puts skip on the primary slots, jump on the secondary, favorite in overflow`() {
    val derived = slots(capabilities(favorite = true))
    assertEquals(
      listOf(
        NotificationButton.SKIP_TO_PREVIOUS to NotificationSlot.BACK,
        NotificationButton.SKIP_TO_NEXT to NotificationSlot.FORWARD,
        NotificationButton.JUMP_BACKWARD to NotificationSlot.BACK_SECONDARY,
        NotificationButton.JUMP_FORWARD to NotificationSlot.FORWARD_SECONDARY,
        NotificationButton.FAVORITE to NotificationSlot.OVERFLOW,
      ),
      derived,
    )
  }

  @Test
  fun `jump falls back to the primary slot when skip is disabled`() {
    val derived = slots(capabilities(skipToPrevious = false, skipToNext = false))
    assertEquals(
      listOf(
        NotificationButton.JUMP_BACKWARD to NotificationSlot.BACK,
        NotificationButton.JUMP_FORWARD to NotificationSlot.FORWARD,
      ),
      derived,
    )
  }

  @Test
  fun `a fully disabled side leaves its slot empty`() {
    val derived =
      slots(capabilities(skipToPrevious = false, jumpBackward = false, jumpForward = false))
    assertEquals(listOf(NotificationButton.SKIP_TO_NEXT to NotificationSlot.FORWARD), derived)
  }

  // MARK: deriveNotificationSlots — explicit layout

  @Test
  fun `explicit layout maps every slot and keeps overflow order`() {
    val layout =
      NotificationButtonLayout(
        back = NotificationButton.JUMP_BACKWARD,
        forward = NotificationButton.JUMP_FORWARD,
        backSecondary = null,
        forwardSecondary = null,
        overflow = arrayOf(NotificationButton.SKIP_TO_PREVIOUS, NotificationButton.SKIP_TO_NEXT),
      )
    val derived = slots(capabilities(), layout)
    assertEquals(
      listOf(
        NotificationButton.JUMP_BACKWARD to NotificationSlot.BACK,
        NotificationButton.JUMP_FORWARD to NotificationSlot.FORWARD,
        NotificationButton.SKIP_TO_PREVIOUS to NotificationSlot.OVERFLOW,
        NotificationButton.SKIP_TO_NEXT to NotificationSlot.OVERFLOW,
      ),
      derived,
    )
  }

  @Test
  fun `explicit layout entries are dropped when their capability is disabled`() {
    val layout =
      NotificationButtonLayout(
        back = NotificationButton.SKIP_TO_PREVIOUS,
        forward = NotificationButton.SKIP_TO_NEXT,
        backSecondary = null,
        forwardSecondary = null,
        overflow = arrayOf(NotificationButton.FAVORITE),
      )
    // favorite unset (= disabled), skipToPrevious explicitly off
    val derived = slots(capabilities(skipToPrevious = false), layout)
    assertEquals(listOf(NotificationButton.SKIP_TO_NEXT to NotificationSlot.FORWARD), derived)
  }
}
