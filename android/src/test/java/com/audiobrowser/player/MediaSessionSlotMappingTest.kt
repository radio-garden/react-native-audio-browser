package com.audiobrowser.player

import androidx.media3.session.CommandButton
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the slot preference lists handed to Media3.
 *
 * The invariant that matters is the OVERFLOW fallback: every flattening keeps a button only if it
 * won SLOT_BACK, won SLOT_FORWARD, or names SLOT_OVERFLOW. A slot that stops naming OVERFLOW
 * silently deletes its button from the notification and the car the moment its primary position is
 * unavailable, so the loop below asserts the property for every slot rather than only today's
 * three.
 */
class MediaSessionSlotMappingTest {

  @Test
  fun `primary slots are preferred with an overflow fallback`() {
    assertArrayEquals(
      intArrayOf(CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW),
      media3SlotsFor(ButtonSlot.BACK),
    )
    assertArrayEquals(
      intArrayOf(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW),
      media3SlotsFor(ButtonSlot.FORWARD),
    )
  }

  @Test
  fun `overflow does not repeat itself`() {
    assertArrayEquals(intArrayOf(CommandButton.SLOT_OVERFLOW), media3SlotsFor(ButtonSlot.OVERFLOW))
  }

  @Test
  fun `every slot names OVERFLOW so nothing can be dropped`() {
    for (slot in ButtonSlot.entries) {
      assertTrue(
        "$slot must name SLOT_OVERFLOW or its button is dropped from the notification and the car",
        media3SlotsFor(slot).contains(CommandButton.SLOT_OVERFLOW),
      )
    }
  }
}
