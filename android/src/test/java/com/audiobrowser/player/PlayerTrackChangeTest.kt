package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer
import com.audiobrowser.Callbacks
import com.audiobrowser.TestFixtures
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.util.TrackFactory
import com.margelo.nitro.audiobrowser.TimedMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerTrackChangeTest {

  private val player = Player(RuntimeEnvironment.getApplication())

  @After fun release() = player.exoPlayer.release()

  @Test
  fun `the timed metadata is cleared before the track change is announced`() {
    player.setup(PlayerSetupOptions())
    val item = TrackFactory.toMedia3(TestFixtures.track())
    player.sessionPlayer.setMediaItems(listOf(item))
    player.nowPlaying.onTimedMetadataReceived(
      TimedMetadata(title = "Song", artist = null, album = null, date = null, genre = null)
    )
    val seenAtTrackChange = mutableListOf<TimedMetadata?>()
    player.callbacks =
      mock(Callbacks::class.java) { invocation ->
        if (invocation.method.name == "onPlaybackActiveTrackChanged") {
          seenAtTrackChange.add(player.nowPlaying.timedMetadata)
        }
        Answers.RETURNS_DEFAULTS.answer(invocation)
      }

    player.playerListener.onMediaItemTransition(item, MediaPlayer.MEDIA_ITEM_TRANSITION_REASON_SEEK)

    assertEquals(listOf<TimedMetadata?>(null), seenAtTrackChange)
  }
}
