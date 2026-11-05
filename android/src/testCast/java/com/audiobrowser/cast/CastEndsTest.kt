package com.audiobrowser.cast

import com.google.android.gms.cast.MediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastEndsTest {
  private val ends = CastEnds()

  private fun load(n: Int) = "a#cast-send-$n"

  private fun idle(contentId: String?, reason: Int = MediaStatus.IDLE_REASON_NONE) =
    ends.report(contentId, MediaStatus.PLAYER_STATE_IDLE, reason)

  @Test
  fun `each load's end is reported once`() {
    idle(load(1))
    assertEquals(CastEnds.Report.ENDED, idle(load(1), MediaStatus.IDLE_REASON_FINISHED))
    assertNull(idle(load(1), MediaStatus.IDLE_REASON_FINISHED))
  }

  // As the Default Media Receiver reports a load it can't fetch: IDLE with the load's media, then
  // IDLE/ERROR without media.
  @Test
  fun `two loads that fail in a row are each reported`() {
    idle(load(1))
    assertEquals(CastEnds.Report.FAILED, idle(null, MediaStatus.IDLE_REASON_ERROR))
    assertNull(idle(null, MediaStatus.IDLE_REASON_ERROR))
    idle(load(2))
    assertEquals(CastEnds.Report.FAILED, idle(null, MediaStatus.IDLE_REASON_ERROR))
  }

  @Test
  fun `a load that ends straight after the last one is reported`() {
    assertEquals(CastEnds.Report.ENDED, idle(load(1), MediaStatus.IDLE_REASON_FINISHED))
    assertEquals(CastEnds.Report.ENDED, idle(load(2), MediaStatus.IDLE_REASON_FINISHED))
  }

  @Test
  fun `playing, an idle load not yet started, and an interrupted one report nothing`() {
    assertNull(ends.report(load(1), MediaStatus.PLAYER_STATE_PLAYING, MediaStatus.IDLE_REASON_NONE))
    assertNull(idle(load(1)))
    assertNull(idle(load(1), MediaStatus.IDLE_REASON_INTERRUPTED))
    assertNull(idle(load(1), MediaStatus.IDLE_REASON_CANCELED))
  }

  @Test
  fun `a new session reports a load it reported before`() {
    idle(load(1), MediaStatus.IDLE_REASON_FINISHED)

    ends.reset()

    assertEquals(CastEnds.Report.ENDED, idle(load(1), MediaStatus.IDLE_REASON_FINISHED))
  }
}
