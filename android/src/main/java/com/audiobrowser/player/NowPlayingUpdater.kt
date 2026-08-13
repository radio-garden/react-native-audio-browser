package com.audiobrowser.player

import com.audiobrowser.util.url
import com.margelo.nitro.audiobrowser.FormatNowPlayingParams
import com.margelo.nitro.audiobrowser.NowPlayingMetadata
import com.margelo.nitro.audiobrowser.NowPlayingUpdate
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.StallReason
import com.margelo.nitro.audiobrowser.TimedMetadata
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * What the now-playing logic needs from the platform — implemented by [Player] (current playback
 * reads, MediaItem stamping via `replaceMediaItem`, artwork resolution through the browser).
 * Mirrors iOS's NowPlayingUpdater / NowPlayingInfoController split: this seam is what makes the
 * precedence/guard/dedupe logic testable with a fake.
 */
interface NowPlayingSurface {
  val currentIndex: Int?
  val currentTrack: Track?
  val playbackState: PlaybackState
  val playbackError: PlaybackError?

  /**
   * MUST be the real play/pause intent read from the underlying ExoPlayer — not the
   * InterceptingPlayer-masked value, which can report false through a masked terminal error to keep
   * the session paused-but-alive. The formatter receives this; feeding it the masked value breaks
   * its output exactly in the error case the mask exists for.
   */
  val playWhenReady: Boolean
  val isRebuffering: Boolean

  /** Device connectivity, used to classify a stall as offline vs a plain rebuffer. */
  val isOnline: Boolean

  val hasNowPlayingArtworkConfig: Boolean

  /** Stamp title/secondary/album onto the playing item. */
  fun stampFields(index: Int, track: Track, title: String?, secondaryLine: String?, album: String?)

  /** Stamp a resolved artwork uri onto the playing item. */
  fun stampArtwork(index: Int, track: Track, uri: String)

  /**
   * Resolve the now-playing artwork for [track] via the `nowPlayingArtwork` config kind. Returns
   * the final uri, or null when resolution produced nothing (errors are logged inside).
   */
  suspend fun resolveNowPlayingArtwork(track: Track, sizePx: Double): String?

  fun emitNowPlayingChanged(metadata: NowPlayingMetadata)
}

/**
 * Owns the Now Playing rendering pipeline: the flash > override > formatter > track precedence, the
 * publish dedupe, the stale-result guards (render generation + track-id keying), timed (ICY)
 * metadata, and the once-per-track artwork keying. Mirrors iOS's NowPlayingUpdater. The platform is
 * reached only through [NowPlayingSurface]; the JS formatter arrives as a plain suspend lambda
 * (wrapped at the Nitro boundary in Player.setup), so every guard here runs under JVM tests.
 */
class NowPlayingUpdater(private val surface: NowPlayingSurface, private val scope: CoroutineScope) {

  /** Whether now-playing rendering (override/flash/formatter) is enabled (PlayerSetupOptions). */
  var enabled = true

  /** The JS formatter, wrapped to a plain suspend call at the Nitro boundary (see Player.setup). */
  var formatter: (suspend (FormatNowPlayingParams) -> NowPlayingUpdate?)? = null

  /** Latest live timed (ICY/ID3) metadata; cleared on track change. */
  @Volatile private var latestTimedMetadata: TimedMetadata? = null

  /** Imperative override (from `updateNowPlaying()`); cleared on track change. */
  private var override: NowPlayingUpdate? = null

  /**
   * Transient now-playing fields (e.g. feedback for a refused remote command). Outranks the
   * formatter and the override while active, so live metadata can't stomp it mid-flash. Reverted by
   * a coroutine delay on [scope] — NOT a JS timer, which pauses with a backgrounded host (and the
   * lock screen is exactly the backgrounded case) — and cleared early on track change.
   */
  private var flash: NowPlayingUpdate? = null
  private var flashRevert: Job? = null

  /**
   * Monotonic stamp for renders. An async formatter result applies only when no newer render —
   * notably a flash — has superseded the one that launched it.
   */
  private var renderGeneration = 0L

  /** The last fields actually published, to dedupe redundant stamps. */
  private data class Published(
    val index: Int,
    val trackId: String?,
    val title: String?,
    val secondaryLine: String?,
    val album: String?,
  )

  private var lastPublished: Published? = null

  /**
   * The track id whose now-playing artwork has been resolved (or is being resolved), so a
   * `nowPlayingArtwork` resolve runs once per track instead of on every render.
   */
  private var artworkResolvedForTrackId: String? = null

  /** Size hint (px, square) for now-playing artwork. Mirrors iOS (screen-width-capped to 1200). */
  private val artworkSizePx = 1200.0

  /** Cancels in-flight work. Called from Player.destroy. */
  fun destroy() {
    scope.cancel()
  }

  /** Sets/clears the imperative override (null reverts to track metadata) and re-renders. */
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

  /** Clears an active flash immediately, reverting to the live metadata. No-op when none. */
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

  /** Gets the current now playing metadata (flash/override if set, else track metadata). */
  fun getNowPlaying(): NowPlayingMetadata? {
    val track = surface.currentTrack ?: return null
    val flash = flash
    val override = override

    return NowPlayingMetadata(
      elapsedTime = null,
      title = flash?.title ?: override?.title ?: track.title,
      album = flash?.album ?: override?.album ?: track.album,
      artist = flash?.artist ?: override?.artist ?: track.artist,
      duration = track.duration,
      artwork = track.artwork?.url,
      description = track.description,
      mediaId = track.src ?: track.path,
      genre = track.genre,
    )
  }

  /** Clears override, flash, and timed metadata on track change (PlayerListener transition). */
  fun clearOverride() {
    override = null
    cancelFlash()
    latestTimedMetadata = null
  }

  /**
   * Records the latest live timed (ICY/ID3) metadata and, when a formatter is configured, re-runs
   * it so the live song is reflected on the now-playing surface.
   */
  fun onTimedMetadataReceived(timed: TimedMetadata) {
    latestTimedMetadata = timed
    if (enabled && formatter != null) {
      render()
    }
  }

  /**
   * Renders the current now playing metadata to the surface. Flash wins while active (formatter
   * skipped entirely so its async result can't land on top); otherwise the override-or-track
   * default is stamped immediately and a configured formatter customizes it asynchronously, guarded
   * by track identity and [renderGeneration].
   */
  fun render() {
    val index = surface.currentIndex ?: return
    val track = surface.currentTrack ?: return
    renderGeneration += 1
    val generation = renderGeneration

    // Metadata source. When metadata is disabled, use the raw track (ignore override + formatter);
    // otherwise the imperative `updateNowPlaying` override wins over the track's own fields.
    val activeOverride = if (enabled) override else null
    val defaultTitle = activeOverride?.title ?: track.title
    val defaultSecondary = activeOverride?.artist ?: track.artist
    val defaultAlbum = activeOverride?.album ?: track.album

    // A flash outranks both the formatter and the override; while one is active the formatter pass
    // is skipped entirely so its async result can't land on top.
    val activeFlash = if (enabled) flash else null
    if (activeFlash != null) {
      applyFields(
        index,
        track,
        activeFlash.title ?: defaultTitle,
        activeFlash.artist ?: defaultSecondary,
        activeFlash.album ?: defaultAlbum,
      )
      return
    }

    // Apply the default immediately so the now-playing never lags a track/status change.
    applyFields(index, track, defaultTitle, defaultSecondary, defaultAlbum)

    // If a formatter is configured, let it customize the fields asynchronously. Falls back to the
    // default on null/throw.
    val activeFormatter = if (enabled) formatter else null
    if (activeFormatter != null) {
      val capturedId = track.src ?: track.path
      // Gate the raw load-control signal to the buffering state so `stalled` is correct on its
      // own: ExoPlayer's rebuffering flag is polled on a different cadence than state transitions
      // and can linger true into the PLAYING transition as a rebuffer recovers. Classify by
      // connectivity so the formatter can show "offline" vs a plain rebuffer; null when not
      // stalled.
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
          latestTimedMetadata,
          surface.playWhenReady,
          stalled,
          surface.playbackError,
        )
      scope.launch {
        val formatted =
          try {
            activeFormatter(params)
          } catch (e: Exception) {
            Timber.e(e, "NowPlaying formatter threw; using default")
            null
          }
        // Apply only if still the same track (a fast skip must not be overwritten by a stale
        // result) AND no newer render superseded this one — without the generation check, a
        // formatter round-trip in flight when a flash starts lands a beat later and overwrites
        // the flash (mirrors the iOS renderGeneration guard).
        val current = surface.currentTrack
        val currentIdx = surface.currentIndex
        if (
          formatted != null &&
            current != null &&
            currentIdx != null &&
            (current.src ?: current.path) == capturedId &&
            renderGeneration == generation
        ) {
          applyFields(
            currentIdx,
            current,
            formatted.title ?: defaultTitle,
            formatted.artist ?: defaultSecondary,
            formatted.album ?: defaultAlbum,
          )
        }
      }
    }
  }

  /** Stamps the fields via the surface, deduped on (index, track identity, fields). */
  private fun applyFields(
    index: Int,
    track: Track,
    title: String?,
    secondaryLine: String?,
    album: String?,
  ) {
    // Skip republishing identical fields. The formatter is re-invoked on every state change, so
    // the same fields are recomputed often; an unconditional stamp would churn the MediaSession
    // (and flicker Android Auto / now-playing) for no visible change. Keyed on the track identity
    // so a new track with a coincidentally identical line still publishes.
    val published = Published(index, track.src ?: track.path, title, secondaryLine, album)
    if (published == lastPublished) return
    lastPublished = published

    surface.stampFields(index, track, title, secondaryLine, album)
    getNowPlaying()?.let { surface.emitNowPlayingChanged(it) }

    // Resolve the now-playing-only artwork (lock screen / notification / Android Auto
    // now-playing), keyed on track id so it runs once per track and not on every render.
    maybeResolveArtwork(track)
  }

  /**
   * Resolves the playing track's now-playing artwork and stamps it onto the playing media item.
   * Guarded exactly like iOS: only when `nowPlayingArtwork` is configured AND the track has a
   * non-empty id (so the `{id}` token never resolves to an empty string). Otherwise the existing
   * artworkUri — which came from `artwork` (browse list path) — is left in place.
   */
  private fun maybeResolveArtwork(track: Track) {
    val trackId =
      track.id?.takeIf { it.isNotEmpty() }
        ?: run {
          // No id → skip nowPlayingArtwork entirely; keep the existing (browse) artworkUri.
          artworkResolvedForTrackId = null
          return
        }

    if (!surface.hasNowPlayingArtworkConfig) {
      // No now-playing artwork config (or no browser) → fall back to the existing artworkUri.
      artworkResolvedForTrackId = null
      return
    }

    // Already resolved (or resolving) for this track id — avoid a redundant resolve per render.
    if (artworkResolvedForTrackId == trackId) return
    artworkResolvedForTrackId = trackId

    scope.launch {
      val uri =
        surface.resolveNowPlayingArtwork(track, artworkSizePx)?.takeIf { it.isNotEmpty() }
          ?: return@launch

      // Apply only if still the same track (a fast skip must not be overwritten by a stale
      // result).
      val currentIdx = surface.currentIndex ?: return@launch
      val current = surface.currentTrack ?: return@launch
      if (current.id != trackId) return@launch

      surface.stampArtwork(currentIdx, current, uri)
    }
  }
}
