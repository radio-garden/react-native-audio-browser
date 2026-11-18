package com.audiobrowser.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.audiobrowser.AudioBrowser
import com.audiobrowser.Callbacks
import com.audiobrowser.browser.displayArtworkSource
import com.audiobrowser.browser.resolveArtworkUrl
import com.audiobrowser.browser.resolveMediaUrl
import com.audiobrowser.browser.unattributedArtworkSource
import com.audiobrowser.cast.createCastRemote
import com.audiobrowser.extension.NumberExt.Companion.toSeconds
import com.audiobrowser.extension.identity
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.model.RetryPolicy
import com.audiobrowser.remote.RemoteOutputsController
import com.audiobrowser.remote.RemotePlayback
import com.audiobrowser.remote.RemoteUrls
import com.audiobrowser.sonos.SonosRemote
import com.audiobrowser.util.ArtworkRoutes
import com.audiobrowser.util.ArtworkUris
import com.audiobrowser.util.Automotive
import com.audiobrowser.util.CoilBitmapLoader
import com.audiobrowser.util.NetworkConnectivityMonitor
import com.audiobrowser.util.PlayingStateFactory
import com.audiobrowser.util.RepeatModeFactory
import com.audiobrowser.util.SvgArtworkRenderer
import com.audiobrowser.util.TrackFactory
import com.audiobrowser.util.TrackFactory.setFavorite
import com.audiobrowser.util.awaitOrTimeout
import com.audiobrowser.util.isAutomotive
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.FavoriteChangedEvent
import com.margelo.nitro.audiobrowser.GateEvent
import com.margelo.nitro.audiobrowser.GateReason
import com.margelo.nitro.audiobrowser.ImageContext
import com.margelo.nitro.audiobrowser.ImageSource
import com.margelo.nitro.audiobrowser.NativeGateRequest
import com.margelo.nitro.audiobrowser.NowPlayingMetadata
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackProgressUpdatedEvent
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayingState
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.SearchParams
import com.margelo.nitro.audiobrowser.Track
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * The window behind its live edge that lets a live item pause in place, as on-demand does (a DVR
 * window). A shorter one is a stream that can't be rewound: a pause closes it.
 */
private const val PAUSABLE_LIVE_WINDOW_MS = 30_000L

/**
 * STOPPED stays out: stop() idles the engine itself, and [Player.stopRetrying] would make it
 * PAUSED.
 */
private val LIVE_CONNECTION_STATES =
  setOf(
    PlaybackState.LOADING,
    PlaybackState.READY,
    PlaybackState.BUFFERING,
    PlaybackState.PLAYING,
    PlaybackState.PAUSED,
  )

@SuppressLint("RestrictedApi")
class Player
internal constructor(
  internal val context: Context,
  castRemote: (Context, String?) -> RemotePlayback?,
  sonosRemote: (Context, CoroutineScope) -> RemotePlayback,
) {
  constructor(context: Context) : this(context, ::createCastRemote, ::SonosRemote)

  val appKilledPlaybackBehavior: AppKilledPlaybackBehavior
    get() = options.appKilledPlaybackBehavior

  private var options = PlayerUpdateOptions()
  private val mainHandler = Handler(Looper.getMainLooper())

  /** Audio focus while [retryLoop] keeps the engine idle; see [RetryFocus]. */
  internal val retryFocus: RetryFocus =
    RetryFocus(
      RetryFocus.Platform(
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
        mainHandler,
      ) {
        exoPlayer.audioAttributes.audioAttributesV21.audioAttributes
      },
      // A permanent loss pauses, as media3 does while playing.
      onLoss = { playWhenReady = false },
      onSuppressionChanged = {
        // Posted: it changes the play intent the loop is reporting from.
        mainHandler.post {
          retryLoop?.playIntentChanged(shouldPlayWhenReady)
          refreshPlayingState()
        }
      },
    )
  internal var callbacks: Callbacks? = null
  private lateinit var mediaSession: MediaSession
  val networkMonitor: NetworkConnectivityMonitor = NetworkConnectivityMonitor(context)
  internal val equalizer =
    EqualizerController(onSettingsChanged = { settings -> callbacks?.onEqualizerChanged(settings) })
  private val mediaSessionCallback = MediaSessionCallback(this)
  internal val playbackStateStore = PlaybackStateStore(this)
  // Fades the local engine only: a remote receiver keeps its own volume.
  internal val volumeFader =
    VolumeFader(getVolume = { exoPlayer.volume }, setVolume = { exoPlayer.volume = it })
  internal val sleepTimer =
    SleepTimerManager(
      volumeFader,
      pause = { pause() },
      onChanged = { callbacks?.onSleepTimerChanged(it) },
    )

  /** The local engine. Rebuilt when the engine options change. */
  internal lateinit var exoPlayer: ExoPlayer
    private set

  /**
   * What plays: [exoPlayer], or while a remote output is on, the remote-switching player around it.
   * Every playback read and command goes here. Local-only work that would disturb a remote (retry,
   * reconnect, stuck recovery, the shuffle order) goes through [localEngine]; the equalizer,
   * skip-silence, buffering and the volume fade act on [exoPlayer] alone.
   */
  lateinit var sessionPlayer: MediaPlayer
    private set

  /** [sessionPlayer] behind the remote-command seam: what the MediaSession wraps. */
  internal lateinit var forwardingPlayer: InterceptingPlayer

  private val remoteOutputs =
    RemoteOutputsController(
      context,
      ::resolveRemoteUrls,
      castRemote,
      sonosRemote,
      localHeld = { retryLoop?.reconnecting == true },
    )

  internal val localEngine =
    LocalEngine({ exoPlayer }, isRemote = { remoteOutputs.player?.isRemote == true })

  private var keepSessionAliveOnError = true

  /** Automatic retry for the local engine; null when the setup turns retry off. */
  internal var retryLoop: RetryLoop? = null
    private set

  /** What the wake mode holds while [retryLoop] keeps the engine idle; see [RetryLocks]. */
  internal var retryLocks: RetryLocks? = null
    private set

  /** The playing item's id, for the load error policy on ExoPlayer's playback thread. */
  @Volatile internal var playingMediaId: String? = null

  /** The last recoverable load failure, the cause of a give-up that has no player error in hand. */
  @Volatile private var lastLoadFailure: IOException? = null

  private val retryEngine =
    object : RetryLoop.Engine {
      override fun reconnect() =
        retryFocus.handOver { localEngine.retry(toLiveEdge = isCurrentItemLive) }

      // A play after a pause over a retry connects nothing until then: no failure shows it.
      override fun holdChanged(held: Boolean, kept: Boolean) {
        retryLocks?.hold(held)
        // A suppressed request outlives the intent it dropped: it waits for the focus to return.
        if (held) retryFocus.hold() else if (!kept || !retryFocus.suppressed) retryFocus.release()
      }

      override fun awaitNetwork() =
        showRetryingError(
          PlaybackErrorClassifier.retryingLoadError(lastLoadFailure ?: IOException(), false)
        )

      override fun giveUp(cause: PlaybackException?) {
        // A remote output took over before the listener heard it: the loop is done either way.
        if (!localEngine.isActive) return
        // ERROR first: the state machine holds it through the IDLE the stop brings.
        playerListener.surfaceTerminal(
          cause
            ?: PlaybackException(
              "No audio within the retry budget",
              lastLoadFailure,
              PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            )
        )
        exoPlayer.stop()
      }
    }

  private var lastEmittedPlayWhenReady: Boolean? = null

  /**
   * Emits a playWhenReady change to JS once per value. Both the synchronous clear at ENDED in
   * [setPlaybackState] and the media3 listener (changes ExoPlayer makes itself, e.g. audio focus)
   * call it; their overlap is dropped.
   */
  internal fun emitPlayWhenReadyChanged(value: Boolean) {
    if (lastEmittedPlayWhenReady == value) return
    lastEmittedPlayWhenReady = value
    callbacks?.onPlaybackPlayWhenReadyChanged(PlaybackPlayWhenReadyChangedEvent(value))
  }

  private lateinit var mediaFactory: MediaFactory
  private lateinit var loadControl: DynamicLoadControl
  private var automaticBufferManager: AutomaticBufferManager? = null

  @Volatile private var browserRegistered = CompletableDeferred<AudioBrowser>()
  private var _coilBitmapLoader: CoilBitmapLoader? = null

  /** For SVG pre-rendering of Android Auto browse items. Set by the service. */
  var imageLoader: coil3.ImageLoader? = null

  /**
   * Called on the main thread when a resumption meant to start playback fails. Set by the service.
   */
  var onPlaybackResumptionFailed: () -> Unit = {}

  /** Maps the browse content provider's content:// tokens to artwork URLs. */
  val browseArtworkRegistry = com.audiobrowser.browser.BrowseArtworkRegistry()

  init {
    Automotive.device = context.isAutomotive
    TrackFactory.favoriteEnabled = options.capabilities.favoriteEnabled
    TrackFactory.artworkRoutes =
      ArtworkRoutes(browseArtworkRegistry, ArtworkUris.authorityFor(context.packageName))
  }

  var coilBitmapLoader: CoilBitmapLoader?
    get() = _coilBitmapLoader
    set(value) {
      _coilBitmapLoader = value
    }

  var browser: AudioBrowser? = null
    set(value) {
      // A completed registration is the previous browser's; an open one keeps its waiters.
      if (browserRegistered.isCompleted) {
        browserRegistered = CompletableDeferred()
      }
      field = value
    }

  /**
   * Marks the browser's routes and tabs configured: completes [awaitBrowser] and lets Android Auto
   * browse. Call after setting [browser] and its configuration.
   */
  fun notifyBrowserConfigurationReady() {
    val audioBrowser = browser ?: return

    if (!browserRegistered.isCompleted) {
      Timber.d("Browser configuration ready - completing deferred")
      browserRegistered.complete(audioBrowser)
      // Android Auto may have subscribed on a cold start before the browser was ready.
      mediaSessionCallback.notifyBrowserReady()
    }

    if (::mediaSession.isInitialized) {
      val searchAvailable = audioBrowser.browserManager.config.hasSearch
      // The session starts without search, so only a configured search needs an update.
      if (searchAvailable) {
        mediaSessionCallback.updateMediaSession(
          mediaSession,
          options.capabilities,
          options.remoteButtonLayout,
          searchAvailable,
          options.forwardJumpInterval,
          options.backwardJumpInterval,
        )
      }
    }
  }

  /**
   * The browser [notifyBrowserConfigurationReady] registered, waiting for it if none is.
   *
   * @throws TimeoutCancellationException if it is not registered within 10 seconds
   */
  suspend fun awaitBrowser(): AudioBrowser =
    try {
      withTimeout(10.seconds) { browserRegistered.await() }
    } catch (e: TimeoutCancellationException) {
      Timber.e("Timed out waiting for browser registration (10s)")
      throw e
    }

  internal lateinit var playerListener: PlayerListener
    private set

  private var cache: SimpleCache? = null

  private var destroyed = false

  private val progressTimer: PlaybackTimer by lazy {
    PlaybackTimer(
      isActive = {
        it == PlaybackState.LOADING || it == PlaybackState.BUFFERING || it == PlaybackState.PLAYING
      }
    ) {
      val index = currentIndex ?: return@PlaybackTimer
      val event =
        PlaybackProgressUpdatedEvent(
          position = position.toSeconds(),
          duration = duration.toSeconds(),
          buffered = bufferedPosition.toSeconds(),
          track = index.toDouble(),
        )
      callbacks?.onPlaybackProgressUpdated(event)
    }
  }

  private val intervalTimer: PlaybackTimer by lazy {
    PlaybackTimer(isActive = { it == PlaybackState.PLAYING }) { callbacks?.onPlaybackInterval() }
  }

  internal var playingState: PlayingState = PlayingState(false, false)
    private set

  val currentTrack: Track?
    get() = sessionPlayer.currentMediaItem?.let { TrackFactory.fromMedia3(it) }

  internal var lastTrack: Track? = null
  internal var lastIndex: Int? = null

  var playbackError: PlaybackError? = null
    internal set

  internal var playbackState: PlaybackState = PlaybackState.NONE
    private set

  fun getPlayback(): Playback {
    return Playback(playbackState, playbackError)
  }

  fun getPlayingState(): PlayingState {
    return playingState
  }

  /**
   * Re-derives [playingState] from [shouldPlayWhenReady] and [playbackState], emitting when it
   * changed. Its only writer: call it wherever either input changes.
   */
  internal fun refreshPlayingState() {
    val newPlayingState = PlayingStateFactory.derive(shouldPlayWhenReady, playbackState)
    if (newPlayingState == playingState) return
    Timber.d(
      "PlayingState changed: playing=${newPlayingState.playing}, buffering=${newPlayingState.buffering}"
    )
    playingState = newPlayingState
    callbacks?.onPlaybackPlayingState(playingState)
  }

  var playWhenReady: Boolean
    get() = sessionPlayer.playWhenReady
    set(value) {
      // ExoPlayer's setPlayWhenReady(true) neither restarts an ended engine (media3 replays only on
      // its media-button path) nor loads an idle one. Here in the setter so every writer plays
      // alike: play(), togglePlayback(), JS, the session.
      if (value && sessionPlayer.playbackState == MediaPlayer.STATE_ENDED) {
        sessionPlayer.seekToDefaultPosition()
      }
      sessionPlayer.playWhenReady = value
      // No-op unless the engine is idle, so this reconnects only after a stop, an error, a
      // cancelled retry or a rebuild, and never re-buffers a healthy stream. Reconnecting is also
      // how a live stream rejoins its live edge. Not while the retry loop paces the reconnect.
      if (
        value &&
          currentTrack != null &&
          retryView(sessionPlayer.playbackState) == MediaPlayer.STATE_IDLE
      ) {
        // An idle engine keeps its position, a seek's while parked included: within a live
        // window, the reconnect would resume there.
        if (currentTrack?.live == true && !liveItemPausable) sessionPlayer.seekToDefaultPosition()
        sessionPlayer.prepare()
      }
    }

  /**
   * The intent a setup carries, raised without loading: a setup repeats, as on a JS reload, and
   * would otherwise start a stopped or failed queue. A play loads.
   */
  internal fun applySetupPlayWhenReady(value: Boolean) {
    sessionPlayer.playWhenReady = value
  }

  val duration: Long
    get() = if (sessionPlayer.duration == C.TIME_UNSET) 0 else sessionPlayer.duration

  internal var oldPosition = 0L

  val position: Long
    get() =
      if (sessionPlayer.currentPosition == C.INDEX_UNSET.toLong()) 0
      else sessionPlayer.currentPosition

  val bufferedPosition: Long
    get() =
      if (sessionPlayer.bufferedPosition == C.INDEX_UNSET.toLong()) 0
      else sessionPlayer.bufferedPosition

  /** From 0 to 1: the local engine's, or while remote, the receiver's. */
  var volume: Float
    get() = sessionPlayer.volume
    set(value) {
      sessionPlayer.volume = value
    }

  var playbackSpeed: Float
    get() = sessionPlayer.playbackParameters.speed
    set(value) {
      sessionPlayer.setPlaybackSpeed(value)
    }

  val isPlaying
    get() = sessionPlayer.isPlaying

  /**
   * False while ExoPlayer suppresses output (a transient audio focus loss) though [playWhenReady]
   * stays raised to resume on; mirrors media3's `ExoPlayerImplInternal.shouldPlayWhenReady`.
   */
  internal val shouldPlayWhenReady: Boolean
    get() =
      sessionPlayer.playWhenReady &&
        sessionPlayer.playbackSuppressionReason == MediaPlayer.PLAYBACK_SUPPRESSION_REASON_NONE &&
        !retryFocus.suppressed

  var repeatMode: RepeatMode
    get() = RepeatModeFactory.fromMedia3(sessionPlayer.repeatMode)
    internal set(value) {
      sessionPlayer.repeatMode = RepeatModeFactory.toMedia3(value)
    }

  val currentIndex: Int?
    get() =
      if (sessionPlayer.currentMediaItemIndex == C.INDEX_UNSET) null
      else sessionPlayer.currentMediaItemIndex

  var shuffleMode: Boolean
    get() = sessionPlayer.shuffleModeEnabled
    set(value) {
      sessionPlayer.shuffleModeEnabled = value
    }

  val trackCount: Int
    get() = sessionPlayer.mediaItemCount

  val isEmpty: Boolean
    get() = sessionPlayer.mediaItemCount == 0

  val tracks: Array<Track>
    get() =
      (0 until sessionPlayer.mediaItemCount)
        .map { index -> TrackFactory.fromMedia3(sessionPlayer.getMediaItemAt(index)) }
        .toTypedArray()

  /** In playback order, so shuffle and repeat count; not the last linear index. */
  val isLastInPlaybackOrder: Boolean
    get() = sessionPlayer.nextMediaItemIndex == C.INDEX_UNSET

  /**
   * Changes whenever the queue's tracks or their order change, whoever changed them, and never
   * repeats in the process, so equal readings mean the same queue even across players.
   */
  var queueVersion = 0
    private set

  private var versionedMediaIds = emptyList<String>()

  /**
   * Compares media ids: an engine rebuild sets the same items again and reports a playlist change.
   */
  internal fun onPlaylistChanged() {
    val mediaIds =
      (0 until sessionPlayer.mediaItemCount).map { sessionPlayer.getMediaItemAt(it).mediaId }
    if (mediaIds == versionedMediaIds) return
    versionedMediaIds = mediaIds
    queueVersion = ++lastQueueVersion
  }

  /** The path the queue was expanded from, so selecting from the same source skips re-expanding. */
  var queueSourcePath: String? = null
    internal set

  /** @throws IllegalArgumentException if [index] is out of bounds. */
  fun getTrack(index: Int): Track {
    if (index < 0 || index >= sessionPlayer.mediaItemCount) {
      throw IllegalArgumentException(
        "Track index $index is out of bounds (size: ${sessionPlayer.mediaItemCount})"
      )
    }
    return TrackFactory.fromMedia3(sessionPlayer.getMediaItemAt(index))
  }

  var skipSilence: Boolean
    get() = exoPlayer.skipSilenceEnabled
    internal set(value) {
      exoPlayer.skipSilenceEnabled = value
    }

  private var engineOptions: PlayerSetupOptions? = null

  private var deferredSetup: PlayerSetupOptions? = null

  /**
   * Builds the ExoPlayer on the first call, and rebuilds it only when the engine options changed. A
   * rebuild carries over the queue, the position and the play intent. During a transient audio
   * focus loss with the intent up, the rebuild waits until focus returns, the intent drops, or a
   * play asks for focus again.
   */
  fun setup(setupOptions: PlayerSetupOptions) {
    nowPlaying.enabled = setupOptions.autoUpdateNowPlayingMetadata
    // Unwrapped from the Nitro Func so the updater never touches bridge types.
    nowPlaying.formatter =
      setupOptions.nowPlayingMetadataFormatter?.let { f ->
        { params -> f.invoke(params).awaitOrTimeout() }
      }

    deferredSetup = setupOptions.takeIf { it.engineOptions() != engineOptions }
    rebuildIfDue()
    if (remoteOutputs.apply(setupOptions.remoteOutputs, exoPlayer)) attachSessionPlayer()
  }

  /**
   * Runs a [deferredSetup] rebuild unless the engine waits out a transient focus loss with the
   * intent up: the new engine would ask for focus at once, taking it from the app that holds it, or
   * lose the intent when refused, as during a call. Call when the engine's state, intent or
   * suppression changes.
   */
  internal fun rebuildIfDue() {
    val options = deferredSetup ?: return
    // A stopped engine keeps the suppression but holds no focus, and is carried unprepared.
    if (
      engineOptions != null &&
        exoPlayer.playbackState != MediaPlayer.STATE_IDLE &&
        exoPlayer.playWhenReady &&
        exoPlayer.playbackSuppressionReason ==
          MediaPlayer.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
    ) {
      return
    }
    deferredSetup = null
    buildEngine(options)
    engineOptions = options.engineOptions()
  }

  /**
   * [rebuildIfDue] once the current command returns: ExoPlayer reports an intent change from inside
   * the command, and a rebuild there would land between its steps, as between stop()'s drop of the
   * intent and its stop.
   */
  internal fun rebuildIfDueLater() {
    if (deferredSetup != null) mainHandler.post(::rebuildIfDue)
  }

  private fun buildEngine(setupOptions: PlayerSetupOptions) {
    Timber.Forest.d("Setting up player with new options")
    val isInitialSetup = engineOptions == null
    val carried = if (isInitialSetup) null else EnginePlayback.of(exoPlayer)

    if (!isInitialSetup) {
      forwardingPlayer.removeListener(playerListener)
      exoPlayer.release()
      Timber.Forest.d("Player cleanup completed")
    }

    if (setupOptions.maxCacheSize > 0) {
      // SimpleCache locks its folder: the constructor throws until the previous one is released.
      cache?.release()
      cache =
        SimpleCache(
          File(context.cacheDir, "RNAB"),
          LeastRecentlyUsedCacheEvictor(
            // MB to bytes
            setupOptions.maxCacheSize.toLong() * 1_000_000
          ),
          StandaloneDatabaseProvider(context),
        )
    } else {
      cache?.release()
      cache = null
    }

    val engine =
      buildPlayerEngine(
        context,
        setupOptions,
        cache,
        onLoadFailure = ::onLoadFailure,
        isCurrent = { it.mediaId == playingMediaId },
        resolveMediaConfig = { url, track -> browser?.getMediaRequestConfig(url, track) },
      )
    loadControl = engine.loadControl
    mediaFactory = engine.mediaFactory
    exoPlayer = engine.exoPlayer
    // A reconnect the old loop held between connections: the engine is carried idle and unprepared.
    val heldIdle = retryLoop?.reconnecting == true && localEngine.isActive
    retryLoop?.suspend()
    // Before the listener and the session are attached, so neither hears the playback replayed.
    carried?.restoreTo(exoPlayer)
    remoteOutputs.setLocal(exoPlayer)
    keepSessionAliveOnError = setupOptions.keepSessionAliveOnError

    if (isInitialSetup) playerListener = PlayerListener(this)
    attachSessionPlayer()
    if (heldIdle && exoPlayer.playbackState == ExoPlayer.STATE_IDLE && exoPlayer.mediaItemCount > 0)
      exoPlayer.prepare()
    playerListener.engineAttached()
    retryLoop = newRetryLoop(setupOptions)
    playingMediaId = exoPlayer.currentMediaItem?.mediaId

    if (isInitialSetup) {
      callbacks?.onPlaybackChanged(Playback(PlaybackState.NONE, null))
      equalizer.initialize(exoPlayer.audioSessionId)
    } else if (localEngine.isActive) {
      // No listener heard the restore. Its prepare() leaves the engine buffering; an unprepared one
      // keeps its state. While remote, the receiver plays on unchanged.
      if (exoPlayer.playbackState != ExoPlayer.STATE_IDLE) setPlaybackState(PlaybackState.BUFFERING)
      // The new engine starts unsuppressed: an unchanged state would not re-derive it.
      refreshPlayingState()
    }

    setupAutomaticBufferManager(setupOptions.automaticBuffer)
  }

  // Call whenever [exoPlayer] or the remote player changes.
  private fun attachSessionPlayer() {
    if (::forwardingPlayer.isInitialized) forwardingPlayer.removeListener(playerListener)
    sessionPlayer = remoteOutputs.player ?: exoPlayer
    forwardingPlayer =
      InterceptingPlayer(
        sessionPlayer,
        { callbacks },
        { options },
        keepSessionAliveOnError,
        playerState = { playbackState },
        engineView = ::retryView,
        masksPlaying = { retryMasksPlaying },
        playPlayer = ::play,
        stopPlayer = ::stop,
      )
    forwardingPlayer.addEngineListener(playerListener)
    if (::mediaSession.isInitialized) mediaSession.player = forwardingPlayer
  }

  private suspend fun resolveRemoteUrls(track: Track): RemoteUrls {
    val src = checkNotNull(track.src) { "The track has no src" }
    val browserManager = browser?.browserManager
    return RemoteUrls(
      url = browserManager?.resolveMediaUrl(src, track)?.url() ?: src,
      artworkUrl = browserManager?.resolveArtworkUrl(track)?.uri,
    )
  }

  private fun setupAutomaticBufferManager(enabled: Boolean) {
    automaticBufferManager?.detach()

    if (enabled) {
      val defaultConfig = loadControl.getBufferConfig()
      automaticBufferManager =
        AutomaticBufferManager(loadControl, defaultConfig).also { it.attach(exoPlayer) }
      Timber.d("Automatic buffer management enabled")
    } else {
      automaticBufferManager = null
      Timber.d("Automatic buffer management disabled")
    }
  }

  fun observeNetworkConnectivity(scope: kotlinx.coroutines.CoroutineScope) {
    networkMonitor.observeOnline(scope) { isOnline ->
      callbacks?.onOnlineChanged(isOnline)
      retryLoop?.onlineChanged(isOnline)

      // The formatter tells a stall from being offline, so it renders on a connectivity change too.
      nowPlaying.render()
    }
  }

  /** Replaces the current track, keeping the rest of the queue; on an empty queue, adds it. */
  fun load(track: Track) {
    if (sessionPlayer.mediaItemCount == 0) {
      add(track)
    } else {
      val index = sessionPlayer.currentMediaItemIndex
      replaceTrack(index, track)
      sessionPlayer.seekTo(index, C.TIME_UNSET)
      sessionPlayer.prepare()
    }
  }

  fun add(track: Track) = add(arrayOf(track))

  /**
   * Loads only into an empty queue: a queue that had tracks keeps its current one as it was, idle
   * after a stop or a cancelled retry.
   *
   * @throws IllegalArgumentException unless [atIndex] is within the queue, or -1 to append.
   */
  fun add(tracks: Array<Track>, atIndex: Int = -1) {
    validateInsertIndex(atIndex)
    val count = sessionPlayer.mediaItemCount
    sessionPlayer.addMediaItems(
      if (atIndex == -1) count else atIndex,
      TrackFactory.toMedia3(tracks),
    )
    if (count == 0) sessionPlayer.prepare()
  }

  /** @throws IllegalArgumentException if an index is out of bounds or repeated. */
  fun remove(indexes: List<Int>) {
    if (indexes.toSet().size != indexes.size) {
      throw IllegalArgumentException("Duplicate indexes provided")
    }
    indexes.forEach { validateIndex(it) }
    val removesCurrent = sessionPlayer.currentMediaItemIndex in indexes
    indexes.sortedDescending().forEach { sessionPlayer.removeMediaItem(it) }
    // Loads the new current track on an engine an error left idle; a no-op otherwise.
    if (removesCurrent && playWhenReady) prepare()
  }

  fun next() = loadAfter { sessionPlayer.seekToNextMediaItem() }

  fun previous() = loadAfter { sessionPlayer.seekToPreviousMediaItem() }

  /**
   * Runs [move] and prepares an idle engine, unless the queue ended and stays where it was: a
   * rebuild carries an ended engine idle, and it stays ended as an ended engine does.
   */
  private inline fun loadAfter(move: () -> Unit) {
    val index = sessionPlayer.currentMediaItemIndex
    move()
    if (playbackState == PlaybackState.ENDED && sessionPlayer.currentMediaItemIndex == index) return
    sessionPlayer.prepare()
  }

  /**
   * A [toIndex] past the end moves the track to the end.
   *
   * @throws IllegalArgumentException if [fromIndex] is out of bounds.
   */
  fun move(fromIndex: Int, toIndex: Int) {
    validateIndex(fromIndex)
    sessionPlayer.moveMediaItem(fromIndex, toIndex)
  }

  /** @throws IllegalArgumentException if [index] is out of bounds. */
  fun skipTo(index: Int) {
    validateIndex(index)
    sessionPlayer.seekTo(index, C.TIME_UNSET)
    sessionPlayer.prepare()
  }

  fun setQueue(
    tracks: Array<Track>,
    startIndex: Int = 0,
    startPositionMs: Long = 0,
    sourcePath: String? = null,
  ) {
    val mediaItems = TrackFactory.toMedia3(tracks).toMutableList()
    sessionPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
    // While remote, the queue plays in list order.
    if (mediaItems.isNotEmpty()) {
      localEngine.setShuffleOrder(shuffleOrderLedBy(startIndex, mediaItems.size))
    }
    queueSourcePath = sourcePath
    sessionPlayer.prepare()
  }

  /** @throws IllegalArgumentException if [index] is out of bounds. */
  fun replaceTrack(index: Int, track: Track) {
    validateIndex(index)
    val isCurrent = index == currentIndex
    if (isCurrent) nowPlaying.forgetPublished()
    sessionPlayer.replaceMediaItem(index, TrackFactory.toMedia3(track))
    if (isCurrent) nowPlaying.render()
  }

  /**
   * @return false only when there is no current track, so MediaSession callers can report an
   *   invalid state instead of a success the controller then watches revert.
   */
  fun setActiveTrackFavorited(favorited: Boolean): Boolean {
    val currentTrack = this.currentTrack ?: return false

    currentTrack.identity?.let { identity ->
      browser?.browserManager?.updateFavorite(identity, favorited)
    }

    val index = sessionPlayer.currentMediaItemIndex
    // Still applied: the favorite is persisted above, and there is no heart to revert.
    if (index == C.INDEX_UNSET) return true

    val updatedTrack = currentTrack.copy(favorited = favorited)
    restampMediaItem(index, updatedTrack) { setFavorite(favorited) }

    updateFavoriteButtonState(favorited)

    // The only emit: a favorite toggle mutates the active track in place, so the track and queue
    // change events stay transition-only.
    callbacks?.onFavoriteChanged(FavoriteChangedEvent(updatedTrack, favorited))
    return true
  }

  fun toggleActiveTrackFavorited(): Boolean {
    val currentTrack = this.currentTrack ?: return false
    return setActiveTrackFavorited(currentTrack.favorited != true)
  }

  // MARK: - Now Playing Metadata

  /** [NowPlayingUpdater] does the rendering; this is its platform surface. */
  val nowPlaying: NowPlayingUpdater =
    NowPlayingUpdater(
      object : NowPlayingSurface {
        override val currentIndex
          get() = this@Player.currentIndex

        override val currentTrack
          get() = this@Player.currentTrack

        override val playbackState
          get() = this@Player.playbackState

        override val playbackError
          get() = this@Player.playbackError

        override val playWhenReady
          get() = shouldPlayWhenReady

        override val isRebuffering
          get() = loadControl.isRebuffering

        override val isOnline
          get() = networkMonitor.getOnline()

        override val hasNowPlayingArtworkConfig
          get() = browser?.browserManager?.config?.nowPlayingArtwork != null

        override fun stampFields(
          index: Int,
          track: Track,
          title: String?,
          secondaryLine: String?,
          album: String?,
        ) =
          restampMediaItem(index, track) {
            setTitle(title)
              .setDisplayTitle(title)
              .setArtist(secondaryLine)
              // Android Auto reads DISPLAY_SUBTITLE, not ARTIST, once DISPLAY_TITLE is set;
              // `artist` still drives the lock screen and notification.
              .setSubtitle(secondaryLine)
              .setAlbumTitle(album)
          }

        override fun stampArtwork(index: Int, track: Track, uri: String) =
          restampMediaItem(index, track) { setArtworkUri(uri.toUri()) }

        override suspend fun resolveNowPlayingArtwork(track: Track, sizePx: Double): String? {
          val browserManager = browser?.browserManager ?: return null
          val config = browserManager.config.nowPlayingArtwork ?: return null
          val resolved =
            try {
              browserManager.resolveArtworkUrl(track, config, ImageContext(sizePx, sizePx))
            } catch (e: Exception) {
              Timber.e(e, "Failed to resolve now-playing artwork for track id=${track.id}")
              null
            }
          val uri = resolved?.uri?.takeIf { it.isNotEmpty() } ?: return null
          val published =
            TrackFactory.sessionArtworkUri(uri, resolved.headers, SvgArtworkRenderer.isSvgUrl(uri))
          // Media3 hands this URI back to the bitmap loader, which re-resolves it with the
          // nowPlayingArtwork config rather than the global artwork config.
          browserManager.artworkResolutions.register(published, track, config)
          return published
        }

        override fun emitNowPlayingChanged(metadata: NowPlayingMetadata) {
          callbacks?.onNowPlayingChanged(metadata)
        }
      },
      MainScope(),
    )

  /**
   * One replacement for the whole queue: each separate one is a timeline change and a queue event
   * to JS.
   */
  private fun restampFavorites() {
    if (!::sessionPlayer.isInitialized || sessionPlayer.mediaItemCount == 0) return
    val items =
      (0 until sessionPlayer.mediaItemCount).map { index ->
        val item = sessionPlayer.getMediaItemAt(index)
        val track = item.localConfiguration?.tag as? Track ?: return@map item
        item.restamped(track) { setFavorite(track.favorited) }
      }
    sessionPlayer.replaceMediaItems(0, items.size, items)
  }

  private fun restampMediaItem(
    index: Int,
    track: Track,
    mutate: MediaMetadata.Builder.() -> MediaMetadata.Builder,
  ) {
    sessionPlayer.replaceMediaItem(
      index,
      sessionPlayer.getMediaItemAt(index).restamped(track, mutate),
    )
  }

  /** Keeps the uri so ExoPlayer updates the item in place instead of reloading it. */
  private fun MediaItem.restamped(
    track: Track,
    mutate: MediaMetadata.Builder.() -> MediaMetadata.Builder,
  ): MediaItem =
    buildUpon()
      .setUri(localConfiguration?.uri)
      .setMediaMetadata(mediaMetadata.buildUpon().mutate().build())
      .setTag(track)
      .build()

  private fun newRetryLoop(options: PlayerSetupOptions): RetryLoop? {
    val (maxRetries, recoveryMs, firstConnectMs) =
      when (val policy = options.retryPolicy) {
        RetryPolicy.Default -> return null
        is RetryPolicy.Infinite ->
          Triple(null, policy.maxRetryDurationMs, policy.firstConnectMaxRetryDurationMs)
        is RetryPolicy.Limited ->
          Triple(
            policy.maxRetries,
            policy.maxRetryDurationMs,
            policy.firstConnectMaxRetryDurationMs,
          )
      }
    val recovery = recoveryMs ?: 120_000
    val firstConnect = firstConnectMs ?: 12_000
    retryLocks =
      RetryLocks(context, options.wakeMode, maxHoldMs = recovery + firstConnect, mainHandler)
    return RetryLoop(
        clock = SystemClock::elapsedRealtime,
        timer = { delayMs, action ->
          val runnable = Runnable(action)
          mainHandler.postDelayed(runnable, delayMs)
          val cancel: () -> Unit = { mainHandler.removeCallbacks(runnable) }
          cancel
        },
        engine = retryEngine,
        maxRetries = maxRetries,
        recoveryMs = recovery,
        firstConnectMs = firstConnect,
        online = networkMonitor.getOnline(),
      )
      .also {
        it.playIntentChanged(shouldPlayWhenReady)
        if (localEngine.isActive && exoPlayer.playbackState != MediaPlayer.STATE_IDLE) {
          it.loadStarted()
          it.isPlayingChanged(exoPlayer.isPlaying)
        } else {
          it.suspend()
        }
      }
  }

  /**
   * A new Load for the retry loop (ADR 0014): a track change, or the phone taking over playback.
   */
  internal fun startRetryLoad() {
    playingMediaId = exoPlayer.currentMediaItem?.mediaId
    val loop = retryLoop ?: return
    if (!localEngine.isActive) return loop.suspend()
    lastLoadFailure = null
    if (exoPlayer.playbackState == MediaPlayer.STATE_IDLE) {
      // Nothing connects an idle engine's new track but a prepare, and leaving idle starts its
      // Load.
      val held = loop.reconnecting
      loop.suspend()
      if (held) exoPlayer.prepare()
      return
    }
    loop.loadStarted()
    loop.isPlayingChanged(exoPlayer.isPlaying)
  }

  /**
   * The engine state the listener and the session are shown: the retry loop's idle engine reads
   * buffering, and so does a READY blip before a failed connection's error surfaces.
   */
  internal fun retryView(@MediaPlayer.State engineState: Int): Int {
    val loop = retryLoop?.takeIf { localEngine.isActive } ?: return engineState
    return RetryView.engineState(
      engineState,
      loop.reconnecting,
      loop.failurePending,
      exoPlayer.totalBufferedDuration,
    )
  }

  /** Whether the engine's playing is a blip the retry loop hides; see [retryView]. */
  internal val retryMasksPlaying: Boolean
    get() =
      localEngine.isActive &&
        RetryView.isBlip(retryLoop?.failurePending == true, exoPlayer.totalBufferedDuration)

  /** Main thread only (ExoPlayer access). */
  private fun findQueueTrackByArtworkUri(uri: String): Track? {
    for (i in 0 until sessionPlayer.mediaItemCount) {
      val item = sessionPlayer.getMediaItemAt(i)
      if (item.mediaMetadata.artworkUri?.toString() == uri) {
        return item.localConfiguration?.tag as? Track
      }
    }
    return null
  }

  /**
   * For [CoilBitmapLoader]: the resolution registry, then the queue track that published [uri],
   * then headers alone for a URI nothing attributes. Null means fetch the URI as-is.
   */
  suspend fun resolveDisplayArtwork(uri: String, sizeHintPixels: Int?): ImageSource? {
    val browserManager = browser?.browserManager ?: return null
    val imageContext =
      sizeHintPixels?.takeIf { it > 0 }?.let { ImageContext(it.toDouble(), it.toDouble()) }
    browserManager.displayArtworkSource(uri, imageContext)?.let {
      return it
    }
    // On Android Automotive OS the queue publishes provider URIs; the resolutions are keyed by URL.
    TrackFactory.artworkRoutes
      ?.publicUrl(uri.toUri())
      ?.takeIf { it != uri }
      ?.let { browserManager.displayArtworkSource(it, imageContext) }
      ?.let {
        return it
      }
    withContext(Dispatchers.Main) { findQueueTrackByArtworkUri(uri) }
      ?.let { track ->
        return browserManager.resolveArtworkUrl(track, null, imageContext)
      }
    return browserManager.unattributedArtworkSource(uri)
  }

  internal fun updateFavoriteButtonState(favorited: Boolean?) {
    if (!::mediaSession.isInitialized) return
    mediaSessionCallback.commandManager.updateFavoriteState(mediaSession, favorited)
  }

  internal fun updateLiveState(live: Boolean) {
    if (!::mediaSession.isInitialized) return
    mediaSessionCallback.commandManager.updateLiveState(mediaSession, live)
  }

  fun removeUpcomingTracks() {
    val index = sessionPlayer.currentMediaItemIndex
    if (index == C.INDEX_UNSET) return
    val lastIndex = sessionPlayer.mediaItemCount
    val fromIndex = index + 1

    sessionPlayer.removeMediaItems(fromIndex, lastIndex)
  }

  fun play() {
    playWhenReady = true
  }

  /**
   * No-op unless the track declares `live`; ExoPlayer never marks progressive ICY radio live. A
   * stream with a live window seeks to its edge; anything else reconnects, which for a non-seekable
   * stream is the live edge.
   */
  fun seekToLiveEdge() {
    if (currentTrack?.live != true || liveStreamParked) return
    // A receiver loads a live stream again on a seek to its default position.
    if (!localEngine.reconnectAtLiveEdge()) sessionPlayer.seekToDefaultPosition()
  }

  /**
   * @return whether a result started playing: false when gated (after onGate), with no results, or
   *   on any error, the browser's registration timeout included.
   */
  suspend fun playFromSearch(params: SearchParams): Boolean {
    return try {
      val audioBrowser = awaitBrowser()
      // Gated like browsing, or voice search would be a way around the gate.
      val gateOutcome =
        audioBrowser.gateDecision(
          NativeGateRequest(reason = GateReason.SEARCH, path = null, search = params)
        )
      if (gateOutcome.gated) {
        Timber.i("playFromSearch refused — request is gated")
        audioBrowser.onGate(GateEvent(GateReason.SEARCH))
        return false
      }
      val browserManager = audioBrowser.browserManager

      Timber.d(
        "Executing voice search: mode=${params.mode}, query='${params.query}', artist='${params.artist}', album='${params.album}'"
      )
      val tracks = browserManager.searchPlayable(params)

      if (tracks != null && tracks.isNotEmpty()) {
        Timber.d("Found ${tracks.size} track(s), playing first: ${tracks[0].title}")
        setQueue(tracks)
        play()
        true
      } else {
        Timber.w("No tracks found for search: ${params}")
        false
      }
    } catch (e: Exception) {
      currentCoroutineContext().ensureActive()
      Timber.e(e, "Error handling voice search: ${params}")
      false
    }
  }

  /** An ended queue stays ended, as on an ended engine. */
  fun prepare() {
    if (liveStreamParked) return
    if (currentTrack != null && playbackState != PlaybackState.ENDED) sessionPlayer.prepare()
  }

  fun pause() {
    playWhenReady = false
  }

  fun togglePlayback() {
    when {
      // Suppressed with the intent raised: ExoPlayer resumes when focus returns. play() would
      // request focus again, and during a call that refusal drops the intent.
      playWhenReady && !shouldPlayWhenReady -> Unit
      PlayingStateFactory.derive(shouldPlayWhenReady, playbackState).playing -> pause()
      else -> play()
    }
  }

  /** Keeps the queue; the state becomes STOPPED. */
  fun stop() {
    nowPlaying.clearTimedMetadata()
    // State first, so the state machine's STOPPED guard swallows the PAUSED the drop below causes.
    setPlaybackState(PlaybackState.STOPPED)
    val ended = sessionPlayer.playbackState == MediaPlayer.STATE_ENDED
    playWhenReady = false
    // The next play is a new Load.
    retryLoop?.suspend()
    sessionPlayer.stop()
    playerListener.fenceOutgoingMetadata()
    // Idle at its end, the next prepare would end it again at once.
    if (ended) sessionPlayer.seekToDefaultPosition()
  }

  fun clear() {
    sessionPlayer.clearMediaItems()
    queueSourcePath = null
  }

  /** Idempotent. */
  fun destroy() {
    if (destroyed) return
    destroyed = true
    deferredSetup = null
    // stop() saves only if playing; before it, so its STOPPED doesn't defeat the ENDED guard.
    playbackStateStore.stopPeriodicSave()
    // An armed timer would fire pause() into the released player. Cleared before the release,
    // while a mid-ramp fade can still restore the volume.
    sleepTimer.clear()
    stop()
    nowPlaying.destroy()
    mediaSessionCallback.destroy()
    forwardingPlayer.removeListener(playerListener)
    automaticBufferManager?.detach()
    automaticBufferManager = null
    remoteOutputs.release()
    exoPlayer.release()
    cache?.release()
    cache = null
    networkMonitor.destroy()
    equalizer.release()
  }

  fun seekTo(duration: Long, unit: TimeUnit) {
    val positionMs = TimeUnit.MILLISECONDS.convert(duration, unit)
    sessionPlayer.seekTo(positionMs)
  }

  fun seekBy(offset: Long, unit: TimeUnit) {
    val positionMs = sessionPlayer.currentPosition + TimeUnit.MILLISECONDS.convert(offset, unit)
    sessionPlayer.seekTo(positionMs)
  }

  /**
   * Bumped on track change and seek so an [onLoadFailure] post for an earlier connection is
   * dropped. Written on main, read on ExoPlayer's playback thread.
   */
  @Volatile private var retryingErrorGeneration = 0

  /**
   * A recoverable load failure, on ExoPlayer's playback thread. Posted to main ahead of the state
   * changes it causes, so the retry loop holds the engine before its READY blip or error arrives.
   * It also attaches the failure to the current state, for UIs to show over the spinner: not an
   * ERROR and not [Callbacks.onPlaybackError], which stays terminal.
   */
  internal fun onLoadFailure(exception: IOException, inPlace: Boolean) {
    val atMs = SystemClock.elapsedRealtime()
    lastLoadFailure = exception
    val error = PlaybackErrorClassifier.retryingLoadError(exception, networkMonitor.getOnline())
    val generation = retryingErrorGeneration
    mainHandler.post {
      if (generation != retryingErrorGeneration) return@post
      if (localEngine.isActive) retryLoop?.connectionFailed(inPlace, atMs)
      showRetryingError(error)
    }
  }

  /** Drops the [onLoadFailure] reports still on their way: they belong to an earlier connection. */
  internal fun dropFailureReports() {
    retryingErrorGeneration++
  }

  private fun showRetryingError(error: PlaybackError) {
    // Paused, the retry would run unseen and its failure be terminal. STOPPED, ERROR and ENDED
    // reach here only from a post that raced them.
    if (
      !playWhenReady &&
        playbackState !in setOf(PlaybackState.STOPPED, PlaybackState.ERROR, PlaybackState.ENDED)
    ) {
      return stopRetrying()
    }
    // Only over a spinner: while PLAYING, buffered audio still plays and ExoPlayer's in-place
    // retry can succeed with no state change, so an error attached there would never clear.
    if (
      playbackState != PlaybackState.LOADING &&
        playbackState != PlaybackState.BUFFERING &&
        playbackState != PlaybackState.PAUSED
    ) {
      return
    }
    // Each backoff tick re-reports the same error.
    if (playbackError == error) return
    playbackError = error
    callbacks?.onPlaybackChanged(Playback(playbackState, error))
    nowPlaying.render()
  }

  /**
   * Cancels the retry a dropped play intent leaves scheduled. No-op unless a retrying error shows.
   */
  internal fun cancelRetry() {
    // The ENDED path drops the intent before it clears the error.
    if (playbackError?.retrying != true || playbackState == PlaybackState.ENDED) return
    stopRetrying()
  }

  /**
   * A paused engine goes on loading a live stream until its buffer is full, and a resume plays what
   * it buffered, behind the stream. The engine idles in PAUSED instead, closing the connection, and
   * the next play's prepare() reconnects at the live edge.
   */
  internal fun dropLiveConnection() {
    if (currentTrack?.live != true || !localEngine.isActive || liveItemPausable) return
    if (playbackState !in LIVE_CONNECTION_STATES) return
    if (exoPlayer.playbackState == ExoPlayer.STATE_IDLE) return
    stopRetrying()
  }

  /** A live window that seeks at least [PAUSABLE_LIVE_WINDOW_MS] behind its live edge. */
  private val liveItemPausable: Boolean
    get() {
      val timeline = exoPlayer.currentTimeline
      if (timeline.isEmpty) return false
      val window = timeline.getWindow(exoPlayer.currentMediaItemIndex, Timeline.Window())
      return window.isDynamic &&
        window.isSeekable &&
        !window.isPlaceholder &&
        window.defaultPositionMs >= PAUSABLE_LIVE_WINDOW_MS
    }

  /** A live track paused on an idle engine: it reconnects only to play, at the live edge. */
  private val liveStreamParked: Boolean
    get() =
      currentTrack?.live == true &&
        !playWhenReady &&
        localEngine.isActive &&
        exoPlayer.playbackState == ExoPlayer.STATE_IDLE

  /**
   * Gives up a load ExoPlayer would retry. The engine idles in PAUSED, where the next prepare()
   * reloads where it failed.
   */
  private fun stopRetrying() {
    // Before the stop: the state machine holds PAUSED through the IDLE that follows.
    setPlaybackState(PlaybackState.PAUSED)
    clearRetryingError()
    exoPlayer.stop()
    // An engine the loop already holds idle leaves no IDLE for the listener to see.
    retryLoop?.engineStopped()
  }

  /**
   * Drops the retrying error of a track that is gone. The new track may stay LOADING or BUFFERING
   * with no state change to emit the clear.
   */
  internal fun clearRetryingError() {
    dropFailureReports()
    if (playbackError?.retrying != true) return
    playbackError = null
    callbacks?.onPlaybackChanged(Playback(playbackState, null))
    nowPlaying.render()
  }

  /** Every state change goes through here: it emits the change and the events that follow it. */
  internal fun setPlaybackState(state: PlaybackState) {
    if (state != playbackState) {
      val oldState = playbackState
      playbackState = state

      forwardingPlayer.onStateChanged(oldState)
      // Whatever restarts playback from it starts a new Load.
      if (state == PlaybackState.ERROR) retryLoop?.suspend()

      // A natural end exhausts the play intent; held, it inverts the toggle, keeps audio focus and
      // the periodic save. Emitted before the state and queue-ended callbacks, matching iOS. The
      // state machine's ENDED guard keeps the drop from reporting PAUSED.
      if (state == PlaybackState.ENDED && playWhenReady) {
        playWhenReady = false
        emitPlayWhenReadyChanged(false)
      }

      // The last track fires no AUTO transition, so the end-of-track sleep timer would stay armed.
      if (state == PlaybackState.ENDED) {
        sleepTimer.onTrackEnd()
      }

      if (oldState == PlaybackState.ERROR) {
        playbackError = null
        callbacks?.onPlaybackError(null)
      }

      // A retrying error rides through LOADING, BUFFERING and PAUSED; any other state proves
      // recovery or abandons the attempt.
      if (
        playbackError?.retrying == true &&
          state != PlaybackState.LOADING &&
          state != PlaybackState.BUFFERING &&
          state != PlaybackState.PAUSED
      ) {
        playbackError = null
      }

      val playback = Playback(state, playbackError)
      callbacks?.onPlaybackChanged(playback)

      // The formatter sees the state, so any transition can change what it renders.
      nowPlaying.render()

      // A repeat mode never ends the queue, even on an ENDED from a seek to the end.
      if (state == PlaybackState.ENDED && isLastInPlaybackOrder && repeatMode == RepeatMode.OFF) {
        currentIndex?.let { index ->
          val event =
            PlaybackQueueEndedEvent(track = index.toDouble(), position = position.toSeconds())
          callbacks?.onPlaybackQueueEnded(event)
        }
        playbackStateStore.savePositionZero()
      }

      progressTimer.onPlaybackStateChanged(state)
      intervalTimer.onPlaybackStateChanged(state)
      refreshPlayingState()
    }
  }

  /** [interval] in seconds; null stops the progress events. */
  fun setProgressUpdateInterval(interval: Double?) {
    progressTimer.setInterval(interval)
  }

  fun setPlaybackIntervalEnabled(enabled: Boolean) {
    intervalTimer.setInterval(if (enabled) 1.0 else null)
  }

  fun applyOptions(options: PlayerUpdateOptions) {
    val previousOptions = this.options

    this.options = options.copy()

    val skipSilenceChanged = previousOptions.skipSilence != options.skipSilence
    val progressUpdateEventIntervalChanged =
      previousOptions.progressUpdateEventInterval != options.progressUpdateEventInterval
    val forwardJumpIntervalChanged =
      previousOptions.forwardJumpInterval != options.forwardJumpInterval
    val backwardJumpIntervalChanged =
      previousOptions.backwardJumpInterval != options.backwardJumpInterval
    val capabilitiesChanged = previousOptions.capabilities != options.capabilities
    val remoteButtonLayoutChanged =
      !previousOptions.remoteButtonLayout.sameAs(options.remoteButtonLayout)
    val appKilledPlaybackBehaviorChanged =
      previousOptions.appKilledPlaybackBehavior != options.appKilledPlaybackBehavior

    val hasChanged =
      skipSilenceChanged ||
        progressUpdateEventIntervalChanged ||
        forwardJumpIntervalChanged ||
        backwardJumpIntervalChanged ||
        capabilitiesChanged ||
        remoteButtonLayoutChanged ||
        appKilledPlaybackBehaviorChanged

    if (skipSilenceChanged) {
      skipSilence = options.skipSilence
    }

    if (capabilitiesChanged) {
      // The favorite capability is the one favoriting switch; the browser hydrates row hearts from
      // it.
      browser?.browserManager?.setFavoriteEnabled(options.capabilities.favoriteEnabled)
      TrackFactory.favoriteEnabled = options.capabilities.favoriteEnabled
      if (previousOptions.capabilities.favoriteEnabled != options.capabilities.favoriteEnabled) {
        restampFavorites()
      }
    }

    if (progressUpdateEventIntervalChanged) {
      setProgressUpdateInterval(options.progressUpdateEventInterval)
    }

    // The jump intervals pick the button icons, so a changed interval rebuilds the layout.
    if (
      capabilitiesChanged ||
        remoteButtonLayoutChanged ||
        forwardJumpIntervalChanged ||
        backwardJumpIntervalChanged
    ) {
      val searchAvailable = browser?.browserManager?.config?.hasSearch ?: false
      mediaSessionCallback.updateMediaSession(
        mediaSession,
        options.capabilities,
        options.remoteButtonLayout,
        searchAvailable,
        options.forwardJumpInterval,
        options.backwardJumpInterval,
      )
    }

    if (hasChanged) {
      callbacks?.onOptionsChanged(options)
    }
  }

  fun setCallbacks(callbacks: Callbacks?) {
    this.callbacks = callbacks
  }

  fun getCallbacks(): Callbacks? {
    return this.callbacks
  }

  fun setMediaSession(mediaSession: MediaSession) {
    this.mediaSession = mediaSession
  }

  fun getOptions(): PlayerUpdateOptions {
    return options
  }

  fun getOnline(): Boolean {
    return networkMonitor.getOnline()
  }

  private fun validateIndex(index: Int) {
    if (index < 0 || index >= sessionPlayer.mediaItemCount) {
      throw IllegalArgumentException(
        "Track index $index is out of bounds (size: ${sessionPlayer.mediaItemCount})"
      )
    }
  }

  private fun validateInsertIndex(index: Int) {
    if (index < -1 || index > sessionPlayer.mediaItemCount) {
      throw IllegalArgumentException(
        "Insert index $index is out of bounds (size: ${sessionPlayer.mediaItemCount}, use -1 to append)"
      )
    }
  }

  fun getMediaSessionCallback(): MediaLibraryService.MediaLibrarySession.Callback {
    return mediaSessionCallback
  }

  fun notifyContentChanged(path: String) {
    mediaSessionCallback.notifyContentChanged(path)
  }

  fun invalidateAllContent() {
    mediaSessionCallback.invalidateAllContent()
  }

  /** From the connected media browser (e.g. Android Auto), when it gives one. */
  val artworkSizeHintPixels: Int?
    get() = mediaSessionCallback.artworkSizeHintPixels

  val isCurrentItemLive: Boolean
    get() = sessionPlayer.isCurrentMediaItemLive

  /**
   * [position] to set the queue at again, or [C.TIME_UNSET], where Media3 starts at the default
   * position: for a live item, for 0, which a live item also reports before it has loaded, and
   * after the end, so the track plays again.
   */
  internal val restorablePositionMs: Long
    get() =
      position.takeUnless {
        isCurrentItemLive || it == 0L || sessionPlayer.playbackState == MediaPlayer.STATE_ENDED
      } ?: C.TIME_UNSET

  // MARK: - Buffer Configuration

  /** Takes effect for future buffering; what is buffered stays. */
  fun updateBufferConfig(config: BufferConfig) {
    loadControl.updateBufferConfig(config)
  }

  fun getBufferConfig(): BufferConfig {
    return loadControl.getBufferConfig()
  }

  fun resetBufferConfig() {
    loadControl.resetToDefaults()
  }

  private companion object {
    var lastQueueVersion = 0
  }
}
