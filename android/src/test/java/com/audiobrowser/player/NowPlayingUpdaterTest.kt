package com.audiobrowser.player

import com.audiobrowser.TestFixtures.track
import com.margelo.nitro.audiobrowser.FormatNowPlayingParams
import com.margelo.nitro.audiobrowser.NowPlayingMetadata
import com.margelo.nitro.audiobrowser.NowPlayingUpdate
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the Now Playing rendering pipeline through a fake [NowPlayingSurface]: the
 * flash > override > formatter > track precedence, the publish dedupe, the stale-result guards
 * (render generation + track-id keying), timed-metadata reruns, the once-per-track artwork keying,
 * and the flash revert timing — all previously untestable inside Player.kt.
 */
class NowPlayingUpdaterTest {

  private class FakeSurface : NowPlayingSurface {
    override var currentIndex: Int? = 0
    override var currentTrack: Track? = null
    override var playbackState: PlaybackState = PlaybackState.PLAYING
    override var playbackError: PlaybackError? = null
    override var playWhenReady: Boolean = true
    override var isRebuffering: Boolean = false
    override var hasNowPlayingArtworkConfig: Boolean = false

    data class Stamp(
      val index: Int,
      val trackTitle: String,
      val title: String?,
      val secondary: String?,
      val album: String?,
    )

    val stamps = mutableListOf<Stamp>()
    val artworkStamps = mutableListOf<Pair<Int, String>>()
    val emitted = mutableListOf<NowPlayingMetadata>()
    var artworkResolveCount = 0
    var artworkResult: String? = null

    override fun stampFields(
      index: Int,
      track: Track,
      title: String?,
      secondaryLine: String?,
      album: String?,
    ) {
      stamps.add(Stamp(index, track.title, title, secondaryLine, album))
    }

    override fun stampArtwork(index: Int, track: Track, uri: String) {
      artworkStamps.add(index to uri)
    }

    override suspend fun resolveNowPlayingArtwork(track: Track, sizePx: Double): String? {
      artworkResolveCount += 1
      return artworkResult
    }

    override fun emitNowPlayingChanged(metadata: NowPlayingMetadata) {
      emitted.add(metadata)
    }
  }

  private fun update(title: String? = null, artist: String? = null, album: String? = null) =
    NowPlayingUpdate(title = title, artist = artist, album = album)

  // MARK: precedence

  @Test
  fun `renders track fields by default`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station", artist = "Place") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }

    updater.render()

    assertEquals(listOf(FakeSurface.Stamp(0, "Station", "Station", "Place", null)), surface.stamps)
    assertEquals(1, surface.emitted.size)
  }

  @Test
  fun `override wins per-field over the track`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station", artist = "Place") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }

    updater.updateNowPlaying(update(title = "Song"))

    assertEquals("Song", surface.stamps.last().title)
    assertEquals("Place", surface.stamps.last().secondary) // falls back per-field
    assertEquals("Song", updater.getNowPlaying()?.title)
  }

  @Test
  fun `flash outranks the override and getNowPlaying reflects it`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }
    updater.updateNowPlaying(update(title = "Song"))

    updater.flashNowPlaying(update(title = "Premium required"), durationMs = 3000.0)

    assertEquals("Premium required", surface.stamps.last().title)
    assertEquals("Premium required", updater.getNowPlaying()?.title)
  }

  @Test
  fun `disabled updater stamps raw track fields ignoring overrides`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope) // enabled = false
    updater.updateNowPlaying(update(title = "Song"))

    assertEquals("Station", surface.stamps.last().title)
  }

  // MARK: dedupe

  @Test
  fun `identical fields are not re-stamped`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }

    updater.render()
    updater.render()
    updater.render()

    assertEquals(1, surface.stamps.size)
  }

  @Test
  fun `a new track with identical text still publishes`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Same", src = "https://s/1.mp3") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }
    updater.render()

    surface.currentTrack = track("Same", src = "https://s/2.mp3")
    updater.render()

    assertEquals(2, surface.stamps.size)
  }

  // MARK: flash revert timing

  @Test
  fun `flash reverts after its duration and re-renders the live fields`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }

    updater.flashNowPlaying(update(title = "Flash!"), durationMs = 300.0)
    runCurrent()
    assertEquals("Flash!", surface.stamps.last().title)

    advanceTimeBy(301)
    runCurrent()
    assertEquals("Station", surface.stamps.last().title)
    assertNull(updater.getNowPlaying()?.title?.takeIf { it == "Flash!" })
  }

  @Test
  fun `clearNowPlayingFlash reverts immediately`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }
    updater.flashNowPlaying(update(title = "Flash!"), durationMs = 60_000.0)
    runCurrent()

    updater.clearNowPlayingFlash()

    assertEquals("Station", surface.stamps.last().title)
  }

  // MARK: formatter

  @Test
  fun `formatter result customizes the fields after the default stamp`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station", artist = "Place") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }
    updater.formatter = { update(title = "Live Song") }

    updater.render()
    runCurrent()

    assertEquals(listOf("Station", "Live Song"), surface.stamps.map { it.title })
    assertEquals("Place", surface.stamps.last().secondary) // per-field fallback
  }

  @Test
  fun `formatter params carry surface state`() = runTest {
    var seen: FormatNowPlayingParams? = null
    val surface =
      FakeSurface().apply {
        currentTrack = track("Station")
        playbackState = PlaybackState.BUFFERING
        isRebuffering = true
        playWhenReady = false
      }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }
    updater.formatter = { params ->
      seen = params
      null
    }

    updater.render()
    runCurrent()

    assertEquals(false, seen?.playWhenReady)
    assertEquals(true, seen?.stalled)
  }

  @Test
  fun `a stale formatter result is dropped after a newer render`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }
    val gate = CompletableDeferred<Unit>()
    updater.formatter = { gate.await(); update(title = "Stale") }

    updater.render() // launches the gated formatter
    updater.flashNowPlaying(update(title = "Flash!"), durationMs = 60_000.0) // newer generation
    runCurrent()
    gate.complete(Unit) // stale formatter completes after the flash
    runCurrent()

    assertEquals("Flash!", surface.stamps.last().title)
    assertTrue(surface.stamps.none { it.title == "Stale" })
  }

  @Test
  fun `a formatter result for a previous track is dropped`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("A", src = "https://s/a.mp3") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }
    val gate = CompletableDeferred<Unit>()
    updater.formatter = { gate.await(); update(title = "For A") }

    updater.render()
    surface.currentTrack = track("B", src = "https://s/b.mp3") // fast skip
    gate.complete(Unit)
    runCurrent()

    assertTrue(surface.stamps.none { it.title == "For A" })
  }

  @Test
  fun `a throwing formatter leaves the default fields`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }
    updater.formatter = { error("boom") }

    updater.render()
    runCurrent()

    assertEquals("Station", surface.stamps.last().title)
  }

  // MARK: timed metadata

  @Test
  fun `timed metadata re-runs the formatter and clears on track change`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }
    val seenTimed = mutableListOf<String?>()
    updater.formatter = { params ->
      seenTimed.add(params.timedMetadata?.title)
      null
    }
    val timed =
      com.margelo.nitro.audiobrowser.TimedMetadata(
        title = "Song X",
        artist = null,
        album = null,
        date = null,
        genre = null,
      )

    updater.onTimedMetadataReceived(timed)
    runCurrent()
    assertEquals("Song X", seenTimed.last())

    updater.clearOverride() // track change clears timed metadata
    updater.render()
    runCurrent()
    assertNull(seenTimed.last())
  }

  // MARK: artwork keying

  @Test
  fun `artwork resolves once per track id and stamps the playing item`() = runTest {
    val surface =
      FakeSurface().apply {
        currentTrack = track("Station", id = "abc")
        hasNowPlayingArtworkConfig = true
        artworkResult = "https://img/abc.png"
      }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }

    updater.render()
    runCurrent()
    updater.updateNowPlaying(update(title = "Song")) // re-render, same track
    runCurrent()

    assertEquals(1, surface.artworkResolveCount)
    assertEquals(listOf(0 to "https://img/abc.png"), surface.artworkStamps)
  }

  @Test
  fun `artwork re-resolves for a new track id and skips without an id`() = runTest {
    val surface =
      FakeSurface().apply {
        currentTrack = track("A", id = "a")
        hasNowPlayingArtworkConfig = true
        artworkResult = "https://img/a.png"
      }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }
    updater.render()
    runCurrent()

    surface.currentTrack = track("B", id = "b", src = "https://s/b.mp3")
    updater.render()
    runCurrent()
    assertEquals(2, surface.artworkResolveCount)

    surface.currentTrack = track("C", id = null, src = "https://s/c.mp3")
    updater.render()
    runCurrent()
    assertEquals(2, surface.artworkResolveCount) // no id → no resolve
  }

  @Test
  fun `a stale artwork result is not stamped after a track change`() = runTest {
    val surface =
      FakeSurface().apply {
        currentTrack = track("A", id = "a")
        hasNowPlayingArtworkConfig = true
        artworkResult = "https://img/a.png"
      }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = true }

    updater.render() // schedules the artwork resolve
    surface.currentTrack = track("B", id = "b", src = "https://s/b.mp3") // skip before it lands
    runCurrent()

    assertTrue(surface.artworkStamps.isEmpty())
  }
}
