package com.audiobrowser.player

import com.audiobrowser.extension.identity
import com.audiobrowser.util.url
import com.margelo.nitro.audiobrowser.FormatNowPlayingParams
import com.margelo.nitro.audiobrowser.NowPlayingMetadata
import com.margelo.nitro.audiobrowser.NowPlayingUpdate
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.StallReason
import com.margelo.nitro.audiobrowser.TimedMetadata
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * What [NowPlayingUpdater] needs from the platform; [Player] implements it, tests fake it. Mirrors
 * iOS's NowPlayingUpdater / NowPlayingInfoController split.
 */
interface NowPlayingSurface {
  val currentIndex: Int?
  val currentTrack: Track?
  val playbackState: PlaybackState
  val playbackError: PlaybackError?

  /**
   * The underlying ExoPlayer's real play intent, false during a transient audio focus loss — not
   * the InterceptingPlayer-masked value, which reads false through a masked terminal error. The
   * formatter gets this, so the masked value breaks its output in exactly that error case.
   */
  val playWhenReady: Boolean
  val isRebuffering: Boolean

  val isOnline: Boolean

  val hasNowPlayingArtworkConfig: Boolean

  fun stampFields(index: Int, track: Track, title: String?, secondaryLine: String?, album: String?)

  fun stampArtwork(index: Int, track: Track, uri: String)

  /**
   * Resolves [track]'s artwork through the `nowPlayingArtwork` config. Returns null when it
   * produced nothing; logs its own errors rather than throwing.
   */
  suspend fun resolveNowPlayingArtwork(track: Track, sizePx: Double): String?

  fun emitNowPlayingChanged(metadata: NowPlayingMetadata)
}

/**
 * Renders the now-playing lines with the precedence flash > override > formatter > track, reaching
 * the platform only through [NowPlayingSurface]. Mirrors iOS's NowPlayingUpdater.
 */
class NowPlayingUpdater(private val surface: NowPlayingSurface, private val scope: CoroutineScope) {

  /**
   * When false, the surface gets the raw track fields, while the event and [getNowPlaying] still
   * report the flash and override.
   */
  var enabled = true

  /** Customizes the lines asynchronously; a null result or a throw leaves the default lines. */
  var formatter: (suspend (FormatNowPlayingParams) -> NowPlayingUpdate?)? = null

  @Volatile
  var timedMetadata: TimedMetadata? = null
    private set

  private var override: NowPlayingUpdate? = null

  // Reverted by a coroutine on [scope], not a JS timer: a JS timer pauses with a backgrounded host,
  // and the lock screen is the backgrounded case.
  private var flash: NowPlayingUpdate? = null
  private var flashRevert: Job? = null

  // An async formatter result applies only if no newer render (a flash, notably) came after the one
  // that launched it.
  private var renderGeneration = 0L

  private data class Published(
    val index: Int,
    val trackId: String?,
    val title: String?,
    val secondaryLine: String?,
    val album: String?,
  )

  private var lastPublished: Published? = null

  // While this is the rendering track's identity, [render] skips its interim default stamp, which
  // would flash the track's own line over the formatter's until the async result lands.
  private var formattedTrackId: String? = null

  private var artworkResolvedForTrack: String? = null

  // Square. Matches iOS, which caps the screen width at 1200.
  private val artworkSizePx = 1200.0

  fun destroy() {
    scope.cancel()
  }

  fun updateNowPlaying(update: NowPlayingUpdate?) {
    override = update
    render()
  }

  fun flashNowPlaying(update: NowPlayingUpdate, durationMs: Double) {
    flashRevert?.cancel()
    flash = update
    flashRevert =
      scope.launch {
        delay(durationMs.toLong())
        flash = null
        flashRevert = null
        render()
      }
    render()
  }

  fun clearNowPlayingFlash() {
    if (flash == null) return
    cancelFlash()
    render()
  }

  private fun cancelFlash() {
    flashRevert?.cancel()
    flashRevert = null
    flash = null
  }

  /**
   * The lines on the surface for the current track, formatter output included. Before the track's
   * first render, and while [enabled] is false, the flash, override or track fields instead.
   */
  fun getNowPlaying(): NowPlayingMetadata? {
    val track = surface.currentTrack ?: return null
    val published =
      lastPublished?.takeIf { enabled && it.trackId == (track.identity ?: track.path) }
        ?: return layered(track)
    return metadata(track, published.title, published.secondaryLine, published.album)
  }

  private fun layered(track: Track): NowPlayingMetadata {
    val flash = flash
    val override = override
    return metadata(
      track,
      flash?.title ?: override?.title ?: track.title,
      flash?.artist ?: override?.artist ?: track.artist,
      flash?.album ?: override?.album ?: track.album,
    )
  }

  private fun metadata(track: Track, title: String?, artist: String?, album: String?) =
    NowPlayingMetadata(
      elapsedTime = null,
      title = title,
      album = album,
      artist = artist,
      duration = track.duration,
      artwork = track.artwork?.url,
      description = track.description,
      mediaId = track.src ?: track.path,
      genre = track.genre,
    )

  /**
   * Makes the next [render] stamp lines and artwork in full. Call when a media item becomes current
   * or is replaced in place: it carries its own default lines and artwork.
   */
  fun forgetPublished() {
    lastPublished = null
    formattedTrackId = null
    artworkResolvedForTrack = null
  }

  /** Call on track change: clears the override, flash, timed metadata and published lines. */
  fun clearOverride() {
    override = null
    cancelFlash()
    timedMetadata = null
    forgetPublished()
  }

  /** Call when the stream stops: its title no longer applies. The caller renders. */
  fun clearTimedMetadata() {
    timedMetadata = null
  }

  /** Records live (ICY/ID3) metadata; re-renders only when a formatter would use it. */
  fun onTimedMetadataReceived(timed: TimedMetadata) {
    timedMetadata = timed
    if (enabled && formatter != null) {
      render()
    }
  }

  /**
   * Stamps the default lines at once, then the formatter's when its result lands, unless a newer
   * render or a track change came first.
   */
  fun render() {
    val index = surface.currentIndex ?: return
    val track = surface.currentTrack ?: return
    renderGeneration += 1
    val generation = renderGeneration

    val activeOverride = if (enabled) override else null
    val defaultTitle = activeOverride?.title ?: track.title
    val defaultSecondary = activeOverride?.artist ?: track.artist
    val defaultAlbum = activeOverride?.album ?: track.album

    // The formatter is skipped while a flash is up, so its async result can't land on top.
    val activeFlash = if (enabled) flash else null
    if (activeFlash != null) {
      applyFields(
        index,
        track,
        activeFlash.title ?: defaultTitle,
        activeFlash.artist ?: defaultSecondary,
        activeFlash.album ?: defaultAlbum,
      )
      formattedTrackId = null
      return
    }

    // The default goes out at once so the surface never lags a track or state change.
    val activeFormatter = if (enabled) formatter else null
    if (activeFormatter == null || formattedTrackId != (track.identity ?: track.path)) {
      applyFields(index, track, defaultTitle, defaultSecondary, defaultAlbum)
    }

    if (activeFormatter != null) {
      val capturedId = track.src ?: track.path
      // ExoPlayer's rebuffering flag is polled on its own cadence and can linger true into PLAYING
      // as a rebuffer recovers, so only BUFFERING counts as a stall.
      val isStalled = surface.playbackState == PlaybackState.BUFFERING && surface.isRebuffering
      val stalled =
        if (isStalled) {
          if (surface.isOnline) StallReason.BUFFERING else StallReason.OFFLINE
        } else {
          null
        }
      val params =
        FormatNowPlayingParams(
          track,
          timedMetadata,
          surface.playWhenReady,
          stalled,
          surface.playbackError,
        )
      scope.launch {
        val formatted =
          try {
            activeFormatter(params)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Timber.e(e, "NowPlaying formatter threw; using default")
            null
          }
        val current = surface.currentTrack
        val currentIdx = surface.currentIndex
        if (
          current != null &&
            currentIdx != null &&
            (current.src ?: current.path) == capturedId &&
            renderGeneration == generation
        ) {
          applyFields(
            currentIdx,
            current,
            formatted?.title ?: defaultTitle,
            formatted?.artist ?: defaultSecondary,
            formatted?.album ?: defaultAlbum,
          )
          formattedTrackId = if (formatted != null) current.identity ?: current.path else null
        }
      }
    }
  }

  private fun applyFields(
    index: Int,
    track: Track,
    title: String?,
    secondaryLine: String?,
    album: String?,
  ) {
    // The formatter reruns on every state change; restamping identical lines churns the
    // MediaSession and flickers Android Auto. Keyed on track identity so a new track with the same
    // lines still publishes.
    val published = Published(index, track.identity ?: track.path, title, secondaryLine, album)
    val unchanged = published == lastPublished
    lastPublished = published
    if (unchanged) return

    surface.stampFields(index, track, title, secondaryLine, album)
    surface.emitNowPlayingChanged(
      if (enabled) metadata(track, title, secondaryLine, album) else layered(track)
    )

    maybeResolveArtwork(track)
  }

  /**
   * Resolves the now-playing artwork once per track identity, id-less tracks included as on iOS (an
   * unfilled `{id}` token makes the request 404 visibly). Without the config, the media item keeps
   * the artwork from `artwork`.
   */
  private fun maybeResolveArtwork(track: Track) {
    if (!surface.hasNowPlayingArtworkConfig) {
      artworkResolvedForTrack = null
      return
    }

    val trackKey = track.identity ?: track.path
    if (trackKey != null && artworkResolvedForTrack == trackKey) return
    artworkResolvedForTrack = trackKey

    scope.launch {
      val uri =
        surface.resolveNowPlayingArtwork(track, artworkSizePx)?.takeIf { it.isNotEmpty() }
          ?: return@launch

      val currentIdx = surface.currentIndex ?: return@launch
      val current = surface.currentTrack ?: return@launch
      if ((current.identity ?: current.path) != trackKey) return@launch

      surface.stampArtwork(currentIdx, current, uri)
    }
  }
}
