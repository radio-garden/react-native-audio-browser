package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.PlayerCapabilities
import com.margelo.nitro.audiobrowser.RemoteButton
import com.margelo.nitro.audiobrowser.RemoteButtonLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth tables for the pure Capability decision core: [PlayerCapabilities.isEnabled] (one rule for
 * "available by default, only false disables; favorite is opt-in; play/pause is one control") and
 * [deriveButtonSlots] (the single slot derivation every Media3 assembly consumes).
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
      favorite = favorite,
      shuffleMode = null,
      repeatMode = null,
      playbackRate = null,
    )

  private fun layout(
    back: RemoteButton? = null,
    forward: RemoteButton? = null,
    overflow: Array<RemoteButton> = emptyArray(),
  ) = RemoteButtonLayout(back = back, forward = forward, overflow = overflow)

  private fun slots(
    capabilities: PlayerCapabilities,
    layout: RemoteButtonLayout? = null,
  ): List<Pair<RemoteButton, ButtonSlot>> =
    deriveButtonSlots(capabilities, layout).map { it.button to it.slot }

  // MARK: isEnabled

  @Test
  fun `everything but favorite is enabled by default`() {
    val defaults = capabilities()
    for (control in Capability.entries) {
      assertEquals("$control", control != Capability.FAVORITE, defaults.isEnabled(control))
    }
  }

  @Test
  fun `only an explicit false disables a control`() {
    val caps = capabilities(skipToNext = false, jumpBackward = false)
    assertFalse(caps.isEnabled(Capability.SKIP_TO_NEXT))
    assertFalse(caps.isEnabled(Capability.JUMP_BACKWARD))
    assertTrue(caps.isEnabled(Capability.SKIP_TO_PREVIOUS))
    assertTrue(caps.isEnabled(Capability.JUMP_FORWARD))
  }

  @Test
  fun `play pause is one control - it survives a single half being disabled`() {
    assertTrue(capabilities(play = false).isEnabled(Capability.PLAY_PAUSE))
    assertTrue(capabilities(pause = false).isEnabled(Capability.PLAY_PAUSE))
    assertFalse(capabilities(play = false, pause = false).isEnabled(Capability.PLAY_PAUSE))
  }

  @Test
  fun `favorite is opt-in`() {
    assertFalse(capabilities().isEnabled(Capability.FAVORITE))
    assertFalse(capabilities(favorite = false).isEnabled(Capability.FAVORITE))
    assertTrue(capabilities(favorite = true).isEnabled(Capability.FAVORITE))
  }

  // MARK: deriveButtonSlots — capability defaults

  @Test
  fun `default layout puts skip on the primary positions, jump and favorite in overflow`() {
    val derived = slots(capabilities(favorite = true))
    assertEquals(
      listOf(
        RemoteButton.SKIP_TO_PREVIOUS to ButtonSlot.BACK,
        RemoteButton.SKIP_TO_NEXT to ButtonSlot.FORWARD,
        RemoteButton.JUMP_BACKWARD to ButtonSlot.OVERFLOW,
        RemoteButton.JUMP_FORWARD to ButtonSlot.OVERFLOW,
        RemoteButton.FAVORITE to ButtonSlot.OVERFLOW,
      ),
      derived,
    )
  }

  @Test
  fun `jump falls back to a primary position when skip is disabled`() {
    val derived = slots(capabilities(skipToPrevious = false, skipToNext = false))
    assertEquals(
      listOf(
        RemoteButton.JUMP_BACKWARD to ButtonSlot.BACK,
        RemoteButton.JUMP_FORWARD to ButtonSlot.FORWARD,
      ),
      derived,
    )
  }

  @Test
  fun `a fully disabled side leaves its position empty`() {
    val derived =
      slots(capabilities(skipToPrevious = false, jumpBackward = false, jumpForward = false))
    assertEquals(listOf(RemoteButton.SKIP_TO_NEXT to ButtonSlot.FORWARD), derived)
  }

  // MARK: deriveButtonSlots — explicit layout

  @Test
  fun `an explicit layout maps every field and keeps overflow order`() {
    val derived =
      slots(
        capabilities(),
        layout(
          back = RemoteButton.JUMP_BACKWARD,
          forward = RemoteButton.JUMP_FORWARD,
          overflow = arrayOf(RemoteButton.SKIP_TO_PREVIOUS, RemoteButton.SKIP_TO_NEXT),
        ),
      )
    assertEquals(
      listOf(
        RemoteButton.JUMP_BACKWARD to ButtonSlot.BACK,
        RemoteButton.JUMP_FORWARD to ButtonSlot.FORWARD,
        RemoteButton.SKIP_TO_PREVIOUS to ButtonSlot.OVERFLOW,
        RemoteButton.SKIP_TO_NEXT to ButtonSlot.OVERFLOW,
      ),
      derived,
    )
  }

  @Test
  fun `a null field leaves that position empty`() {
    val derived = slots(capabilities(), layout(back = null, forward = RemoteButton.SKIP_TO_NEXT))
    assertEquals(listOf(RemoteButton.SKIP_TO_NEXT to ButtonSlot.FORWARD), derived)
  }

  @Test
  fun `a layout is never merged with the capability defaults`() {
    // Nitro collapses an omitted and a null enum field to the same Kotlin null, so a per-field
    // merge is not expressible: an empty position must stay empty rather than falling back to the
    // capability default. Every capability here is enabled, so a merge would add skip and jump.
    val derived =
      slots(capabilities(favorite = true), layout(overflow = arrayOf(RemoteButton.FAVORITE)))
    assertEquals(listOf(RemoteButton.FAVORITE to ButtonSlot.OVERFLOW), derived)
  }

  @Test
  fun `a disabled entry drops out without promoting anything into its position`() {
    // favorite unset (= disabled), skipToPrevious explicitly off. skip-to-next must stay on
    // FORWARD rather than sliding into the vacated BACK position.
    val derived =
      slots(
        capabilities(skipToPrevious = false),
        layout(
          back = RemoteButton.SKIP_TO_PREVIOUS,
          forward = RemoteButton.SKIP_TO_NEXT,
          overflow = arrayOf(RemoteButton.FAVORITE),
        ),
      )
    assertEquals(listOf(RemoteButton.SKIP_TO_NEXT to ButtonSlot.FORWARD), derived)
  }

  // MARK: sameAs

  @Test
  fun `sameAs compares overflow by content, not by array identity`() {
    val a = layout(back = RemoteButton.JUMP_BACKWARD, overflow = arrayOf(RemoteButton.FAVORITE))
    val b = layout(back = RemoteButton.JUMP_BACKWARD, overflow = arrayOf(RemoteButton.FAVORITE))
    // Distinct Array instances — data-class `==` would call these different and republish the
    // layout to Android Auto on every unrelated options update.
    assertTrue(a.sameAs(b))
    assertFalse(a.sameAs(layout(back = RemoteButton.JUMP_BACKWARD)))
    assertFalse(
      a.sameAs(layout(back = RemoteButton.JUMP_FORWARD, overflow = arrayOf(RemoteButton.FAVORITE)))
    )
  }

  @Test
  fun `sameAs treats null as its own layout`() {
    assertTrue(null.sameAs(null))
    assertFalse(layout(back = RemoteButton.SKIP_TO_NEXT).sameAs(null))
    assertFalse(null.sameAs(layout(back = RemoteButton.SKIP_TO_NEXT)))
  }
}
