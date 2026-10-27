package com.audiobrowser.player

import com.audiobrowser.TestFixtures.track
import com.margelo.nitro.audiobrowser.FormatNowPlayingParams
import com.margelo.nitro.audiobrowser.NowPlayingMetadata
import com.margelo.nitro.audiobrowser.NowPlayingUpdate
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.StallReason
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
 * Tests the Now Playing rendering pipeline through a fake [NowPlayingSurface]: the flash >
 * override > formatter > track precedence, the publish dedupe, the stale-result guards (render
 * generation + track-id keying), timed-metadata reruns, the once-per-track artwork keying, and the
 * flash revert timing — all previously untestable inside Player.kt.
 */
class NowPlayingUpdaterTest {

  private class FakeSurface : NowPlayingSurface {
    override var currentIndex: Int? = 0
    override var currentTrack: Track? = null
    override var playbackState: PlaybackState = PlaybackState.PLAYING
    override var playbackError: PlaybackError? = null
    override var playWhenReady: Boolean = true
    override var isRebuffering: Boolean = false
    override var isOnline: Boolean = true
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
    val updater = NowPlayingUpdater(surface, backgroundScope)

    updater.render()

    assertEquals(listOf(FakeSurface.Stamp(0, "Station", "Station", "Place", null)), surface.stamps)
    assertEquals(1, surface.emitted.size)
    assertEquals("Place", surface.emitted.last().artist)
    assertEquals(surface.emitted.last(), updater.getNowPlaying())
  }

  @Test
  fun `override wins per-field over the track`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station", artist = "Place") }
    val updater = NowPlayingUpdater(surface, backgroundScope)

    updater.updateNowPlaying(update(title = "Song"))

    assertEquals("Song", surface.stamps.last().title)
    assertEquals("Place", surface.stamps.last().secondary) // falls back per-field
    assertEquals("Song", updater.getNowPlaying()?.title)
  }

  @Test
  fun `flash outranks the override and getNowPlaying reflects it`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.updateNowPlaying(update(title = "Song"))

    updater.flashNowPlaying(update(title = "Premium required"), durationMs = 3000.0)

    assertEquals("Premium required", surface.stamps.last().title)
    assertEquals("Premium required", updater.getNowPlaying()?.title)
  }

  @Test
  fun `disabled updater stamps raw track fields ignoring overrides`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = false }
    updater.updateNowPlaying(update(title = "Song"))

    assertEquals("Station", surface.stamps.last().title)
  }

  @Test
  fun `disabled updater still reports the override to JS`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope).apply { enabled = false }
    updater.updateNowPlaying(update(title = "Song"))

    assertEquals("Song", surface.emitted.last().title)
    assertEquals("Song", updater.getNowPlaying()?.title)
  }

  // MARK: dedupe

  @Test
  fun `identical fields are not re-stamped`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope)

    updater.render()
    updater.render()
    updater.render()

    assertEquals(1, surface.stamps.size)
  }

  @Test
  fun `a new track with identical text still publishes`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Same", src = "https://s/1.mp3") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.render()

    surface.currentTrack = track("Same", src = "https://s/2.mp3")
    updater.render()

    assertEquals(2, surface.stamps.size)
  }

  // MARK: flash revert timing

  @Test
  fun `flash reverts after its duration and re-renders the live fields`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope)

    updater.flashNowPlaying(update(title = "Flash!"), durationMs = 300.0)
    runCurrent()
    assertEquals("Flash!", surface.stamps.last().title)

    advanceTimeBy(301)
    runCurrent()
    assertEquals("Station", surface.stamps.last().title)
    assertEquals("Station", updater.getNowPlaying()?.title)
  }

  @Test
  fun `clearNowPlayingFlash reverts immediately`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.flashNowPlaying(update(title = "Flash!"), durationMs = 60_000.0)
    runCurrent()

    updater.clearNowPlayingFlash()

    assertEquals("Station", surface.stamps.last().title)
  }

  // MARK: formatter

  @Test
  fun `formatter result customizes the fields after the default stamp`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station", artist = "Place") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.formatter = { update(title = "Live Song") }

    updater.render()
    runCurrent()

    assertEquals(listOf("Station", "Live Song"), surface.stamps.map { it.title })
    assertEquals("Place", surface.stamps.last().secondary) // per-field fallback
  }

  @Test
  fun `the formatter's lines reach the event and getNowPlaying`() = runTest {
    val surface =
      FakeSurface().apply {
        currentTrack =
          track("Station", artist = "Place", album = "Country", artwork = "https://s/a.png")
      }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.formatter = { update(artist = "Song - Artist") }

    updater.render()
    runCurrent()

    val emitted = surface.emitted.last()
    assertEquals("Station", emitted.title)
    assertEquals("Song - Artist", emitted.artist)
    assertEquals("Country", emitted.album)
    assertEquals(emitted, updater.getNowPlaying())
    assertNull(emitted.elapsedTime)
    assertEquals("https://s/a.png", emitted.artwork)
    assertEquals("https://s/a.mp3", emitted.mediaId)
  }

  @Test
  fun `a re-render keeps the formatter's line up while the formatter runs`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station", artist = "Place") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    val gate = CompletableDeferred<Unit>()
    var calls = 0
    updater.formatter = {
      if (++calls > 1) gate.await()
      update(artist = "Song - Artist")
    }
    updater.render()
    runCurrent()

    updater.render()
    runCurrent()
    assertEquals("Song - Artist", updater.getNowPlaying()?.artist)
    gate.complete(Unit)
    runCurrent()

    assertEquals(listOf("Place", "Song - Artist"), surface.emitted.map { it.artist })
    assertEquals(listOf("Place", "Song - Artist"), surface.stamps.map { it.secondary })
  }

  @Test
  fun `a formatter that stops customizing restores the default`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station", artist = "Place") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    var line: String? = "Song - Artist"
    updater.formatter = { update(artist = line) }
    updater.render()
    runCurrent()

    line = null
    updater.render()
    runCurrent()

    assertEquals("Place", surface.stamps.last().secondary)
    assertEquals("Place", surface.emitted.last().artist)
    assertEquals("Place", updater.getNowPlaying()?.artist)
  }

  @Test
  fun `a throwing formatter after a result restores the default`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station", artist = "Place") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    var fail = false
    updater.formatter = { if (fail) error("boom") else update(artist = "Song - Artist") }
    updater.render()
    runCurrent()

    fail = true
    updater.render()
    runCurrent()

    assertEquals("Place", surface.stamps.last().secondary)
    assertEquals("Place", updater.getNowPlaying()?.artist)
  }

  @Test
  fun `getNowPlaying keeps the formatter's line when the playing item moves in the queue`() =
    runTest {
      val surface = FakeSurface().apply { currentTrack = track("Station", artist = "Place") }
      val updater = NowPlayingUpdater(surface, backgroundScope)
      updater.formatter = { update(artist = "Song - Artist") }
      updater.render()
      runCurrent()

      surface.currentIndex = 1

      assertEquals("Song - Artist", updater.getNowPlaying()?.artist)
    }

  @Test
  fun `a track replaced in place is stamped even with unchanged lines`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.render()

    updater.forgetPublished()
    surface.currentTrack = track("Station")
    updater.render()

    assertEquals(listOf("Station", "Station"), surface.stamps.map { it.title })
  }

  @Test
  fun `a new item of the same track gets the formatter's line stamped again`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station", artist = "Place") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.formatter = { update(artist = "Song - Artist") }
    updater.render()
    runCurrent()

    surface.currentTrack = track("Station", artist = "Place")
    updater.clearOverride()
    updater.render()
    runCurrent()

    assertEquals(
      listOf("Place", "Song - Artist", "Place", "Song - Artist"),
      surface.stamps.map { it.secondary },
    )
    assertEquals("Song - Artist", updater.getNowPlaying()?.artist)
  }

  @Test
  fun `a copy of the playing track keeps the formatter's line`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station", artist = "Place") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.formatter = { update(artist = "Song - Artist") }
    updater.render()
    runCurrent()

    surface.currentTrack = surface.currentTrack?.copy(favorited = true)
    assertEquals("Song - Artist", updater.getNowPlaying()?.artist)
    updater.render()
    runCurrent()

    assertEquals(listOf("Place", "Song - Artist"), surface.emitted.map { it.artist })
  }

  @Test
  fun `getNowPlaying shows a new track before it renders`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("A", src = "https://s/a.mp3") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.formatter = { update(artist = "Song on A") }
    updater.render()
    runCurrent()

    surface.currentTrack = track("B", src = "https://s/b.mp3", artist = "Place B")

    assertEquals("B", updater.getNowPlaying()?.title)
    assertEquals("Place B", updater.getNowPlaying()?.artist)
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
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.formatter = { params ->
      seen = params
      null
    }

    updater.render()
    runCurrent()

    assertEquals(false, seen?.playWhenReady)
    assertEquals(StallReason.BUFFERING, seen?.stalled)
  }

  @Test
  fun `a stall while offline is classified as offline`() = runTest {
    var seen: FormatNowPlayingParams? = null
    val surface =
      FakeSurface().apply {
        currentTrack = track("Station")
        playbackState = PlaybackState.BUFFERING
        isRebuffering = true
        isOnline = false
      }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.formatter = { params ->
      seen = params
      null
    }

    updater.render()
    runCurrent()

    assertEquals(StallReason.OFFLINE, seen?.stalled)
  }

  @Test
  fun `no stall when not rebuffering`() = runTest {
    var seen: FormatNowPlayingParams? = null
    val surface =
      FakeSurface().apply {
        currentTrack = track("Station")
        playbackState = PlaybackState.PLAYING
      }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.formatter = { params ->
      seen = params
      null
    }

    updater.render()
    runCurrent()

    assertEquals(null, seen?.stalled)
  }

  @Test
  fun `a stale formatter result is dropped after a newer render`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    val gate = CompletableDeferred<Unit>()
    updater.formatter = {
      gate.await()
      update(title = "Stale")
    }

    updater.render() // launches the gated formatter
    updater.flashNowPlaying(update(title = "Flash!"), durationMs = 60_000.0) // newer generation
    runCurrent()
    gate.complete(Unit) // stale formatter completes after the flash
    runCurrent()

    assertEquals("Flash!", surface.stamps.last().title)
    assertTrue(surface.stamps.none { it.title == "Stale" })
  }

  @Test
  fun `the formatter is not invoked while a flash is active`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    var invocations = 0
    updater.formatter = {
      invocations += 1
      null
    }
    updater.flashNowPlaying(update(title = "Flash!"), durationMs = 60_000.0)
    runCurrent()

    updater.render() // e.g. a playback-state change mid-flash
    runCurrent()

    assertEquals(0, invocations)
  }

  @Test
  fun `a formatter result for a previous track is dropped`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("A", src = "https://s/a.mp3") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    val gate = CompletableDeferred<Unit>()
    updater.formatter = {
      gate.await()
      update(title = "For A")
    }

    updater.render()
    surface.currentTrack = track("B", src = "https://s/b.mp3") // fast skip
    gate.complete(Unit)
    runCurrent()

    assertTrue(surface.stamps.none { it.title == "For A" })
  }

  @Test
  fun `a throwing formatter leaves the default fields`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.formatter = { error("boom") }

    updater.render()
    runCurrent()

    assertEquals("Station", surface.stamps.last().title)
  }

  // MARK: timed metadata

  @Test
  fun `timed metadata re-runs the formatter and clears on track change`() = runTest {
    val surface = FakeSurface().apply { currentTrack = track("Station") }
    val updater = NowPlayingUpdater(surface, backgroundScope)
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
    val updater = NowPlayingUpdater(surface, backgroundScope)

    updater.render()
    runCurrent()
    updater.updateNowPlaying(update(title = "Song")) // re-render, same track
    runCurrent()

    assertEquals(1, surface.artworkResolveCount)
    assertEquals(listOf(0 to "https://img/abc.png"), surface.artworkStamps)
  }

  @Test
  fun `artwork re-resolves for a new track identity including id-less tracks`() = runTest {
    val surface =
      FakeSurface().apply {
        currentTrack = track("A", id = "a")
        hasNowPlayingArtworkConfig = true
        artworkResult = "https://img/a.png"
      }
    val updater = NowPlayingUpdater(surface, backgroundScope)
    updater.render()
    runCurrent()

    surface.currentTrack = track("B", id = "b", src = "https://s/b.mp3")
    updater.render()
    runCurrent()
    assertEquals(2, surface.artworkResolveCount)

    // The config runs for id-less tracks too — no blanket skip.
    surface.currentTrack = track("C", id = null, src = "https://s/c.mp3")
    updater.render()
    runCurrent()
    assertEquals(3, surface.artworkResolveCount)
    assertEquals(0 to "https://img/a.png", surface.artworkStamps.last())
  }

  @Test
  fun `artwork for an id-less track resolves once keyed on src identity`() = runTest {
    val surface =
      FakeSurface().apply {
        currentTrack = track("C", id = null, src = "https://s/c.mp3")
        hasNowPlayingArtworkConfig = true
        artworkResult = "https://img/c.png"
      }
    val updater = NowPlayingUpdater(surface, backgroundScope)

    updater.render()
    runCurrent()
    updater.updateNowPlaying(update(title = "Song")) // re-render, same track
    runCurrent()

    assertEquals(1, surface.artworkResolveCount)
    assertEquals(listOf(0 to "https://img/c.png"), surface.artworkStamps)
  }

  @Test
  fun `artwork keying resets when the config disappears so the same track re-resolves later`() =
    runTest {
      val surface =
        FakeSurface().apply {
          currentTrack = track("A", id = "a")
          hasNowPlayingArtworkConfig = true
          artworkResult = "https://img/a.png"
        }
      val updater = NowPlayingUpdater(surface, backgroundScope)
      updater.render()
      runCurrent()
      assertEquals(1, surface.artworkResolveCount)

      surface.hasNowPlayingArtworkConfig = false
      updater.updateNowPlaying(update(title = "x")) // re-render without config resets the keying
      runCurrent()

      surface.hasNowPlayingArtworkConfig = true
      updater.updateNowPlaying(update(title = "y"))
      runCurrent()
      assertEquals(2, surface.artworkResolveCount)
    }

  @Test
  fun `a stale artwork result is not stamped after a track change`() = runTest {
    val surface =
      FakeSurface().apply {
        currentTrack = track("A", id = "a")
        hasNowPlayingArtworkConfig = true
        artworkResult = "https://img/a.png"
      }
    val updater = NowPlayingUpdater(surface, backgroundScope)

    updater.render() // schedules the artwork resolve
    surface.currentTrack = track("B", id = "b", src = "https://s/b.mp3") // skip before it lands
    runCurrent()

    assertTrue(surface.artworkStamps.isEmpty())
  }

  @Test
  fun `a stale artwork result for an id-less track is not stamped after a track change`() =
    runTest {
      val surface =
        FakeSurface().apply {
          currentTrack = track("A", id = null, src = "https://s/a.mp3")
          hasNowPlayingArtworkConfig = true
          artworkResult = "https://img/a.png"
        }
      val updater = NowPlayingUpdater(surface, backgroundScope)

      updater.render() // schedules the artwork resolve
      // Skip to another id-less track before it lands: identity (src) keying must catch it.
      surface.currentTrack = track("B", id = null, src = "https://s/b.mp3")
      runCurrent()

      assertTrue(surface.artworkStamps.isEmpty())
    }
}
