package com.audiobrowser.cast

import androidx.media3.cast.Cast
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.PlaybackException
import com.audiobrowser.remote.RemotePlayback
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.api.GoogleApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

/**
 * The Cast SDK tells both [RemoteCastPlayer] and the remote of each session event, in an order it
 * doesn't promise; the player then lets go of its session at an end, an ending and a suspension
 * alike, and takes it up at a start and a resume.
 */
@RunWith(RobolectricTestRunner::class)
class SdkCastRemoteTest {
  private enum class First {
    PLAYER,
    REMOTE,
  }

  private val cast = mock(Cast::class.java)
  private val player = mock(RemoteCastPlayer::class.java)
  private val session = mock(CastSession::class.java)

  /** [RemotePlayback.isSessionAvailable] as each session change the listener heard of saw it. */
  private val seen = mutableListOf<Boolean>()

  private val remote =
    SdkCastRemote(cast, player).apply {
      setListener(
        object : RemotePlayback.Listener {
          override fun onSessionChanged() {
            seen += isSessionAvailable
          }

          override fun onPlaying() = Unit

          override fun onEnded() = Unit

          override fun onFailed(error: PlaybackException) = Unit
        }
      )
    }

  private val toRemote: SessionManagerListener<CastSession> = run {
    @Suppress("UNCHECKED_CAST")
    val captor =
      ArgumentCaptor.forClass(SessionManagerListener::class.java)
        as ArgumentCaptor<SessionManagerListener<CastSession>>
    verify(cast).addSessionManagerListener(captor.capture())
    captor.value
  }

  private val fromPlayer: SessionAvailabilityListener = run {
    val captor = ArgumentCaptor.forClass(SessionAvailabilityListener::class.java)
    verify(player).setSessionAvailabilityListener(captor.capture())
    captor.value
  }

  private fun playerTakesUpSession() {
    `when`(player.isCastSessionAvailable).thenReturn(true)
    fromPlayer.onCastSessionAvailable()
  }

  private fun playerLetsGoOfSession() {
    `when`(player.isCastSessionAvailable).thenReturn(false)
    fromPlayer.onCastSessionUnavailable()
  }

  private fun tell(first: First, thePlayer: () -> Unit, theRemote: () -> Unit) =
    if (first == First.PLAYER) {
      thePlayer()
      theRemote()
    } else {
      theRemote()
      thePlayer()
    }

  private fun startSession(first: First) =
    tell(first, ::playerTakesUpSession) { toRemote.onSessionStarted(session, "session") }

  private fun suspendSession(first: First) =
    tell(first, ::playerLetsGoOfSession) {
      toRemote.onSessionSuspended(session, GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST)
    }

  private fun resumeSession(first: First) =
    tell(first, ::playerTakesUpSession) { toRemote.onSessionResumed(session, true) }

  @Test
  fun `a suspended session stays the session, out of reach, whichever hears of it first`() {
    for (first in First.entries) {
      startSession(first)
      seen.clear()

      suspendSession(first)

      assertTrue("$first first", remote.isSessionAvailable)
      assertTrue("$first first", remote.isSuspended)
      assertFalse("$first first", false in seen)
    }
  }

  @Test
  fun `a session that resumes was never gone, and is in reach once the player takes it up`() {
    for (first in First.entries) {
      startSession(first)
      seen.clear()

      suspendSession(first)
      resumeSession(first)

      assertTrue("$first first", remote.isSessionAvailable)
      assertFalse("$first first", remote.isSuspended)
      assertFalse("$first first", false in seen)
    }
  }

  @Test
  fun `a resumed session is out of reach until the player takes it up`() {
    startSession(First.PLAYER)
    suspendSession(First.PLAYER)
    seen.clear()

    toRemote.onSessionResumed(session, true)
    assertTrue(remote.isSuspended)

    playerTakesUpSession()
    assertFalse(remote.isSuspended)
    assertEquals(listOf(true), seen)
  }

  @Test
  fun `a session that ends is gone, whichever hears of it first`() {
    for (first in First.entries) {
      startSession(first)
      seen.clear()

      tell(first, ::playerLetsGoOfSession) {
        toRemote.onSessionEnded(session, CastStatusCodes.SUCCESS)
      }

      assertFalse("$first first", remote.isSessionAvailable)
      assertEquals("$first first", false, seen.last())
    }
  }

  @Test
  fun `a session that is ending is gone, whichever hears of it first`() {
    for (first in First.entries) {
      startSession(first)
      seen.clear()

      tell(first, ::playerLetsGoOfSession) { toRemote.onSessionEnding(session) }

      assertFalse("$first first", remote.isSessionAvailable)
      assertEquals("$first first", false, seen.last())
    }
  }

  // The player only logs a failed resume: it let go of the session when that was suspended.
  @Test
  fun `a suspended session that fails to resume is gone, and the listener hears of it`() {
    startSession(First.PLAYER)
    suspendSession(First.PLAYER)
    seen.clear()

    toRemote.onSessionResumeFailed(session, CastStatusCodes.NETWORK_ERROR)

    assertFalse(remote.isSessionAvailable)
    assertFalse(remote.isSuspended)
    assertEquals(listOf(false), seen)
  }
}
