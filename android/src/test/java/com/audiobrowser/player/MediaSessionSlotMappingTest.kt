package com.audiobrowser.player

import androidx.media3.session.CommandButton
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
