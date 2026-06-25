package com.audiobrowser.destination.sonos

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UPnP `CurrentTransportState` → Media3 playback state + isPlaying. */
class TransportStateMapperTest {

  @Test
  fun `playing maps to ready and playing`() {
    val s = TransportStateMapper.map("PLAYING")
    assertEquals(Player.STATE_READY, s.playbackState)
    assertTrue(s.isPlaying)
  }

  @Test
  fun `paused maps to ready and not playing`() {
    val s = TransportStateMapper.map("PAUSED_PLAYBACK")
    assertEquals(Player.STATE_READY, s.playbackState)
    assertFalse(s.isPlaying)
  }

  @Test
  fun `transitioning maps to buffering`() {
    val s = TransportStateMapper.map("TRANSITIONING")
    assertEquals(Player.STATE_BUFFERING, s.playbackState)
    assertFalse(s.isPlaying)
  }

  @Test
  fun `stopped and no-media map to idle`() {
    assertEquals(Player.STATE_IDLE, TransportStateMapper.map("STOPPED").playbackState)
    assertEquals(Player.STATE_IDLE, TransportStateMapper.map("NO_MEDIA_PRESENT").playbackState)
  }

  @Test
  fun `unknown state is treated as idle and not playing`() {
    val s = TransportStateMapper.map("WAT")
    assertEquals(Player.STATE_IDLE, s.playbackState)
    assertFalse(s.isPlaying)
  }

  @Test
  fun `mapping is case-insensitive`() {
    assertTrue(TransportStateMapper.map("playing").isPlaying)
  }
}
