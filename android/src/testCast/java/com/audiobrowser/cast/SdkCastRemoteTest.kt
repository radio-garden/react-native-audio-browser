package com.audiobrowser.cast

import androidx.media3.cast.Cast
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.PlaybackException
import com.audiobrowser.remote.RemotePlayback
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.cast.framework.media.RemoteMediaClient.MediaChannelResult
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.ResultCallback
import com.google.android.gms.common.api.Status
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
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
  private val client = mock(RemoteMediaClient::class.java)
  private val session = mock(CastSession::class.java).also {
    `when`(it.remoteMediaClient).thenReturn(client)
  }

  /** The status requests sent, oldest first. */
  private val requests = mutableListOf<PendingResult<MediaChannelResult>>()

  init {
    `when`(client.requestStatus()).thenAnswer {
      @Suppress("UNCHECKED_CAST")
      (mock(PendingResult::class.java) as PendingResult<MediaChannelResult>).also { requests += it }
    }
  }

  private var networkRegained: (() -> Unit)? = null
  private var networkWatched = false

  /** [RemotePlayback.isSessionAvailable] as each session change the listener heard of saw it. */
  private val seen = mutableListOf<Boolean>()

  private var sends = 0L

  private val failures = mutableListOf<PlaybackException>()

  private val remote =
    SdkCastRemote(cast, player, { sends }) { onRegained ->
        networkRegained = onRegained
        networkWatched = true
        return@SdkCastRemote { networkWatched = false }
      }
      .apply {
      setListener(
        object : RemotePlayback.Listener {
          override fun onSessionChanged() {
            seen += isSessionAvailable
          }

          override fun onPlaying() = Unit

          override fun onEnded() = Unit

          override fun onFailed(error: PlaybackException) {
            failures += error
          }
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
    verify(client).unregisterCallback(any())
  }

  // After a loss it reports neither as a suspension nor as an end, the SDK can give up on the
  // session it holds with a failed start; the player only logs that, and keeps the session.
  @Test
  fun `a held session whose start fails is gone, and the listener hears of it`() {
    startSession(First.PLAYER)
    seen.clear()

    toRemote.onSessionStartFailed(session, CastStatusCodes.NETWORK_ERROR)

    assertFalse(remote.isSessionAvailable)
    assertEquals(listOf(false), seen)
  }

  @Test
  fun `a suspended session whose start fails is gone, and the listener hears of it`() {
    startSession(First.PLAYER)
    suspendSession(First.PLAYER)
    seen.clear()

    toRemote.onSessionStartFailed(session, CastStatusCodes.NETWORK_ERROR)

    assertFalse(remote.isSessionAvailable)
    assertFalse(remote.isSuspended)
    assertEquals(listOf(false), seen)
  }

  @Test
  fun `a start that fails without a session tells the listener nothing`() {
    toRemote.onSessionStartFailed(session, CastStatusCodes.NETWORK_ERROR)

    assertFalse(remote.isSessionAvailable)
    assertEquals(emptyList<Boolean>(), seen)
  }

  /** Answers the last status request as the SDK does: [statusCode], or a timeout for no answer. */
  private fun answer(statusCode: Int) {
    @Suppress("UNCHECKED_CAST")
    val captor =
      ArgumentCaptor.forClass(ResultCallback::class.java)
        as ArgumentCaptor<ResultCallback<MediaChannelResult>>
    verify(requests.last()).setResultCallback(captor.capture(), anyLong(), eq(TimeUnit.MILLISECONDS))
    val result = mock(MediaChannelResult::class.java)
    `when`(result.status).thenReturn(Status(statusCode))
    captor.value.onResult(result)
  }

  @Test
  fun `a session whose receiver no longer answers once the network is back is gone, and ended`() {
    startSession(First.PLAYER)
    seen.clear()

    networkRegained!!()
    answer(CommonStatusCodes.TIMEOUT)

    assertFalse(remote.isSessionAvailable)
    assertEquals(listOf(false), seen)
    verify(cast).endCurrentSession(false)
  }

  @Test
  fun `a session whose receiver answers once the network is back stays`() {
    startSession(First.PLAYER)
    seen.clear()

    networkRegained!!()
    answer(CommonStatusCodes.SUCCESS)

    assertTrue(remote.isSessionAvailable)
    assertEquals(emptyList<Boolean>(), seen)
    verify(cast, never()).endCurrentSession(anyBoolean())
  }

  // A request fails at once while suspended, and is replaced or cancelled by others: only a
  // receiver's silence tells of the quiet reconnect.
  @Test
  fun `a request that fails without the receiver's silence leaves the session`() {
    startSession(First.PLAYER)

    for (statusCode in
      listOf(CommonStatusCodes.ERROR, CastStatusCodes.REPLACED, CastStatusCodes.CANCELED)) {
      networkRegained!!()
      answer(statusCode)
    }

    assertTrue(remote.isSessionAvailable)
    verify(cast, never()).endCurrentSession(anyBoolean())
  }

  @Test
  fun `the network's return asks nothing of a suspended session, which the SDK resumes or ends`() {
    startSession(First.PLAYER)
    suspendSession(First.PLAYER)

    networkRegained!!()

    verify(client, never()).requestStatus()
  }

  @Test
  fun `no answer from a session that suspended meanwhile leaves it to the SDK`() {
    startSession(First.PLAYER)
    networkRegained!!()

    suspendSession(First.PLAYER)
    answer(CommonStatusCodes.TIMEOUT)

    assertTrue(remote.isSessionAvailable)
    verify(cast, never()).endCurrentSession(anyBoolean())
  }

  @Test
  fun `no answer from a session that suspended and resumed meanwhile leaves it`() {
    startSession(First.PLAYER)
    networkRegained!!()

    suspendSession(First.PLAYER)
    resumeSession(First.PLAYER)
    answer(CommonStatusCodes.TIMEOUT)

    assertTrue(remote.isSessionAvailable)
    verify(cast, never()).endCurrentSession(anyBoolean())
  }

  @Test
  fun `the network's return asks nothing without a session`() {
    networkRegained!!()

    verify(client, never()).requestStatus()
  }

  @Test
  fun `the network returning again asks nothing more while the receiver has yet to answer`() {
    startSession(First.PLAYER)

    networkRegained!!()
    networkRegained!!()
    verify(client, times(1)).requestStatus()

    answer(CommonStatusCodes.SUCCESS)
    networkRegained!!()
    verify(client, times(2)).requestStatus()
  }

  /** The receiver's media status: [itemId] idle for [idleReason]. */
  private fun receiverReports(itemId: Int, idleReason: Int) {
    val status = mock(MediaStatus::class.java)
    `when`(status.playerState).thenReturn(MediaStatus.PLAYER_STATE_IDLE)
    `when`(status.idleReason).thenReturn(idleReason)
    `when`(status.currentItemId).thenReturn(itemId)
    `when`(client.mediaStatus).thenReturn(status)
    val captor = ArgumentCaptor.forClass(RemoteMediaClient.Callback::class.java)
    verify(client, atLeastOnce()).registerCallback(captor.capture())
    captor.value.onStatusUpdated()
  }

  @Test
  fun `a failure reported after the next load went is the held load's, not the next one's`() {
    startSession(First.PLAYER)
    sends = 1
    receiverReports(1, MediaStatus.IDLE_REASON_NONE)
    sends = 2

    receiverReports(1, MediaStatus.IDLE_REASON_ERROR)
    assertEquals(0, failures.size)

    receiverReports(2, MediaStatus.IDLE_REASON_ERROR)
    assertEquals(1, failures.size)
  }

  // The receiver failed the load while the session was suspended.
  @Test
  fun `a failure a resumed session reports first is the load's`() {
    startSession(First.PLAYER)
    sends = 1
    receiverReports(1, MediaStatus.IDLE_REASON_NONE)
    suspendSession(First.PLAYER)
    resumeSession(First.PLAYER)

    receiverReports(1, MediaStatus.IDLE_REASON_ERROR)
    assertEquals(1, failures.size)
  }

  @Test
  fun `a failure a resumed session reports first, after a load held through the suspension, is the earlier load's`() {
    startSession(First.PLAYER)
    sends = 1
    receiverReports(1, MediaStatus.IDLE_REASON_NONE)
    suspendSession(First.PLAYER)
    sends = 2
    resumeSession(First.PLAYER)

    receiverReports(1, MediaStatus.IDLE_REASON_ERROR)
    assertEquals(0, failures.size)

    receiverReports(2, MediaStatus.IDLE_REASON_ERROR)
    assertEquals(1, failures.size)
  }

  @Test
  fun `a failure the receiver repeats after a resume is reported once`() {
    startSession(First.PLAYER)
    sends = 1
    receiverReports(1, MediaStatus.IDLE_REASON_ERROR)
    suspendSession(First.PLAYER)
    resumeSession(First.PLAYER)

    receiverReports(1, MediaStatus.IDLE_REASON_ERROR)
    assertEquals(1, failures.size)
  }

  @Test
  fun `a new session reports a failure of an item id the last one reported`() {
    startSession(First.PLAYER)
    sends = 1
    receiverReports(1, MediaStatus.IDLE_REASON_ERROR)
    toRemote.onSessionEnded(session, CastStatusCodes.SUCCESS)
    startSession(First.PLAYER)
    sends = 2

    receiverReports(1, MediaStatus.IDLE_REASON_ERROR)
    assertEquals(2, failures.size)
  }

  @Test
  fun `release stops watching the network`() {
    remote.release()

    assertFalse(networkWatched)
  }
}
