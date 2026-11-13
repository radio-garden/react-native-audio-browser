package com.audiobrowser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.Keep
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.browser.CallbackException
import com.audiobrowser.browser.ContentNotFoundException
import com.audiobrowser.browser.HttpStatusException
import com.audiobrowser.browser.NetworkException
import com.audiobrowser.browser.flattenedChildren
import com.audiobrowser.browser.handleTrackLoad
import com.audiobrowser.browser.resolveMediaUrl
import com.audiobrowser.extension.NumberExt.Companion.toSeconds
import com.audiobrowser.extension.indexOfTappedTrack
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.player.Player
import com.audiobrowser.util.BatteryOptimizationHelper
import com.audiobrowser.util.BatteryWarningStore
import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.OutputMonitor
import com.audiobrowser.util.OutputSwitcher
import com.audiobrowser.util.SystemVolumeMonitor
import com.audiobrowser.util.awaitNestedOrTimeout
import com.audiobrowser.util.awaitOrTimeout
import com.facebook.proguard.annotations.DoNotStrip
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.NitroModules
import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.BatteryOptimizationStatus
import com.margelo.nitro.audiobrowser.BatteryOptimizationStatusChangedEvent
import com.margelo.nitro.audiobrowser.BatteryWarningPendingChangedEvent
import com.margelo.nitro.audiobrowser.ChapterMetadata
import com.margelo.nitro.audiobrowser.EqualizerSettings
import com.margelo.nitro.audiobrowser.FavoriteChangedEvent
import com.margelo.nitro.audiobrowser.FormatNavigationErrorParams
import com.margelo.nitro.audiobrowser.FormattedNavigationError
import com.margelo.nitro.audiobrowser.Gate
import com.margelo.nitro.audiobrowser.GateDecision
import com.margelo.nitro.audiobrowser.GateEvent
import com.margelo.nitro.audiobrowser.HybridAudioBrowserSpec
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.NativeBrowserConfiguration
import com.margelo.nitro.audiobrowser.NativeGateRequest
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import com.margelo.nitro.audiobrowser.NativeSetupPlayerOptions
import com.margelo.nitro.audiobrowser.NativeUpdateOptions
import com.margelo.nitro.audiobrowser.NavigationError
import com.margelo.nitro.audiobrowser.NavigationErrorEvent
import com.margelo.nitro.audiobrowser.NavigationErrorType
import com.margelo.nitro.audiobrowser.NowPlayingMetadata
import com.margelo.nitro.audiobrowser.NowPlayingUpdate
import com.margelo.nitro.audiobrowser.Options
import com.margelo.nitro.audiobrowser.Output
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackErrorEvent
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackProgressUpdatedEvent
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent
import com.margelo.nitro.audiobrowser.PlaybackState
import com.margelo.nitro.audiobrowser.PlayingState
import com.margelo.nitro.audiobrowser.Progress
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemotePlayIdEvent
import com.margelo.nitro.audiobrowser.RemotePlaySearchEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent
import com.margelo.nitro.audiobrowser.RemoteSkipEvent
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.RepeatModeChangedEvent
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.SleepTimer
import com.margelo.nitro.audiobrowser.TimedMetadata
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TrackMetadata
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import com.margelo.nitro.core.NullType
import com.margelo.nitro.core.Promise
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

@Keep
@DoNotStrip
class AudioBrowser : HybridAudioBrowserSpec(), ServiceConnection {

  /**
   * Runs on an explicit JS `dispose()` and, through [disposeCurrent], on runtime teardown, which
   * Nitro itself never reports.
   *
   * The Service outlives this object, so everything registered in `init` or on connect is undone
   * here; left alone, the running [Player] calls into a dead JSI runtime and each reload strands a
   * ServiceConnection and lifecycle observer. The teardown is posted to main, where
   * [onServiceConnected] and [onServiceDisconnected] write the same state; dispose itself arrives
   * on the JS thread or the teardown callback's thread.
   */
  override fun dispose() {
    current.compareAndSet(this, null)
    systemVolumeMonitor.destroy()
    outputMonitor.destroy()
    // Only when still ours: on a reload the replacement may already own the slot.
    carConnectionTarget.compareAndSet(onCarConnectedChanged, null)

    dropStaleJSCallbacks()

    handler.post {
      connectedService?.let { service ->
        // Identity-guarded: both instances bind to the same singleton Service, so a late dispose
        // must not strip the wiring a replacement has already installed.
        if (service.player.getCallbacks() === callbacks) service.player.setCallbacks(null)
        if (service.player.browser === this) {
          service.player.browser = null
          // It wraps a JS callback from this runtime: left set, every track change throws into the
          // now-playing updater's fallback until the next setup().
          service.player.nowPlaying.formatter = null
        }
        if (service.onBatteryWarningPendingChanged === onBatteryWarningPending) {
          service.onBatteryWarningPendingChanged = null
        }
      }
      connectedService = null

      // Dropping the reference disconnects nothing: the controller stays in the session's
      // connected list, holding a binder and a listener.
      mediaBrowserFuture?.let { MediaBrowser.releaseFuture(it) }
      mediaBrowserFuture = null

      serviceBinding.unbind(this)

      ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
      // Inside this post: run from dispose() after the post, it would drop the teardown itself.
      handler.removeCallbacksAndMessages(null)
      mainScope.cancel()
    }
    super.dispose()
  }

  /**
   * Strips every JS function from the config, at every depth, and reverts [resolveGate]: anything
   * still holding this instance (an in-flight browse, the running player) can invoke them after the
   * runtime is gone. Those paths then degrade like a config that never had callbacks (static
   * layers, default error copy, fail-closed gate) instead of throwing into the error path. The
   * now-playing formatter is cleared in [dispose]. Mirrors iOS `dropStaleJSCallbacks`, where the
   * same invoke is fatal rather than a throw.
   */
  private fun dropStaleJSCallbacks() {
    _configuration = _configuration.strippingJSCallbacks()
    browserManager.config = buildConfig()
    resolveGate = defaultResolveGate
  }

  private val mainScope = MainScope()
  private var navigationJob: Job? = null
  private val handler = Handler(Looper.getMainLooper())
  private val context =
    NitroModules.applicationContext
      ?: throw IllegalStateException("NitroModules.applicationContext is null")

  // MARK: Browser state
  private var isConfigured = false
  private var _configuration =
    NativeBrowserConfiguration(
      path = null,
      request = null,
      requestResolver = null,
      browse = null,
      browseResolver = null,
      media = null,
      artwork = null,
      nowPlayingArtwork = null,
      routes = null,
      singleTrack = null,
      handleTrackLoad = null,
      androidControllerOfflineError = null,
      viewMoreTitle = null,
      carPlayLoadingTitle = null,
      resolveAlbumPath = null,
      formatNavigationError = null,
    )

  internal val browserManager =
    BrowserManager().apply {
      setOnPathChanged { path -> onPathChanged(path) }
      setOnContentChanged { content -> onContentChanged(content) }
      setOnTabsChanged { tabs -> onTabsChanged(tabs) }
      setOnArtworkRegistriesCleared { connectedService?.player?.browseArtworkRegistry?.clear() }
    }

  private val systemVolumeMonitor = SystemVolumeMonitor(context)
  private val outputMonitor = OutputMonitor(context)

  // MARK: Player state
  private var updateOptions: PlayerUpdateOptions = PlayerUpdateOptions()
  private var mediaBrowserFuture: ListenableFuture<MediaBrowser>? = null
  private var setupOptions = PlayerSetupOptions()
  // Written only on main (ServiceConnection callbacks, dispose); read on main and, in places,
  // straight from the JS thread, where it may be stale either way: a just-detached service, or
  // null while onServiceConnected is pending. Keep writes on main; no read-modify-write.
  @Volatile private var connectedService: Service? = null

  private val serviceBinding = ServiceBinding(context)

  // Held rather than inlined so [dispose] can tell its own wiring from a replacement's.
  private val onBatteryWarningPending: (Boolean) -> Unit = { pending ->
    post { onBatteryWarningPendingChanged(BatteryWarningPendingChangedEvent(pending)) }
  }
  // Every setupPlayer waiting for the service awaits it. Replaced on disconnect, after
  // cancelChildren has rejected its waiters, and on a dead binding, after failing them.
  private var connectionReady = CompletableDeferred<Unit>()

  // Staged by setup options or by setters called before the player exists; last write wins, and
  // [applyPendingPlayerState] consumes them.
  private var pendingPlayWhenReady: Boolean? = null
  private var pendingRepeatMode: RepeatMode? = null

  private fun post(block: () -> Unit) = handler.post(block)

  // MARK: Browser callbacks
  override var onPathChanged: (String) -> Unit = {}
  override var onContentChanged: (ResolvedTrack?) -> Unit = {}
  override var onTabsChanged: (Array<Track>) -> Unit = {}
  override var onNavigationError: (NavigationErrorEvent) -> Unit = {}
  override var onFormattedNavigationError: (FormattedNavigationError?) -> Unit = {}

  // MARK: Gate callbacks (native→JS; set from JS, native CALLS them)
  // Denies by default: it is reached only while a resolver is declared but none from a live
  // runtime is bound (before JS assigns one, or after [dispose] reverts it), and an active gate
  // must not serve content in that window.
  override var resolveGate: (request: NativeGateRequest) -> Promise<Promise<GateDecision>> =
    defaultResolveGate
  override var onGate: (event: GateEvent) -> Unit = {}

  // MARK: Player callbacks
  override var onPlaybackChanged: (data: Playback) -> Unit = {}
  override var onRemoteJumpBackward: (RemoteJumpBackwardEvent) -> Unit = {}
  override var onRemoteJumpForward: (RemoteJumpForwardEvent) -> Unit = {}
  override var onRemoteNext: () -> Unit = {}
  override var onRemotePause: () -> Unit = {}
  override var onChapterMetadata: (chapters: Array<ChapterMetadata>) -> Unit = {}
  override var onTrackMetadata: (metadata: TrackMetadata) -> Unit = {}
  override var onTimedMetadata: (metadata: TimedMetadata) -> Unit = {}
  override var onPlaybackActiveTrackChanged: (data: PlaybackActiveTrackChangedEvent) -> Unit = {}
  override var onPlaybackError: (data: PlaybackErrorEvent) -> Unit = {}
  override var onPlaybackPlayWhenReadyChanged: (data: PlaybackPlayWhenReadyChangedEvent) -> Unit =
    {}
  override var onPlaybackPlayingState: (data: PlayingState) -> Unit = {}
  override var onPlaybackProgressUpdated: (data: PlaybackProgressUpdatedEvent) -> Unit = {}
  override var onPlaybackInterval: () -> Unit = {}
  override var onPlaybackQueueEnded: (data: PlaybackQueueEndedEvent) -> Unit = {}
  override var onPlaybackQueueChanged: (queue: Array<Track>) -> Unit = {}
  override var onPlaybackRepeatModeChanged: (data: RepeatModeChangedEvent) -> Unit = {}
  override var onPlaybackShuffleModeChanged: (enabled: Boolean) -> Unit = {}
  override var onRemotePlay: (() -> Unit) = {}
  override var onRemotePlayId: (RemotePlayIdEvent) -> Unit = {}
  override var onRemotePlaySearch: (RemotePlaySearchEvent) -> Unit = {}
  override var onRemotePrevious: () -> Unit = {}
  override var onRemoteSeek: (RemoteSeekEvent) -> Unit = {}
  override var onRemoteSkip: (RemoteSkipEvent) -> Unit = {}
  override var onRemoteStop: () -> Unit = {}
  override var onOptionsChanged: (Options) -> Unit = {}
  override var onFavoriteChanged: (FavoriteChangedEvent) -> Unit = {}
  override var onNowPlayingChanged: (NowPlayingMetadata) -> Unit = {}
  override var onOnlineChanged: (Boolean) -> Unit = {}
  override var onEqualizerChanged: (EqualizerSettings) -> Unit = {}
  override var onSleepTimerChanged: (SleepTimer?) -> Unit = {}
  override var onBatteryWarningPendingChanged: (BatteryWarningPendingChangedEvent) -> Unit = {}
  override var onBatteryOptimizationStatusChanged: (BatteryOptimizationStatusChangedEvent) -> Unit =
    {}
  override var onSystemVolumeChanged: (Double) -> Unit = {}
  override var onOutputChanged: (Output) -> Unit = {}

  // MARK: Remote handlers
  override var handleRemoteJumpBackward: ((RemoteJumpBackwardEvent) -> Unit)? = null
  override var handleRemoteJumpForward: ((RemoteJumpForwardEvent) -> Unit)? = null
  override var handleRemoteNext: (() -> Unit)? = null
  override var handleRemotePause: (() -> Unit)? = null
  override var handleRemotePlay: (() -> Unit)? = null
  override var handleRemotePlayId: ((RemotePlayIdEvent) -> Unit)? = null
  override var handleRemotePlaySearch: ((RemotePlaySearchEvent) -> Unit)? = null
  override var handleRemotePrevious: (() -> Unit)? = null
  override var handleRemoteSeek: ((RemoteSeekEvent) -> Unit)? = null
  override var handleRemoteSkip: (() -> Unit)? = null
  override var handleRemoteStop: (() -> Unit)? = null

  private val lifecycleObserver =
    object : DefaultLifecycleObserver {
      override fun onStart(owner: LifecycleOwner) {
        checkBatteryStatusChange()
      }
    }

  init {
    current.set(this)

    launchInScope {
      try {
        Timber.d("Attempting to auto-bind to existing AudioBrowserService from AudioBrowser")
        val intent = Intent(context, Service::class.java)
        val bound = serviceBinding.bind(intent, this@AudioBrowser)
        Timber.d("Auto-bind result: $bound")

        if (!bound) {
          Timber.w("Failed to bind to AudioBrowserService - service may not be running")
        }
      } catch (e: Exception) {
        Timber.e(e, "Failed to auto-bind to AudioBrowserService during initialization")
      }
    }

    // LifecycleRegistry.addObserver asserts the main thread.
    handler.post { ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver) }

    systemVolumeMonitor.setOnVolumeChanged { volume -> post { onSystemVolumeChanged(volume) } }

    outputMonitor.setOnOutputChanged { output -> post { onOutputChanged(output) } }
    outputMonitor.start()

    startCarConnectionObserver(context, handler)
  }

  // ============================================================================
  // MARK: Browser Configuration
  // ============================================================================

  internal fun buildConfig(): BrowserConfig {
    return BrowserConfig(
      request = _configuration.request,
      requestResolver = _configuration.requestResolver,
      browse = _configuration.browse,
      browseResolver = _configuration.browseResolver,
      media = _configuration.media,
      artwork = _configuration.artwork,
      nowPlayingArtwork = _configuration.nowPlayingArtwork,
      routes = _configuration.routes,
      singleTrack = _configuration.singleTrack ?: false,
      androidControllerOfflineError = _configuration.androidControllerOfflineError ?: true,
      viewMoreTitle = _configuration.viewMoreTitle,
    )
  }

  /**
   * The request config for [originalUrl], or null when resolution fails. Blocks: it is called from
   * [com.audiobrowser.player.TransformingDataSource.open] on ExoPlayer's loading thread, never main
   * or JS.
   */
  fun getMediaRequestConfig(originalUrl: String, track: Track): MediaRequestConfig? {
    return try {
      runBlocking { browserManager.resolveMediaUrl(originalUrl, track) }
    } catch (e: Exception) {
      Timber.e(e, "Failed to transform media URL: $originalUrl")
      null
    }
  }

  private fun hasValidConfiguration(): Boolean {
    return _configuration.routes?.any { it.path != BrowserManager.SEARCH_ROUTE_PATH } == true
  }

  // Suspends because queryTabs() invokes JS callbacks, which run on the JS thread. Never call it
  // through runBlocking from a Nitro setter: that runs on the JS thread and deadlocks waiting for
  // callbacks that need it.
  private suspend fun getDefaultPath(): String? {
    return try {
      browserManager.config = buildConfig()
      val tabs = browserManager.queryTabs()
      if (tabs.isNotEmpty()) {
        Timber.d("Using first tab as default path: ${tabs[0].path}")
        tabs[0].path
      } else {
        Timber.d("Using root path as default: /")
        "/"
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Timber.e(e, "Failed to get default path, falling back to /")
      "/"
    }
  }

  // ============================================================================
  // MARK: Browser Properties
  // ============================================================================

  override var path: String?
    get() = browserManager.getPath()
    set(value) {
      if (hasValidConfiguration()) {
        browserManager.config = buildConfig()
        clearNavigationError()

        navigationJob?.cancel()

        navigationJob =
          mainScope.launch {
            val path = value ?: getDefaultPath() ?: return@launch
            try {
              browserManager.navigate(path)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              handleBrowserException(e, path, "setting path: $path")
            }
          }
      }
    }

  override var tabs: Array<Track>?
    get() = browserManager.getTabs()
    set(value) {}

  override var configuration: NativeBrowserConfiguration
    get() = _configuration
    set(value) {
      _configuration = value
      browserManager.config = buildConfig()
      browserManager.clearContentCache()

      // Reaches MediaSession, which asserts the main thread, from this JS-thread setter. Launched,
      // not blocked: what it triggers can wait on JS callbacks, which cannot run while the JS
      // thread waits. Queued on mainScope ahead of navigationJob, so the two stay ordered.
      launchInScope {
        // Here, not in the setter: onServiceConnected reads it on main, and must not see it before
        // browserManager.config is set.
        isConfigured = true
        connectedService?.player?.notifyBrowserConfigurationReady()
      }

      clearNavigationError()

      navigationJob?.cancel()

      navigationJob =
        mainScope.launch {
          val path = value.path ?: getDefaultPath() ?: return@launch
          try {
            browserManager.navigate(path)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            handleBrowserException(e, path, "setting configuration path: $path")
          }
        }
    }

  private var navigationError: NavigationError? = null
  private var formattedNavigationError: FormattedNavigationError? = null

  override fun getNavigationError(): NavigationError? = navigationError

  override fun getFormattedNavigationError(): FormattedNavigationError? = formattedNavigationError

  /**
   * The display form of [error] at [path]: the app's `formatNavigationError` when configured, else
   * the built-in default. Shared with the Android Auto empty tile so in-app and in-car copy cannot
   * drift (ADR 0001).
   */
  internal suspend fun resolveFormattedError(
    error: NavigationError,
    path: String,
  ): FormattedNavigationError {
    val default = defaultFormattedError(error)
    val formatter = _configuration.formatNavigationError ?: return default
    return formattedOrDefault(default) {
      formatter(
          FormatNavigationErrorParams(
            error,
            default,
            path,
            BrowserPathHelper.navigationErrorKind(path),
          )
        )
        .awaitOrTimeout()
    }
  }

  private fun setNavigationError(navError: NavigationError, path: String) {
    navigationError = navError
    onNavigationError(NavigationErrorEvent(navigationError))

    // Without a formatter the field and event land in this turn, so a same-turn
    // getFormattedNavigationError or clearNavigationError cannot race a launch yet to run.
    if (_configuration.formatNavigationError == null) {
      val default = defaultFormattedError(navError)
      formattedNavigationError = default
      onFormattedNavigationError(default)
      return
    }

    mainScope.launch {
      val formatted = resolveFormattedError(navError, path)
      formattedNavigationError = formatted
      onFormattedNavigationError(formatted)
    }
  }

  private fun handleBrowserException(e: Exception, path: String, logContext: String) {
    val navError = navigationErrorFor(e)
    Timber.e(e, "${navError.code} $logContext")
    setNavigationError(navError, path)
  }

  private fun clearNavigationError() {
    if (navigationError != null || formattedNavigationError != null) {
      navigationError = null
      formattedNavigationError = null
      onNavigationError(NavigationErrorEvent(null))
      onFormattedNavigationError(null)
    }
  }

  // ============================================================================
  // MARK: Browser Navigation Methods
  // ============================================================================

  override fun navigatePath(path: String) {
    clearNavigationError()

    navigationJob?.cancel()

    navigationJob =
      mainScope.launch {
        try {
          Timber.d("Navigating to path: $path")
          browserManager.navigate(path)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          handleBrowserException(e, path, "navigating to path: $path")
        }
      }
  }

  override fun navigateTrack(track: Track) {
    // A disabled track never plays, whichever surface delivered it (mirrors iOS
    // TrackSelector.select). Refused before any state changes: an inert tap must not clear an
    // unrelated navigation error or cancel an in-flight navigation.
    if (track.disabled == true) {
      Timber.d("Ignoring selection of disabled track: ${track.title}")
      return
    }

    clearNavigationError()

    navigationJob?.cancel()

    val path = track.path
    navigationJob =
      mainScope.launch {
        try {
          when {
            path != null && BrowserPathHelper.isContextual(path) -> {
              Timber.d("Navigating to contextual track path: $path")

              val parentPath = BrowserPathHelper.stripTrackId(path)
              val trackId = BrowserPathHelper.extractTrackId(path)

              if (trackId != null && parentPath == player.queueSourcePath) {
                val index = player.tracks.indexOfTappedTrack(path, trackId)
                if (index >= 0) {
                  Timber.d("Queue already from $parentPath, skipping to index $index")
                  handleTrackLoad(
                    _configuration.handleTrackLoad,
                    track,
                    player.tracks,
                    index.toDouble(),
                    startPosition = null,
                    intercepted = {},
                    defaultBehavior = {
                      player.skipTo(index)
                      player.play()
                    },
                  )
                  return@launch
                }
              }

              val expanded = browserManager.expandQueueFromContextualPath(path)

              if (expanded != null) {
                val (tracks, startIndex) = expanded
                Timber.d(
                  "Loading expanded queue: ${tracks.size} tracks, starting at index $startIndex"
                )
                handleTrackLoad(
                  _configuration.handleTrackLoad,
                  track,
                  tracks,
                  startIndex.toDouble(),
                  startPosition = null,
                  intercepted = {},
                  defaultBehavior = {
                    // The Player itself: this class's overrides block on main, where this runs.
                    player.setQueue(tracks, startIndex, sourcePath = parentPath)
                    player.play()
                  },
                )
                return@launch
              } else {
                Timber.w("Queue expansion failed, loading single track")
                handleTrackLoad(
                  _configuration.handleTrackLoad,
                  track,
                  arrayOf(track),
                  0.0,
                  startPosition = null,
                  intercepted = {},
                  defaultBehavior = { player.load(track) },
                )
              }
            }
            path != null -> {
              Timber.d("Navigating to browsable track: $path")
              browserManager.navigate(path)
            }
            track.src != null -> {
              Timber.d("Loading playable track into player: ${track.title}")
              handleTrackLoad(
                _configuration.handleTrackLoad,
                track,
                arrayOf(track),
                0.0,
                startPosition = null,
                intercepted = {},
                defaultBehavior = { player.load(track) },
              )
            }
            else -> {
              throw IllegalArgumentException("Track must have either a 'path' or an 'src' property")
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          handleBrowserException(e, path ?: track.src ?: "", "navigating to track: ${track.title}")
        }
      }
  }

  override fun search(query: String): Promise<Array<Track>> {
    return promise {
      Timber.d("Searching for: $query")
      // Rejects with the error a browse would raise but leaves the navigation error unset: a failed
      // search must not replace the displayed page's state (mirrors iOS HybridAudioBrowser.search).
      val searchResults =
        try {
          browserManager.search(query)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Timber.e(e, "Search failed for query: $query")
          throw NavigationErrorException(navigationErrorFor(e))
        }
      // Not `children`: search builds one untitled section (ADR 0010), so `children` is null.
      searchResults.flattenedChildren?.toTypedArray() ?: emptyArray()
    }
  }

  override fun getContent(): ResolvedTrack? {
    return browserManager.getContent()
  }

  override fun notifyContentChanged(path: String) {
    Timber.d("Notifying content changed for path: $path")

    browserManager.invalidateContentCache(path)

    // MediaSession asserts the main thread; this override runs on the JS thread.
    launchInScope { connectedService?.player?.notifyContentChanged(path) }

    if (browserManager.getPath() == path) {
      mainScope.launch { browserManager.refresh() }
    }
  }

  override fun invalidateAllContent() {
    Timber.d("Invalidating all content")

    browserManager.clearContentCache()

    // MediaSession asserts the main thread; this override runs on the JS thread.
    launchInScope { connectedService?.player?.invalidateAllContent() }

    mainScope.launch {
      runCatching { browserManager.queryTabs() }
        .onFailure { Timber.e(it, "Re-querying tabs failed") }
    }
    mainScope.launch { browserManager.refresh() }
  }

  override fun setFavorites(favorites: Array<String>) {
    browserManager.setFavorites(favorites.toList())
  }

  // MARK: Gate

  /** [chrome] is non-null whenever [gated] is true: the serve sites force-unwrap it. */
  data class GateOutcome(val gated: Boolean, val chrome: Gate?)

  private val builtInGate = Gate("Unavailable", null)

  // One volatile reference, so [gateDecision] reads all three fields at once: separate volatiles
  // could tear against [clearGate] into an active gate with no chrome. Written on the JS thread,
  // read on the Media3 application thread.
  private data class GateState(val active: Boolean, val chrome: Gate?, val hasResolver: Boolean)

  @Volatile private var gateState = GateState(active = false, chrome = null, hasResolver = false)

  override fun setGate(gate: Gate?, hasResolver: Boolean) {
    gateState = GateState(active = true, chrome = gate, hasResolver = hasResolver)
    // Re-queries every subscribed parent so a connected controller swaps its lists for the gate
    // tile without reconnecting; the content cache stays warm for when the gate clears.
    // MediaSession asserts the main thread; this override runs on the JS thread.
    launchInScope { connectedService?.player?.invalidateAllContent() }
  }

  override fun clearGate() {
    if (!gateState.active) return
    gateState = GateState(active = false, chrome = null, hasResolver = false)
    // MediaSession asserts the main thread; this override runs on the JS thread.
    launchInScope { connectedService?.player?.invalidateAllContent() }
  }

  /**
   * Whether [request] is gated, and with which chrome. No active gate allows; a gate without a
   * resolver gates with its stored chrome and never asks JS. A resolver that throws, rejects or
   * times out fails closed, gating rather than serving content; only a successful `gated: false`
   * allows. Chrome falls back from the resolver's to the stored one to [builtInGate]. A caller
   * cancelled while it waits gets the cancellation.
   */
  suspend fun gateDecision(request: NativeGateRequest): GateOutcome {
    val s = gateState
    if (!s.active) return GateOutcome(false, null)
    if (!s.hasResolver) return GateOutcome(true, s.chrome ?: builtInGate)
    val result = runCatching { resolveGate(request).awaitNestedOrTimeout() }
    currentCoroutineContext().ensureActive()
    val decision = result.getOrNull() ?: return GateOutcome(true, s.chrome ?: builtInGate)
    if (!decision.gated) return GateOutcome(false, null)
    return GateOutcome(true, decision.gate ?: s.chrome ?: builtInGate)
  }

  // MARK: Car connection (Android Auto / Android Automotive)

  override fun isCarConnected(): Boolean = carConnected

  override var onCarConnectedChanged: (Boolean) -> Unit = {}
    set(value) {
      field = value
      carConnectionTarget.set(value)
      // The car may have connected before this runtime subscribed (mirrors iOS).
      value(carConnected)
    }

  // ============================================================================
  // MARK: Player Setup and Options
  // ============================================================================

  override fun setupPlayer(options: NativeSetupPlayerOptions): Promise<Unit> {
    return promise {
      setupOptions.update(options)
      // Staged to apply together with the engine below, or at service connect. Last write wins
      // against the imperative setters.
      options.options?.let { updateOptions.updateFromBridge(it) }
      options.repeatMode?.let { pendingRepeatMode = it }
      options.playWhenReady?.let { pendingPlayWhenReady = it }

      connectedService?.let {
        it.player.applyOptions(updateOptions)
        it.player.setup(setupOptions)
        applyPendingPlayerState(it.player)
        return@promise
      }

      if (!serviceBinding.bind(Intent(context, Service::class.java), this@AudioBrowser)) {
        throw RuntimeException("Failed to bind to AudioBrowserService")
      }
      connectionReady.await()
    }
  }

  override fun updateOptions(options: NativeUpdateOptions) {
    updateOptions.updateFromBridge(options)
    // Blocks rather than launches: updateFromBridge mutates the holder in place on the JS thread
    // and applyOptions reads it on main (Media3 asserts main), so blocking keeps the two ordered.
    connectedService?.let { service ->
      runBlockingOnMain { service.player.applyOptions(updateOptions) }
    }
  }

  override fun getOptions(): Options {
    return updateOptions.toNitro()
  }

  // ============================================================================
  // MARK: Player Control Methods
  // ============================================================================

  override fun load(track: Track) {
    // Throws without a player, as on iOS: a track asked for and silently dropped hides a mistake.
    // Blocks rather than launching, so a track the player refuses fails this call.
    runBlockingOnMain { player.load(track) }
  }

  override fun reset() =
    commandPlayer("reset") { player ->
      player.stop()
      player.clear()
      // clear() while idle emits no engine event, so the state would otherwise stay STOPPED.
      player.setPlaybackState(PlaybackState.NONE)
    }

  override fun play() = commandPlayer("play") { it.play() }

  override fun pause() = commandPlayer("pause") { it.pause() }

  override fun togglePlayback() = commandPlayer("togglePlayback") { it.togglePlayback() }

  override fun stop() = commandPlayer("stop") { it.stop() }

  override fun setPlayWhenReady(playWhenReady: Boolean) = runBlockingOnMain {
    connectedService?.player?.let { it.playWhenReady = playWhenReady }
      ?: run { pendingPlayWhenReady = playWhenReady }
  }

  override fun getPlayWhenReady(): Boolean = runBlockingOnMain {
    connectedService?.player?.playWhenReady ?: pendingPlayWhenReady ?: false
  }

  override fun seekTo(position: Double) =
    commandPlayer("seekTo") { player ->
      player.seekTo((position * 1000).toLong(), TimeUnit.MILLISECONDS)
    }

  override fun seekBy(offset: Double) =
    commandPlayer("seekBy") { player ->
      player.seekBy((offset * 1000).toLong(), TimeUnit.MILLISECONDS)
    }

  override fun seekToLiveEdge() = commandPlayer("seekToLiveEdge") { it.seekToLiveEdge() }

  override fun setVolume(level: Double) = commandPlayer("setVolume") { it.volume = level.toFloat() }

  override fun getVolume(): Double = readPlayer(1.0) { it.volume.toDouble() }

  override fun setRate(rate: Double) =
    commandPlayer("setRate") { it.playbackSpeed = rate.toFloat() }

  override fun getRate(): Double = readPlayer(1.0) { it.playbackSpeed.toDouble() }

  override fun getProgress(): Progress =
    readPlayer(Progress(duration = 0.0, position = 0.0, buffered = 0.0)) { player ->
      Progress(
        duration = player.duration.toSeconds(),
        position = player.position.toSeconds(),
        buffered = player.bufferedPosition.toSeconds(),
      )
    }

  override fun getPlayback(): Playback =
    readPlayer(Playback(PlaybackState.NONE, null)) { it.getPlayback() }

  override fun getPlayingState(): PlayingState =
    readPlayer(PlayingState(playing = false, buffering = false)) { it.getPlayingState() }

  override fun getRepeatMode(): RepeatMode = runBlockingOnMain {
    connectedService?.player?.repeatMode ?: pendingRepeatMode ?: RepeatMode.OFF
  }

  override fun setRepeatMode(mode: RepeatMode) = runBlockingOnMain {
    connectedService?.player?.let { it.repeatMode = mode } ?: run { pendingRepeatMode = mode }
  }

  override fun getShuffleEnabled(): Boolean = readPlayer(false) { it.shuffleMode }

  override fun setShuffleEnabled(enabled: Boolean) =
    commandPlayer("setShuffleEnabled") { player -> player.shuffleMode = enabled }

  override fun setPlaybackIntervalEnabled(enabled: Boolean) =
    commandPlayer("setPlaybackIntervalEnabled") { player ->
      player.setPlaybackIntervalEnabled(enabled)
    }

  override fun getPlaybackError(): PlaybackError? = readPlayer(null) { it.playbackError }

  override fun retry() = commandPlayer("retry") { it.prepare() }

  // ============================================================================
  // MARK: Queue Management
  // ============================================================================

  override fun add(tracks: Array<Track>, insertBeforeIndex: Double?) =
    commandPlayer("add") { player ->
      val inputIndex = insertBeforeIndex?.toInt() ?: -1
      player.add(tracks, inputIndex)
    }

  override fun move(fromIndex: Double, toIndex: Double) =
    commandPlayer("move") { player -> player.move(fromIndex.toInt(), toIndex.toInt()) }

  override fun remove(indexes: DoubleArray) =
    commandPlayer("remove") { player ->
      val indexList = indexes.map { it.toInt() }
      player.remove(indexList)
    }

  override fun removeUpcomingTracks() =
    commandPlayer("removeUpcomingTracks") { it.removeUpcomingTracks() }

  override fun skip(index: Double, initialPosition: Double?) =
    commandPlayer("skip") { player ->
      player.skipTo(index.toInt())

      if (initialPosition != null && initialPosition >= 0) {
        player.seekTo((initialPosition * 1000).toLong(), TimeUnit.MILLISECONDS)
      }
    }

  override fun skipToNext(initialPosition: Double?) =
    commandPlayer("skipToNext") { player ->
      player.next()

      if (initialPosition != null && initialPosition >= 0) {
        player.seekTo((initialPosition * 1000).toLong(), TimeUnit.MILLISECONDS)
      }
    }

  override fun skipToPrevious(initialPosition: Double?) =
    commandPlayer("skipToPrevious") { player ->
      player.previous()

      if (initialPosition != null && initialPosition >= 0) {
        player.seekTo((initialPosition * 1000).toLong(), TimeUnit.MILLISECONDS)
      }
    }

  override fun setQueue(tracks: Array<Track>, startIndex: Double?, startPosition: Double?) =
    commandPlayer("setQueue") { player ->
      player.setQueue(tracks, startIndex?.toInt() ?: 0, ((startPosition ?: 0.0) * 1000).toLong())
    }

  override fun setActiveTrackFavorited(favorited: Boolean): Unit =
    commandPlayer("setActiveTrackFavorited") { player ->
      // The applied result serves MediaSession; the JS API is fire-and-forget.
      player.setActiveTrackFavorited(favorited)
    }

  override fun toggleActiveTrackFavorited(): Unit =
    commandPlayer("toggleActiveTrackFavorited") { it.toggleActiveTrackFavorited() }

  override fun getQueue(): Array<Track> = readPlayer(emptyArray()) { it.tracks }

  override fun getQueueVersion(): Double = readPlayer(-1.0) { it.queueVersion.toDouble() }

  override fun getTrack(index: Double): Track? =
    readPlayer(null) { player ->
      try {
        player.getTrack(index.toInt())
      } catch (e: IllegalArgumentException) {
        null
      }
    }

  override fun getActiveTrackIndex(): Double? = readPlayer(null) { it.currentIndex?.toDouble() }

  override fun getActiveTrack(): Track? = readPlayer(null) { it.currentTrack }

  // ============================================================================
  // MARK: Now Playing Metadata
  // ============================================================================

  override fun updateNowPlaying(update: NowPlayingUpdate?) =
    commandPlayer("updateNowPlaying") { player -> player.nowPlaying.updateNowPlaying(update) }

  override fun flashNowPlaying(update: NowPlayingUpdate, durationMs: Double) =
    commandPlayer("flashNowPlaying") { player ->
      player.nowPlaying.flashNowPlaying(update, durationMs)
    }

  override fun clearNowPlayingFlash() =
    commandPlayer("clearNowPlayingFlash") { player -> player.nowPlaying.clearNowPlayingFlash() }

  override fun getNowPlaying(): NowPlayingMetadata? =
    readPlayer(null) { player -> player.nowPlaying.getNowPlaying() }

  override fun getTimedMetadata(): TimedMetadata? =
    readPlayer(null) { player -> player.nowPlaying.timedMetadata }

  // ============================================================================
  // MARK: Network Connectivity
  // ============================================================================

  override fun getOnline(): Boolean = readPlayer(true) { it.getOnline() }

  // ============================================================================
  // MARK: System Volume
  // ============================================================================

  override fun getSystemVolume(): Double = systemVolumeMonitor.getVolume()

  override fun setSystemVolume(volume: Double) = systemVolumeMonitor.setVolume(volume)

  // ============================================================================
  // MARK: External Audio Output
  // ============================================================================

  override fun getOutput(): Output? = outputMonitor.current

  override fun supportsOutputSwitcher(): Boolean = OutputSwitcher.isSupported()

  override fun openOutputPicker() {
    OutputSwitcher.open(context)
  }

  // ============================================================================
  // MARK: Equalizer (Android only)
  // ============================================================================

  override fun getEqualizerSettings(): EqualizerSettings? =
    readPlayer(null) { player -> player.equalizer.getSettings() }

  override fun setEqualizerEnabled(enabled: Boolean) =
    commandPlayer("setEqualizerEnabled") { player -> player.equalizer.setEnabled(enabled) }

  override fun setEqualizerPreset(preset: String) =
    commandPlayer("setEqualizerPreset") { player -> player.equalizer.setPreset(preset) }

  override fun setEqualizerLevels(levels: DoubleArray) =
    commandPlayer("setEqualizerLevels") { player -> player.equalizer.setLevels(levels) }

  // ============================================================================
  // MARK: Sleep Timer
  // ============================================================================

  override fun getSleepTimer(): SleepTimer =
    readPlayer(SleepTimer.create(NullType.NULL)) { it.sleepTimer.get() }

  override fun setSleepTimer(seconds: Double, fadeDuration: Double?) =
    commandPlayer("setSleepTimer") { player -> player.sleepTimer.setAfter(seconds, fadeDuration) }

  override fun setSleepTimerToEndOfTrack() =
    commandPlayer("setSleepTimerToEndOfTrack") { it.sleepTimer.setEndOfTrack() }

  override fun clearSleepTimer(): Boolean = readPlayer(false) { it.sleepTimer.clear() }

  // ============================================================================
  // MARK: Battery Optimization (Android only)
  // ============================================================================

  private var lastKnownBatteryStatus: BatteryOptimizationHelper.Status? = null

  override fun getBatteryWarningPending(): Boolean {
    val rawPending = BatteryWarningStore.isWarningPending(context)
    val status = BatteryOptimizationHelper.getStatus(context)

    return if (rawPending && status == BatteryOptimizationHelper.Status.UNRESTRICTED) {
      BatteryWarningStore.clearWarning(context)
      post { onBatteryWarningPendingChanged(BatteryWarningPendingChangedEvent(false)) }
      false
    } else {
      rawPending
    }
  }

  override fun getBatteryOptimizationStatus(): BatteryOptimizationStatus {
    return BatteryOptimizationHelper.getStatus(context).toNitro()
  }

  override fun dismissBatteryWarning() {
    BatteryWarningStore.clearWarning(context)
    post { onBatteryWarningPendingChanged(BatteryWarningPendingChangedEvent(false)) }
  }

  override fun openBatterySettings() {
    BatteryOptimizationHelper.openSettings(context)
  }

  private fun checkBatteryStatusChange() {
    val currentStatus = BatteryOptimizationHelper.getStatus(context)
    if (lastKnownBatteryStatus != null && lastKnownBatteryStatus != currentStatus) {
      post {
        onBatteryOptimizationStatusChanged(
          BatteryOptimizationStatusChangedEvent(currentStatus.toNitro())
        )
      }

      if (
        currentStatus == BatteryOptimizationHelper.Status.UNRESTRICTED &&
          BatteryWarningStore.isWarningPending(context)
      ) {
        BatteryWarningStore.clearWarning(context)
        post { onBatteryWarningPendingChanged(BatteryWarningPendingChangedEvent(false)) }
      }
    }
    lastKnownBatteryStatus = currentStatus
  }

  private fun BatteryOptimizationHelper.Status.toNitro(): BatteryOptimizationStatus {
    return when (this) {
      BatteryOptimizationHelper.Status.UNRESTRICTED -> BatteryOptimizationStatus.UNRESTRICTED
      BatteryOptimizationHelper.Status.OPTIMIZED -> BatteryOptimizationStatus.OPTIMIZED
      BatteryOptimizationHelper.Status.RESTRICTED -> BatteryOptimizationStatus.RESTRICTED
    }
  }

  // ============================================================================
  // MARK: Service Connection
  // ============================================================================

  override fun onServiceConnected(name: ComponentName, serviceBinder: IBinder) {
    launchInScope {
      connectedService =
        (serviceBinder as Service.LocalBinder).service.apply {
          player.setCallbacks(callbacks)
          player.applyOptions(updateOptions)
          player.setup(setupOptions)
          applyPendingPlayerState(player)
          player.observeNetworkConnectivity(mainScope)
          player.browser = this@AudioBrowser

          // The configuration setter's notify finds no service when it runs before connect.
          if (isConfigured) player.notifyBrowserConfigurationReady()
        }

      connectedService?.onBatteryWarningPendingChanged = onBatteryWarningPending

      val sessionToken = SessionToken(context, ComponentName(context, Service::class.java))
      mediaBrowserFuture = MediaBrowser.Builder(context, sessionToken).buildAsync()

      connectionReady.complete(Unit)
    }
  }

  // Consumes what it applies, so a later re-setup does not replay stale state.
  private fun applyPendingPlayerState(player: Player) {
    pendingRepeatMode?.let {
      player.repeatMode = it
      pendingRepeatMode = null
    }
    pendingPlayWhenReady?.let {
      player.applySetupPlayWhenReady(it)
      pendingPlayWhenReady = null
    }
  }

  override fun onServiceDisconnected(name: ComponentName) {
    mainScope.coroutineContext.cancelChildren()
    mediaBrowserFuture = null
    connectedService = null
    connectionReady = CompletableDeferred()
    Timber.d("AudioBrowser.onServiceDisconnected()")
  }

  // Android never reconnects a dead binding: release it so the next setupPlayer binds afresh.
  override fun onBindingDied(name: ComponentName) = dropDeadBinding()

  override fun onNullBinding(name: ComponentName) = dropDeadBinding()

  private fun dropDeadBinding() {
    serviceBinding.unbind(this)
    connectionReady.completeExceptionally(IllegalStateException("AudioBrowserService binding died"))
    connectionReady = CompletableDeferred()
  }

  // ============================================================================
  // MARK: Internal Helpers
  // ============================================================================

  private fun <T> promise(run: suspend () -> T): Promise<T> {
    val promise = Promise<T>()
    mainScope.launchSettled({ it.fold(promise::resolve, promise::reject) }, run)
    return promise
  }

  private fun launchInScope(block: suspend () -> Unit) {
    mainScope.launch { block() }
  }

  // For calls arriving on the JS thread: called on main it waits on itself and deadlocks.
  private fun <T> runBlockingOnMain(block: suspend () -> T): T {
    return runBlocking(mainScope.coroutineContext) { block() }
  }

  private val service: Service
    get() = connectedService ?: throw Exception("Player not initialized")

  private val connectedPlayer: Player?
    get() = connectedService?.player

  /** What [read] gets from the player, or without one [fallback], the answer iOS gives then. */
  private fun <T> readPlayer(fallback: T, read: suspend (Player) -> T): T = runBlockingOnMain {
    val player = connectedPlayer ?: return@runBlockingOnMain fallback
    read(player)
  }

  /** Runs [command] on the player; without one it is dropped with a warning, as iOS drops it. */
  private fun commandPlayer(name: String, command: suspend (Player) -> Unit) = runBlockingOnMain {
    val player = connectedPlayer
    if (player == null) {
      Timber.w("$name before the player is set up, or while its service restarts: ignored")
    } else {
      command(player)
    }
  }

  internal val player
    get() = service.player

  // ============================================================================
  // MARK: Player Callbacks
  // ============================================================================

  val callbacks =
    object : Callbacks {
      override fun onPlaybackChanged(playback: Playback) {
        post { this@AudioBrowser.onPlaybackChanged(playback) }
      }

      override fun onPlaybackActiveTrackChanged(event: PlaybackActiveTrackChangedEvent) {
        post { this@AudioBrowser.onPlaybackActiveTrackChanged(event) }
      }

      override fun onPlaybackProgressUpdated(event: PlaybackProgressUpdatedEvent) {
        post { this@AudioBrowser.onPlaybackProgressUpdated(event) }
      }

      override fun onPlaybackInterval() {
        post { this@AudioBrowser.onPlaybackInterval() }
      }

      override fun onPlaybackPlayWhenReadyChanged(event: PlaybackPlayWhenReadyChangedEvent) {
        post { this@AudioBrowser.onPlaybackPlayWhenReadyChanged(event) }
      }

      override fun onPlaybackPlayingState(event: PlayingState) {
        post {
          Timber.d(
            "AudioBrowser forwarding PlayingState to JS: playing=${event.playing}, buffering=${event.buffering}"
          )
          this@AudioBrowser.onPlaybackPlayingState(event)
        }
      }

      override fun onPlaybackQueueEnded(event: PlaybackQueueEndedEvent) {
        post { this@AudioBrowser.onPlaybackQueueEnded(event) }
      }

      override fun onPlaybackQueueChanged(queue: Array<Track>) {
        post { this@AudioBrowser.onPlaybackQueueChanged(queue) }
      }

      override fun onPlaybackRepeatModeChanged(event: RepeatMode) {
        post { this@AudioBrowser.onPlaybackRepeatModeChanged(RepeatModeChangedEvent(event)) }
      }

      override fun onPlaybackShuffleModeChanged(enabled: Boolean) {
        post { this@AudioBrowser.onPlaybackShuffleModeChanged(enabled) }
      }

      override fun onPlaybackError(error: PlaybackError?) {
        post { this@AudioBrowser.onPlaybackError(PlaybackErrorEvent(error)) }
      }

      override fun onTrackMetadata(metadata: TrackMetadata) {
        post { this@AudioBrowser.onTrackMetadata(metadata) }
      }

      override fun onChapterMetadata(chapters: List<ChapterMetadata>) {
        post { this@AudioBrowser.onChapterMetadata(chapters.toTypedArray()) }
      }

      override fun onTimedMetadata(metadata: TimedMetadata) {
        post { this@AudioBrowser.onTimedMetadata(metadata) }
      }

      override fun handleRemotePlay(): Boolean {
        val handled =
          this@AudioBrowser.handleRemotePlay?.let {
            it.invoke()
            true
          } ?: false

        post { this@AudioBrowser.onRemotePlay() }

        return handled
      }

      override fun handleRemotePause(): Boolean {
        val handled =
          this@AudioBrowser.handleRemotePause?.let {
            it.invoke()
            true
          } ?: false

        post { this@AudioBrowser.onRemotePause() }

        return handled
      }

      override fun handleRemoteStop(): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteStop?.let {
            it.invoke()
            true
          } ?: false

        post { this@AudioBrowser.onRemoteStop() }

        return handled
      }

      override fun handleRemoteNext(): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteNext?.let {
            it.invoke()
            true
          } ?: false

        post { this@AudioBrowser.onRemoteNext() }

        return handled
      }

      override fun handleRemotePrevious(): Boolean {
        val handled =
          this@AudioBrowser.handleRemotePrevious?.let {
            it.invoke()
            true
          } ?: false

        post { this@AudioBrowser.onRemotePrevious() }

        return handled
      }

      override fun handleRemoteJumpForward(event: RemoteJumpForwardEvent): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteJumpForward?.let {
            it.invoke(event)
            true
          } ?: false

        post { this@AudioBrowser.onRemoteJumpForward(event) }

        return handled
      }

      override fun handleRemoteJumpBackward(event: RemoteJumpBackwardEvent): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteJumpBackward?.let {
            it.invoke(event)
            true
          } ?: false

        post { this@AudioBrowser.onRemoteJumpBackward(event) }

        return handled
      }

      override fun handleRemoteSeek(event: RemoteSeekEvent): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteSeek?.let {
            it.invoke(event)
            true
          } ?: false

        post { this@AudioBrowser.onRemoteSeek(event) }

        return handled
      }

      override fun onOptionsChanged(options: PlayerUpdateOptions) {
        post { this@AudioBrowser.onOptionsChanged(options.toNitro()) }
      }

      override fun onFavoriteChanged(event: FavoriteChangedEvent) {
        post { this@AudioBrowser.onFavoriteChanged(event) }
      }

      override fun onNowPlayingChanged(metadata: NowPlayingMetadata) {
        post { this@AudioBrowser.onNowPlayingChanged(metadata) }
      }

      override fun onOnlineChanged(online: Boolean) {
        post { this@AudioBrowser.onOnlineChanged(online) }
      }

      override fun onEqualizerChanged(settings: EqualizerSettings) {
        post { this@AudioBrowser.onEqualizerChanged(settings) }
      }

      override fun onSleepTimerChanged(timer: SleepTimer?) {
        post { this@AudioBrowser.onSleepTimerChanged(timer) }
      }
    }

  companion object {
    // Process-wide, from androidx.car.app CarConnection, observed once per process: instances are
    // re-created on each JS reload, and a per-instance observeForever would leak each one. The
    // latest instance's callback is the single target (mirrors iOS).
    @Volatile private var carConnected = false
    // Compare-and-set, not @Volatile: a disposing instance clears the slot only if it still owns
    // it, and a replacement may be claiming it at the same moment.
    private val carConnectionTarget = AtomicReference<((Boolean) -> Unit)?>(null)
    private var carConnectionObserverStarted = false

    // The instance the current JS runtime uses: Nitro keeps no registry for teardown to find it in.
    private val current = AtomicReference<AudioBrowser?>(null)

    /** Called by [AudioBrowserLifecycleModule] when the React instance is torn down. */
    internal fun disposeCurrent() {
      current.getAndSet(null)?.dispose()
    }

    private val defaultResolveGate: (NativeGateRequest) -> Promise<Promise<GateDecision>> = {
      Promise.resolved(Promise.resolved(GateDecision(gated = true, gate = null)))
    }

    private fun startCarConnectionObserver(context: Context, handler: Handler) {
      // LiveData observation is main-thread only, which also keeps the start guard on one thread.
      handler.post {
        if (carConnectionObserverStarted) return@post
        carConnectionObserverStarted = true
        CarConnection(context.applicationContext).type.observeForever { type ->
          val connected = type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
          if (connected == carConnected) return@observeForever
          carConnected = connected
          carConnectionTarget.get()?.invoke(connected)
        }
      }
    }
  }
}

// MARK: Deep JS-callback stripping (used by dispose)
// A JS function field added at any level of the config must be nulled by its type's strip.

private fun NativeBrowserConfiguration.strippingJSCallbacks() =
  copy(
    requestResolver = null,
    browseResolver = null,
    handleTrackLoad = null,
    viewMoreTitle = null,
    resolveAlbumPath = null,
    formatNavigationError = null,
    request = request?.strippingJSCallbacks(),
    browse = browse?.strippingJSCallbacks(),
    media = media?.strippingJSCallbacks(),
    artwork = artwork?.strippingJSCallbacks(),
    nowPlayingArtwork = nowPlayingArtwork?.strippingJSCallbacks(),
    routes = routes?.map { it.strippingJSCallbacks() }?.toTypedArray(),
  )

private fun TransformableRequestConfig.strippingJSCallbacks() =
  copy(transform = null, transformSync = null)

private fun MediaRequestConfig.strippingJSCallbacks() =
  copy(resolve = null, resolveSync = null, transform = null, transformSync = null)

private fun ArtworkRequestConfig.strippingJSCallbacks() =
  copy(resolve = null, resolveSync = null, transform = null, transformSync = null)

private fun NativeRouteEntry.strippingJSCallbacks() =
  copy(
    browseCallback = null,
    browseConfig = browseConfig?.strippingJSCallbacks(),
    searchCallback = null,
    searchConfig = searchConfig?.strippingJSCallbacks(),
    media = media?.strippingJSCallbacks(),
    artwork = artwork?.strippingJSCallbacks(),
  )

/**
 * The navigation error [e] raises, shared by failed navigations, the rejection
 * [AudioBrowser.search] hands JS and the Android Auto browse-error tile, so one failure is named
 * one way wherever it surfaces.
 */
internal fun navigationErrorFor(e: Exception): NavigationError =
  when (e) {
    is HttpStatusException ->
      NavigationError(
        NavigationErrorType.HTTP_ERROR,
        e.message ?: "Server error",
        e.statusCode.toDouble(),
        e.statusCode in 200..299,
      )
    is NetworkException ->
      NavigationError(
        NavigationErrorType.NETWORK_ERROR,
        e.message ?: "Network request failed",
        null,
        null,
      )
    is ContentNotFoundException ->
      NavigationError(
        NavigationErrorType.CONTENT_NOT_FOUND,
        e.message ?: "Content not found",
        null,
        null,
      )
    is CallbackException ->
      NavigationError(
        NavigationErrorType.CALLBACK_ERROR,
        e.message ?: "An error occurred",
        null,
        null,
      )
    else ->
      NavigationError(
        NavigationErrorType.UNKNOWN_ERROR,
        e.message ?: "An unexpected error occurred",
        null,
        null,
      )
  }

/** Carries a [NavigationError] across a rejected Promise, whose JS error keeps its message. */
class NavigationErrorException(val error: NavigationError) : Exception(error.message)

/** The built-in display copy for [error], and the fallback when the app's formatter fails. */
internal fun defaultFormattedError(error: NavigationError): FormattedNavigationError {
  val title =
    when (error.code) {
      NavigationErrorType.CONTENT_NOT_FOUND -> "Content Not Found"
      NavigationErrorType.NETWORK_ERROR -> "Network Error"
      NavigationErrorType.HTTP_ERROR -> {
        error.statusCode?.let { httpStatusText(it.toInt()) } ?: "Server Error"
      }
      NavigationErrorType.CALLBACK_ERROR -> "Error"
      NavigationErrorType.UNKNOWN_ERROR -> "Error"
      // Not a failure: a container that resolved with no children (ADR 0001).
      NavigationErrorType.EMPTY_CONTENT -> "Nothing here"
      NavigationErrorType.TIMEOUT -> "Couldn't load"
    }
  // An empty message is omitted so it renders title-only.
  return FormattedNavigationError(title, error.message.takeIf { it.isNotEmpty() })
}

/** The app's formatted error from [format], or [default] when it returns null or throws. */
internal suspend fun formattedOrDefault(
  default: FormattedNavigationError,
  format: suspend () -> FormattedNavigationError?,
): FormattedNavigationError =
  try {
    format()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  } ?: default

/**
 * Runs [run] in this scope and hands its outcome to [settle] once. Unlike Nitro's `Promise.async`,
 * a scope cancelled before [run] starts still settles, with the cancellation, and [run] never runs.
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun <T> CoroutineScope.launchSettled(settle: (Result<T>) -> Unit, run: suspend () -> T) {
  launch(start = CoroutineStart.ATOMIC) {
    settle(
      runCatching {
        ensureActive()
        run()
      }
    )
  }
}

private fun httpStatusText(statusCode: Int): String {
  return when (statusCode) {
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    408 -> "Request Timeout"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    504 -> "Gateway Timeout"
    else -> "Server Error"
  }
}
