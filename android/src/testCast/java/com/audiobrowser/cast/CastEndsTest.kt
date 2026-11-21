package com.audiobrowser.cast

import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The status sequences are a Google TV Streamer's, running the Default Media Receiver. */
class CastEndsTest {
  private var lastSent: String? = null
  private var sends = 0

  private val ends = CastEnds { lastSent }

  private fun send() {
    lastSent = "a#cast-send-${sends++}"
  }

  private fun idle(item: Int, reason: Int = MediaStatus.IDLE_REASON_NONE) =
    ends.report(item, MediaStatus.PLAYER_STATE_IDLE, reason)

  private fun playing(item: Int) =
    ends.report(item, MediaStatus.PLAYER_STATE_PLAYING, MediaStatus.IDLE_REASON_NONE)

  @Test
  fun `each load's end is reported once`() {
    send()
    playing(1)
    assertEquals(CastEnds.Report.ENDED, idle(1, MediaStatus.IDLE_REASON_FINISHED))
    assertNull(idle(1, MediaStatus.IDLE_REASON_FINISHED))
  }

  @Test
  fun `a load sent while another plays, which fails at once, is reported`() {
    send()
    playing(2)
    send()

    assertNull(idle(2, MediaStatus.IDLE_REASON_INTERRUPTED))
    assertEquals(CastEnds.Report.FAILED, idle(3, MediaStatus.IDLE_REASON_ERROR))
  }

  @Test
  fun `a load sent after a stop, which fails, is reported`() {
    send()
    playing(4)
    send()

    assertNull(idle(4, MediaStatus.IDLE_REASON_CANCELED))
    assertNull(idle(5))
    assertEquals(CastEnds.Report.FAILED, idle(5, MediaStatus.IDLE_REASON_ERROR))
  }

  @Test
  fun `a failure of the load held when the next was sent is that load's, and dropped`() {
    send()
    playing(1)
    send()

    assertNull(idle(1, MediaStatus.IDLE_REASON_ERROR))
    assertEquals(CastEnds.Report.FAILED, idle(2, MediaStatus.IDLE_REASON_ERROR))
  }

  @Test
  fun `two loads that fail in a row are each reported`() {
    send()
    assertEquals(CastEnds.Report.FAILED, idle(1, MediaStatus.IDLE_REASON_ERROR))
    assertNull(idle(1, MediaStatus.IDLE_REASON_ERROR))
    send()
    assertEquals(CastEnds.Report.FAILED, idle(2, MediaStatus.IDLE_REASON_ERROR))
  }

  @Test
  fun `a status without an item id counts as the last item seen`() {
    send()
    playing(1)

    assertEquals(
      CastEnds.Report.FAILED,
      idle(MediaQueueItem.INVALID_ITEM_ID, MediaStatus.IDLE_REASON_ERROR),
    )
    assertNull(idle(1, MediaStatus.IDLE_REASON_ERROR))
  }

  @Test
  fun `a load that ends straight after the last one is reported`() {
    send()
    assertEquals(CastEnds.Report.ENDED, idle(1, MediaStatus.IDLE_REASON_FINISHED))
    send()
    assertEquals(CastEnds.Report.ENDED, idle(2, MediaStatus.IDLE_REASON_FINISHED))
  }

  @Test
  fun `playing, an idle load not yet started, and an interrupted one report nothing`() {
    send()
    assertNull(playing(1))
    assertNull(idle(1))
    assertNull(idle(1, MediaStatus.IDLE_REASON_INTERRUPTED))
    assertNull(idle(1, MediaStatus.IDLE_REASON_CANCELED))
  }

  @Test
  fun `a new session reports a load it reported before`() {
    send()
    idle(1, MediaStatus.IDLE_REASON_FINISHED)

    ends.reset()

    assertEquals(CastEnds.Report.ENDED, idle(1, MediaStatus.IDLE_REASON_FINISHED))
  }

  // The receiver failed the load while the session was suspended.
  @Test
  fun `a failure that a resumed session reports first is the load's`() {
    send()
    playing(1)

    ends.reset()

    assertEquals(CastEnds.Report.FAILED, idle(1, MediaStatus.IDLE_REASON_ERROR))
  }
}
