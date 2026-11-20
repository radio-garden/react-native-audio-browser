package com.audiobrowser.cast

import com.google.android.gms.cast.MediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastEndsTest {
  private var lastSent: String? = null

  private val ends = CastEnds { lastSent }

  private fun load(n: Int) = "a#cast-send-$n"

  private fun send(n: Int) {
    lastSent = load(n)
  }

  private fun idle(contentId: String?, reason: Int = MediaStatus.IDLE_REASON_NONE) =
    ends.report(contentId, MediaStatus.PLAYER_STATE_IDLE, reason)

  @Test
  fun `each load's end is reported once`() {
    send(1)
    idle(load(1))
    assertEquals(CastEnds.Report.ENDED, idle(load(1), MediaStatus.IDLE_REASON_FINISHED))
    assertNull(idle(load(1), MediaStatus.IDLE_REASON_FINISHED))
  }

  // As the Default Media Receiver reports a load it can't fetch: IDLE with the load's media, then
  // IDLE/ERROR without media.
  @Test
  fun `two loads that fail in a row are each reported`() {
    send(1)
    idle(load(1))
    assertEquals(CastEnds.Report.FAILED, idle(null, MediaStatus.IDLE_REASON_ERROR))
    assertNull(idle(null, MediaStatus.IDLE_REASON_ERROR))
    send(2)
    idle(load(2))
    assertEquals(CastEnds.Report.FAILED, idle(null, MediaStatus.IDLE_REASON_ERROR))
  }

  @Test
  fun `a failure that arrives after the next load was sent is the earlier load's, and dropped`() {
    send(1)
    idle(load(1))
    send(2)

    assertNull(idle(null, MediaStatus.IDLE_REASON_ERROR))

    idle(load(2))
    assertEquals(CastEnds.Report.FAILED, idle(null, MediaStatus.IDLE_REASON_ERROR))
  }

  @Test
  fun `a load that ends straight after the last one is reported`() {
    send(1)
    assertEquals(CastEnds.Report.ENDED, idle(load(1), MediaStatus.IDLE_REASON_FINISHED))
    send(2)
    assertEquals(CastEnds.Report.ENDED, idle(load(2), MediaStatus.IDLE_REASON_FINISHED))
  }

  @Test
  fun `playing, an idle load not yet started, and an interrupted one report nothing`() {
    send(1)
    assertNull(ends.report(load(1), MediaStatus.PLAYER_STATE_PLAYING, MediaStatus.IDLE_REASON_NONE))
    assertNull(idle(load(1)))
    assertNull(idle(load(1), MediaStatus.IDLE_REASON_INTERRUPTED))
    assertNull(idle(load(1), MediaStatus.IDLE_REASON_CANCELED))
  }

  @Test
  fun `a new session reports a load it reported before`() {
    send(1)
    idle(load(1), MediaStatus.IDLE_REASON_FINISHED)

    ends.reset()

    assertEquals(CastEnds.Report.ENDED, idle(load(1), MediaStatus.IDLE_REASON_FINISHED))
  }

  // The receiver failed the load while the session was suspended.
  @Test
  fun `a failure without media that a resumed session reports first is the load's`() {
    send(1)
    idle(load(1))

    ends.reset()

    assertEquals(CastEnds.Report.FAILED, idle(null, MediaStatus.IDLE_REASON_ERROR))
  }

  @Test
  fun `a load this side never sent reports nothing`() {
    assertNull(idle(load(1), MediaStatus.IDLE_REASON_FINISHED))
    assertNull(idle(null, MediaStatus.IDLE_REASON_ERROR))
  }
}
