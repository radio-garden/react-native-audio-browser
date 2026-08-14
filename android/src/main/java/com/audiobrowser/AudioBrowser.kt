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
import com.margelo.nitro.core.Promise
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

@Keep
@DoNotStrip
class AudioBrowser : HybridAudioBrowserSpec(), ServiceConnection {

  /**
   * Nitro calls this only when JS calls `dispose()` explicitly — never on runtime teardown, which
   * is what [AudioBrowserLifecycleModule] is for. Both routes land here.
   *
   * The Service outlives this object, so everything registered in `init` or on connect has to be
   * undone. Left alone, the running [Player] goes on invoking callbacks into a dead JSI runtime,
   * and every reload strands another ServiceConnection and lifecycle observer, each retaining a
   * BrowserManager.
   *
   * Runs the teardown on main: the state below is written from main-thread coroutines
   * ([onServiceConnected], [onServiceDisconnected]), while dispose itself arrives on the JS thread
   * or the teardown callback's thread.
   */
  override fun dispose() {
    current.compareAndSet(this, null)
    systemVolumeMonitor.destroy()
    outputMonitor.destroy()
    // Only when still ours: on a reload the replacement may already own the slot.
    carConnectionTarget.compareAndSet(onCarConnectedChanged, null)

    // The runtime behind the config's JS functions is going away, but anything still holding
    // this instance (an in-flight browse coroutine, the running player) can invoke them after
    // it's gone — each invoke then throws instead of answering, so browse takes its error path
    // rather than its fallback.
    dropStaleJSCallbacks()

    handler.post {
      connectedService?.let { service ->
        // Identity-guarded: both instances bind to the same singleton Service, so a late dispose
        // must not strip the wiring a replacement has already installed.
        if (service.player.getCallbacks() === callbacks) service.player.setCallbacks(null)
        if (service.player.browser === this) {
          service.player.browser = null
          service.player.forgetBrowserRegistration()
          // The formatter wraps a JS callback from this instance's runtime (installed by our
          // setup(); browser still being ours means no replacement has run setup yet). Without
          // this, every track change until the next setup() throws into the updater's fallback.
          service.player.nowPlaying.formatter = null
        }
        if (service.onBatteryWarningPendingChanged === onBatteryWarningPending) {
          service.onBatteryWarningPendingChanged = null
        }
      }
      connectedService = null

      // Dropping the reference disconnects nothing — the controller stays in the session's
      // connected list, holding a binder and a listener.
      mediaBrowserFuture?.let { MediaBrowser.releaseFuture(it) }
      mediaBrowserFuture = null
      setupPromise = null

      serviceBinding.unbind(this)

      ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
      // After the observer removal, so this post is not the one dropped.
      handler.removeCallbacksAndMessages(null)
      mainScope.cancel()
    }
    super.dispose()
  }

  /**
   * Demotes the config to data-only (deep strip of every JS function field) and reverts the JS
   * callbacks stored outside the config tree ([resolveGate]; the now-playing formatter is cleared
   * in [dispose]'s service block, identity-guarded). Stale paths then degrade the same way as a
   * config that never had callbacks — static request/browse layers, default error copy, fail-closed
   * gate — instead of throwing into the error path. Mirrors iOS `dropStaleJSCallbacks`, where the
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
  private var connectedService: Service? = null

  private val serviceBinding = ServiceBinding(context)

  /** Held rather than inlined so [dispose] can tell its own wiring from a replacement's. */
  private val onBatteryWarningPending: (Boolean) -> Unit = { pending ->
    post { onBatteryWarningPendingChanged(BatteryWarningPendingChangedEvent(pending)) }
  }
  private var setupPromise: ((Unit) -> Unit)? = null

  // Initial player state staged before the player exists — from setup options or the imperative
  // setters called pre-bind. Strict last-write-wins; consumed when the player comes up.
  private var pendingPlayWhenReady: Boolean? = null
  private var pendingRepeatMode: RepeatMode? = null

  /** Post callback to main handler for consistent async delivery to JS - avoids deadlocks */
  private fun post(block: () -> Unit) = handler.post(block)

  // MARK: Browser callbacks
  override var onPathChanged: (String) -> Unit = {}
  override var onContentChanged: (ResolvedTrack?) -> Unit = {}
  override var onTabsChanged: (Array<Track>) -> Unit = {}
  override var onNavigationError: (NavigationErrorEvent) -> Unit = {}
  override var onFormattedNavigationError: (FormattedNavigationError?) -> Unit = {}

  // MARK: Gate callbacks (native→JS; set from JS, native CALLS them)
  // Default resolver: only reachable while hasResolver=true but no live runtime has the real
  // `resolveGate` bound — the init window before JS assigns it, or after [dispose] reverts a dead
  // runtime's resolver. It DENIES by default
  // (gated=true) so an active gate never serves content during that window — same fail-closed
  // direction as the resolver-error path in [gateDecision]. The static-gate fast path skips it
  // entirely once a resolver-less gate is set.
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

  /** Lifecycle observer to check battery status when app comes to foreground */
  private val lifecycleObserver =
    object : DefaultLifecycleObserver {
      override fun onStart(owner: LifecycleOwner) {
        checkBatteryStatusChange()
      }
    }

  init {
    current.set(this)

    // Auto-bind to service if it's already running
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

    // Observe app lifecycle to check battery status on foreground
    // Must run on main thread as required by LifecycleRegistry.addObserver
    handler.post { ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver) }

    // Observe system volume changes
    systemVolumeMonitor.setOnVolumeChanged { volume -> post { onSystemVolumeChanged(volume) } }

    // Observe current audio output changes (active media route via AudioManager)
    outputMonitor.setOnOutputChanged { output -> post { onOutputChanged(output) } }
    outputMonitor.start()

    // Observe the car connection (Android Auto / Android Automotive)
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
    )
  }

  /**
   * Media URL transformation for [com.audiobrowser.player.TransformingDataSource]. Resolution lives
   * in [resolveMediaUrl] (browser/BrowserUrlResolution.kt); this shell owns only the blocking
   * bridge: it runs on ExoPlayer's IO thread (TransformingDataSource.open), so blocking here is
   * safe and intentional.
   */
  fun getMediaRequestConfig(originalUrl: String): MediaRequestConfig? {
    return try {
      runBlocking { browserManager.resolveMediaUrl(originalUrl) }
    } catch (e: Exception) {
      Timber.e(e, "Failed to transform media URL: $originalUrl")
      null
    }
  }

  private fun hasValidConfiguration(): Boolean {
    // Need at least one browsable route (not just search)
    return _configuration.routes?.any { it.path != BrowserManager.SEARCH_ROUTE_PATH } == true
  }

  // Suspending on purpose: queryTabs() resolves tabs by invoking JS callbacks,
  // which can only run on the JS thread. Callers MUST invoke this from a
  // coroutine (off the JS thread) — never via runBlocking on a synchronous Nitro
  // setter, which runs on the JS thread and would deadlock (the JS thread blocks
  // waiting for callbacks that need that same thread to run).
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
      throw e // Rethrow so cooperative cancellation isn't swallowed
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

        // Cancel previous navigation to avoid race conditions
        navigationJob?.cancel()

        // Resolve the default path inside the coroutine (getDefaultPath queries
        // tabs via JS callbacks) so it never blocks the JS thread this setter
        // runs on.
        navigationJob =
          mainScope.launch {
            val path = value ?: getDefaultPath() ?: return@launch
            try {
              browserManager.navigate(path)
            } catch (e: CancellationException) {
              throw e // Rethrow to properly cancel
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

      // Notify player that browser configuration is ready (routes/tabs available)
      // This allows Android Auto to start browsing content
      connectedService?.player?.notifyBrowserConfigurationReady()

      clearNavigationError()

      // Cancel previous navigation to avoid race conditions
      navigationJob?.cancel()

      // Navigate to the initial path, or default to the first tab. The default
      // path is resolved inside the coroutine (getDefaultPath queries tabs via
      // JS callbacks) so it never blocks the JS thread this setter runs on —
      // doing it synchronously here via runBlocking deadlocked: the JS callbacks
      // can't run because the JS thread is blocked waiting for them.
      navigationJob =
        mainScope.launch {
          val path = value.path ?: getDefaultPath() ?: return@launch
          try {
            browserManager.navigate(path)
          } catch (e: CancellationException) {
            throw e // Rethrow to properly cancel
          } catch (e: Exception) {
            handleBrowserException(e, path, "setting configuration path: $path")
          }
        }
    }

  private var navigationError: NavigationError? = null
  private var formattedNavigationError: FormattedNavigationError? = null

  override fun getNavigationError(): NavigationError? = navigationError

  override fun getFormattedNavigationError(): FormattedNavigationError? = formattedNavigationError

  /** Creates a default formatted error from a NavigationError */
  private fun defaultFormattedError(error: NavigationError): FormattedNavigationError {
    val title =
      when (error.code) {
        NavigationErrorType.CONTENT_NOT_FOUND -> "Content Not Found"
        NavigationErrorType.NETWORK_ERROR -> "Network Error"
        NavigationErrorType.HTTP_ERROR -> {
          // Use system-localized HTTP status text (e.g., "Not Found", "Service Unavailable")
          error.statusCode?.let { httpStatusText(it.toInt()) } ?: "Server Error"
        }
        NavigationErrorType.CALLBACK_ERROR -> "Error"
        NavigationErrorType.UNKNOWN_ERROR -> "Error"
        // Not a failure — a container that resolved with no children. Neutral copy. See ADR 0001.
        NavigationErrorType.EMPTY_CONTENT -> "Nothing here"
        NavigationErrorType.TIMEOUT -> "Couldn't load"
      }
    // Omit an empty message so it renders as title-only (e.g. the empty-content case).
    return FormattedNavigationError(title, error.message.takeIf { it.isNotEmpty() })
  }

  /** Returns localized HTTP status text for the given status code */
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

  private fun setNavigationError(
    code: NavigationErrorType,
    message: String,
    path: String,
    statusCode: Double? = null,
    statusCodeSuccess: Boolean? = null,
  ) {
    val navError = NavigationError(code, message, statusCode, statusCodeSuccess)
    navigationError = navError
    onNavigationError(NavigationErrorEvent(navigationError))

    // Format the error (async if using JS callback, sync for defaults)
    val defaultFormatted = defaultFormattedError(navError)
    val formatter = _configuration.formatNavigationError
    if (formatter != null) {
      mainScope.launch {
        try {
          val params = FormatNavigationErrorParams(navError, defaultFormatted, path)
          val customFormatted = formatter(params).await()
          formattedNavigationError = customFormatted ?: defaultFormatted
        } catch (e: Exception) {
          formattedNavigationError = defaultFormatted
        }
        onFormattedNavigationError(formattedNavigationError)
      }
    } else {
      formattedNavigationError = defaultFormatted
      onFormattedNavigationError(formattedNavigationError)
    }
  }

  /** Maps common browser exceptions to navigation errors */
  private fun handleBrowserException(e: Exception, path: String, logContext: String) {
    when (e) {
      is HttpStatusException -> {
        Timber.e(e, "HTTP error $logContext")
        setNavigationError(
          NavigationErrorType.HTTP_ERROR,
          e.message ?: "Server error",
          path,
          e.statusCode.toDouble(),
          e.statusCode in 200..299,
        )
      }
      is NetworkException -> {
        Timber.e(e, "Network error $logContext")
        setNavigationError(
          NavigationErrorType.NETWORK_ERROR,
          e.message ?: "Network request failed",
          path,
        )
      }
      is ContentNotFoundException -> {
        Timber.e(e, "Content not found $logContext")
        setNavigationError(
          NavigationErrorType.CONTENT_NOT_FOUND,
          e.message ?: "Content not found",
          path,
        )
      }
      is CallbackException -> {
        Timber.e(e, "Callback error $logContext")
        setNavigationError(
          NavigationErrorType.CALLBACK_ERROR,
          e.message ?: "An error occurred",
          path,
        )
      }
      else -> {
        Timber.e(e, "Unexpected error $logContext")
        setNavigationError(
          NavigationErrorType.UNKNOWN_ERROR,
          e.message ?: "An unexpected error occurred",
          path,
        )
      }
    }
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

    // Cancel previous navigation to avoid race conditions
    navigationJob?.cancel()

    navigationJob =
      mainScope.launch {
        try {
          Timber.d("Navigating to path: $path")
          browserManager.navigate(path)
        } catch (e: CancellationException) {
          throw e // Rethrow to properly cancel
        } catch (e: Exception) {
          handleBrowserException(e, path, "navigating to path: $path")
        }
      }
  }

  override fun navigateTrack(track: Track) {
    // A disabled track is unavailable — it never plays, whichever surface or
    // stale resume path delivered the selection (Track.disabled; mirrors iOS
    // TrackSelector.select). Refused before any state changes: an inert tap
    // must not clear an unrelated navigation error or cancel an in-flight
    // navigation.
    if (track.disabled == true) {
      Timber.d("Ignoring selection of disabled track: ${track.title}")
      return
    }

    clearNavigationError()

    // Cancel previous navigation to avoid race conditions
    navigationJob?.cancel()

    val path = track.path
    navigationJob =
      mainScope.launch {
        try {
          when {
            // Check if this is a contextual path (playable-only track with queue context)
            path != null && BrowserPathHelper.isContextual(path) -> {
              Timber.d("Navigating to contextual track path: $path")

              val parentPath = BrowserPathHelper.stripTrackId(path)
              val trackId = BrowserPathHelper.extractTrackId(path)

              // Check if queue already came from this parent path - just skip
              // to the tapped surface (exact path first, identity for
              // index-less paths — see indexOfTappedTrack)
              if (trackId != null && parentPath == player.queueSourcePath) {
                val index = player.tracks.indexOfTappedTrack(path, trackId)
                if (index >= 0) {
                  Timber.d("Queue already from $parentPath, skipping to index $index")
                  handleTrackLoad(
                    _configuration.handleTrackLoad,
                    track,
                    player.tracks,
                    index.toDouble(),
                    intercepted = {},
                    defaultBehavior = {
                      player.skipTo(index)
                      player.play()
                    },
                  )
                  return@launch
                }
              }

              // Expand the queue from the contextual path
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
                  intercepted = {},
                  defaultBehavior = {
                    // Replace queue and seek to selected track
                    // Use internal player methods directly to avoid blocking on main thread
                    player.setQueue(tracks, startIndex, sourcePath = parentPath)
                    player.play()
                  },
                )
                return@launch
              } else {
                // Fallback: just load the single track
                Timber.w("Queue expansion failed, loading single track")
                handleTrackLoad(
                  _configuration.handleTrackLoad,
                  track,
                  arrayOf(track),
                  0.0,
                  intercepted = {},
                  defaultBehavior = { player.load(track) },
                )
              }
            }
            // Navigate to browsable track to show browsing UI
            path != null -> {
              Timber.d("Navigating to browsable track: $path")
              browserManager.navigate(path)
            }
            // If track is playable (has src), load it into player
            track.src != null -> {
              Timber.d("Loading playable track into player: ${track.title}")
              handleTrackLoad(
                _configuration.handleTrackLoad,
                track,
                arrayOf(track),
                0.0,
                intercepted = {},
                defaultBehavior = { player.load(track) },
              )
            }
            else -> {
              throw IllegalArgumentException("Track must have either a 'path' or an 'src' property")
            }
          }
        } catch (e: CancellationException) {
          throw e // Rethrow to properly cancel
        } catch (e: Exception) {
          handleBrowserException(e, path ?: track.src ?: "", "navigating to track: ${track.title}")
        }
      }
  }

  override fun search(query: String): Promise<Array<Track>> {
    return Promise.async(mainScope) {
      Timber.d("Searching for: $query")
      val searchResults = browserManager.search(query)
      searchResults.children ?: emptyArray()
    }
  }

  override fun getContent(): ResolvedTrack? {
    return browserManager.getContent()
  }

  override fun notifyContentChanged(path: String) {
    Timber.d("Notifying content changed for path: $path")

    // Invalidate cached content so future navigations fetch fresh data
    browserManager.invalidateContentCache(path)

    // Notify external media controllers (Android Auto)
    connectedService?.player?.notifyContentChanged(path)

    // If we're currently viewing this path, refresh the content
    if (browserManager.getPath() == path) {
      mainScope.launch { browserManager.refresh() }
    }
  }

  override fun invalidateAllContent() {
    Timber.d("Invalidating all content")

    // Clear all cached content so every path re-fetches fresh data
    browserManager.clearContentCache()

    // Notify external media controllers (Android Auto) to refresh subscribed paths
    connectedService?.player?.invalidateAllContent()

    // Refresh whatever the browser is currently viewing
    mainScope.launch { browserManager.refresh() }
  }

  override fun setFavorites(favorites: Array<String>) {
    browserManager.setFavorites(favorites.toList())
  }

  // MARK: Gate

  /** A gate decision for one request: whether to gate, and which chrome to render. */
  data class GateOutcome(val gated: Boolean, val chrome: Gate?)

  // The minimal chrome rendered when a gate is active but no override or stored
  // default chrome exists (the resolver-only setGate overload).
  private val builtInGate = Gate("Unavailable", null)

  /**
   * The three gate fields as one immutable triple so [gateDecision] reads them as a unit. Written
   * from the JS thread, read from the Media3 application thread (the enforcement sites in
   * MediaSessionCallback consult it) — hence the single field is @Volatile.
   *
   * Folding the three former @Volatile fields into one reference is what makes the read atomic:
   *
   * @Volatile gives per-field visibility but NO atomicity across a compound read, so reading three
   *   separate volatiles in [gateDecision] could interleave with a concurrent [clearGate] and yield
   *   a torn `(active=true, chrome=null)` state that crashed the force-unwrap at the serve sites.
   *   One volatile reference read once cannot tear.
   */
  private data class GateState(val active: Boolean, val chrome: Gate?, val hasResolver: Boolean)

  @Volatile private var gateState = GateState(active = false, chrome = null, hasResolver = false)

  override fun setGate(gate: Gate?, hasResolver: Boolean) {
    // Single atomic assignment — readers never see a half-updated triple.
    gateState = GateState(active = true, chrome = gate, hasResolver = hasResolver)
    // Re-query every subscribed parent so a connected controller (Android
    // Auto) swaps its lists for the gate tile without reconnecting. Notify
    // only — the content cache stays warm for when the gate clears.
    connectedService?.player?.invalidateAllContent()
  }

  override fun clearGate() {
    if (!gateState.active) return
    // Single atomic assignment — readers never see a half-cleared triple.
    gateState = GateState(active = false, chrome = null, hasResolver = false)
    connectedService?.player?.invalidateAllContent()
  }

  /**
   * The single gate choke point. Each enforcement site (browse / search) calls this for the current
   * request and serves the gate chrome when [GateOutcome.gated] is true. Structurally similar to
   * the iOS `gateDecision(for:)` helper, but note the concurrency model differs: iOS confines gate
   * state to `@MainActor`; here the consistent read comes from snapshotting the single [gateState]
   * reference once (see [GateState]).
   * - No active gate → allow.
   * - Active gate, no resolver → static fast path: gate with the stored default chrome (or built-in
   *   if none), no JS hop.
   * - Active gate with a resolver → ask JS per request. A resolver that throws / rejects / times
   *   out FAILS CLOSED by design: while a gate is active the consumer has declared content blocked,
   *   so the only safe fallback when we cannot compute the per-request decision is the gate's own
   *   chrome — never the content. A *successful* `gated:false` still allows. Chrome order on a
   *   gated decision: override → stored default → built-in.
   *
   * Every gated return guards chrome against null (`?: builtInGate`) so a gated outcome always
   * carries a renderable chrome — the serve sites force-unwrap it.
   */
  suspend fun gateDecision(request: NativeGateRequest): GateOutcome {
    // Read the whole gate triple once; a single volatile reference can't tear (see [GateState]).
    val s = gateState
    if (!s.active) return GateOutcome(false, null)
    if (!s.hasResolver) return GateOutcome(true, s.chrome ?: builtInGate) // static fast path
    val decision =
      runCatching { resolveGate(request).await().await() }.getOrNull()
        ?: return GateOutcome(true, s.chrome ?: builtInGate) // fail CLOSED on resolver error
    if (!decision.gated) return GateOutcome(false, null) // explicit allow
    return GateOutcome(true, decision.gate ?: s.chrome ?: builtInGate)
  }

  // MARK: Car connection (Android Auto / Android Automotive)

  override fun isCarConnected(): Boolean = carConnected

  override var onCarConnectedChanged: (Boolean) -> Unit = {}
    set(value) {
      field = value
      carConnectionTarget.set(value)
      // Immediately notify current state (the car may have connected before
      // this JS runtime subscribed) — mirrors the iOS behavior.
      value(carConnected)
    }

  // ============================================================================
  // MARK: Player Setup and Options
  // ============================================================================

  override fun setupPlayer(options: NativeSetupPlayerOptions): Promise<Unit> {
    return Promise.async(mainScope) {
      setupOptions.update(options)
      // The bundled runtime options and initial state are part of the atomic launch
      // description: stage them here, apply them together with the engine below (or at
      // service connect). Last-write-wins with their imperative counterparts.
      options.options?.let { updateOptions.updateFromBridge(it) }
      options.repeatMode?.let { pendingRepeatMode = it }
      options.playWhenReady?.let { pendingPlayWhenReady = it }

      connectedService?.let {
        it.player.applyOptions(updateOptions)
        it.player.setup(setupOptions)
        applyPendingPlayerState(it.player)
        return@async
      }

      // Service not connected yet, bind to service
      suspendCoroutine<Unit> { continuation ->
        Timber.d("Binding to AudioBrowserService")
        val bound = serviceBinding.bind(Intent(context, Service::class.java), this@AudioBrowser)

        if (!bound) {
          continuation.resumeWithException(
            RuntimeException("Failed to bind to AudioBrowserService")
          )
        } else {
          // Service will resolve the promise in onServiceConnected
          setupPromise = { continuation.resume(Unit) }
        }
      }
    }
  }

  override fun updateOptions(options: NativeUpdateOptions) {
    updateOptions.updateFromBridge(options)
    // Only update the options if the service is around
    connectedService?.let { player.applyOptions(updateOptions) }
  }

  override fun getOptions(): Options {
    // The holder is the source of truth whether or not the player exists yet — updateOptions
    // merges into it and setup/connect applies it to the player.
    return updateOptions.toNitro()
  }

  // ============================================================================
  // MARK: Player Control Methods
  // ============================================================================

  override fun load(track: Track) {
    launchInScope { player.load(track) }
  }

  override fun reset() = runBlockingOnMain {
    player.stop()
    player.clear()
    // clear() while IDLE emits no engine event, so the state machine never
    // leaves STOPPED — a reset queue is "nothing loaded".
    player.setPlaybackState(PlaybackState.NONE)
  }

  override fun play() = runBlockingOnMain { player.play() }

  override fun pause() = runBlockingOnMain { player.pause() }

  override fun togglePlayback() = runBlockingOnMain { player.togglePlayback() }

  override fun stop() = runBlockingOnMain { player.stop() }

  override fun setPlayWhenReady(playWhenReady: Boolean) = runBlockingOnMain {
    // Pre-setup this stages the intent for the player to come up with — never throws.
    connectedService?.player?.let { it.playWhenReady = playWhenReady }
      ?: run { pendingPlayWhenReady = playWhenReady }
  }

  override fun getPlayWhenReady(): Boolean = runBlockingOnMain {
    connectedService?.player?.playWhenReady ?: pendingPlayWhenReady ?: false
  }

  override fun seekTo(position: Double) = runBlockingOnMain {
    player.seekTo((position * 1000).toLong(), TimeUnit.MILLISECONDS)
  }

  override fun seekBy(offset: Double) = runBlockingOnMain {
    player.seekBy((offset * 1000).toLong(), TimeUnit.MILLISECONDS)
  }

  override fun seekToLiveEdge() = runBlockingOnMain { player.seekToLiveEdge() }

  override fun setVolume(level: Double) = runBlockingOnMain { player.volume = level.toFloat() }

  override fun getVolume(): Double = runBlockingOnMain { player.volume.toDouble() }

  override fun setRate(rate: Double) = runBlockingOnMain { player.playbackSpeed = rate.toFloat() }

  override fun getRate(): Double = runBlockingOnMain { player.playbackSpeed.toDouble() }

  override fun getProgress(): Progress = runBlockingOnMain {
    Progress(
      duration = player.duration.toSeconds(),
      position = player.position.toSeconds(),
      buffered = player.bufferedPosition.toSeconds(),
    )
  }

  override fun getPlayback(): Playback = runBlockingOnMain { player.getPlayback() }

  override fun getPlayingState(): PlayingState = runBlockingOnMain { player.getPlayingState() }

  override fun getRepeatMode(): RepeatMode = runBlockingOnMain {
    connectedService?.player?.repeatMode ?: pendingRepeatMode ?: RepeatMode.OFF
  }

  override fun setRepeatMode(mode: RepeatMode) = runBlockingOnMain {
    // Pre-setup this stages the mode for the player to come up with — never throws.
    connectedService?.player?.let { it.repeatMode = mode } ?: run { pendingRepeatMode = mode }
  }

  override fun getShuffleEnabled(): Boolean = runBlockingOnMain { player.shuffleMode }

  override fun setShuffleEnabled(enabled: Boolean) = runBlockingOnMain {
    player.shuffleMode = enabled
  }

  override fun setPlaybackIntervalEnabled(enabled: Boolean) = runBlockingOnMain {
    player.setPlaybackIntervalEnabled(enabled)
  }

  override fun getPlaybackError(): PlaybackError? = runBlockingOnMain { player.playbackError }

  override fun retry() = runBlockingOnMain { player.prepare() }

  // ============================================================================
  // MARK: Queue Management
  // ============================================================================

  override fun add(tracks: Array<Track>, insertBeforeIndex: Double?) = runBlockingOnMain {
    val inputIndex = insertBeforeIndex?.toInt() ?: -1
    player.add(tracks, inputIndex)
  }

  override fun move(fromIndex: Double, toIndex: Double) = runBlockingOnMain {
    player.move(fromIndex.toInt(), toIndex.toInt())
  }

  override fun remove(indexes: DoubleArray) = runBlockingOnMain {
    val indexList = indexes.map { it.toInt() }
    player.remove(indexList)
  }

  override fun removeUpcomingTracks() = runBlockingOnMain { player.removeUpcomingTracks() }

  override fun skip(index: Double, initialPosition: Double?) = runBlockingOnMain {
    player.skipTo(index.toInt())

    if (initialPosition != null && initialPosition >= 0) {
      player.seekTo((initialPosition * 1000).toLong(), TimeUnit.MILLISECONDS)
    }
  }

  override fun skipToNext(initialPosition: Double?) = runBlockingOnMain {
    player.next()

    if (initialPosition != null && initialPosition >= 0) {
      player.seekTo((initialPosition * 1000).toLong(), TimeUnit.MILLISECONDS)
    }
  }

  override fun skipToPrevious(initialPosition: Double?) = runBlockingOnMain {
    player.previous()

    if (initialPosition != null && initialPosition >= 0) {
      player.seekTo((initialPosition * 1000).toLong(), TimeUnit.MILLISECONDS)
    }
  }

  override fun setQueue(tracks: Array<Track>, startIndex: Double?, startPosition: Double?) =
    runBlockingOnMain {
      player.setQueue(tracks, startIndex?.toInt() ?: 0, ((startPosition ?: 0.0) * 1000).toLong())
    }

  override fun setActiveTrackFavorited(favorited: Boolean): Unit = runBlockingOnMain {
    // Applied-result is for MediaSession honesty; the JS API stays fire-and-forget.
    player.setActiveTrackFavorited(favorited)
    Unit
  }

  override fun toggleActiveTrackFavorited(): Unit = runBlockingOnMain {
    player.toggleActiveTrackFavorited()
    Unit
  }

  override fun getQueue(): Array<Track> = runBlockingOnMain { player.tracks }

  override fun getTrack(index: Double): Track? = runBlockingOnMain {
    try {
      player.getTrack(index.toInt())
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  override fun getActiveTrackIndex(): Double? = runBlockingOnMain {
    player.currentIndex?.toDouble()
  }

  override fun getActiveTrack(): Track? = runBlockingOnMain { player.currentTrack }

  // ============================================================================
  // MARK: Now Playing Metadata
  // ============================================================================

  override fun updateNowPlaying(update: NowPlayingUpdate?) = runBlockingOnMain {
    player.nowPlaying.updateNowPlaying(update)
  }

  override fun flashNowPlaying(update: NowPlayingUpdate, durationMs: Double) = runBlockingOnMain {
    player.nowPlaying.flashNowPlaying(update, durationMs)
  }

  override fun clearNowPlayingFlash() = runBlockingOnMain {
    player.nowPlaying.clearNowPlayingFlash()
  }

  override fun getNowPlaying(): NowPlayingMetadata? = runBlockingOnMain {
    player.nowPlaying.getNowPlaying()
  }

  // ============================================================================
  // MARK: Network Connectivity
  // ============================================================================

  override fun getOnline(): Boolean = runBlockingOnMain { player.getOnline() }

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

  override fun getEqualizerSettings(): EqualizerSettings? = runBlockingOnMain {
    player.equalizer.getSettings()
  }

  override fun setEqualizerEnabled(enabled: Boolean) = runBlockingOnMain {
    player.equalizer.setEnabled(enabled)
  }

  override fun setEqualizerPreset(preset: String) = runBlockingOnMain {
    player.equalizer.setPreset(preset)
  }

  override fun setEqualizerLevels(levels: DoubleArray) = runBlockingOnMain {
    player.equalizer.setLevels(levels)
  }

  // ============================================================================
  // MARK: Sleep Timer
  // ============================================================================

  override fun getSleepTimer(): SleepTimer = runBlockingOnMain { player.sleepTimer.get() }

  override fun setSleepTimer(seconds: Double, fadeDuration: Double?) = runBlockingOnMain {
    player.sleepTimer.setAfter(seconds, fadeDuration)
  }

  override fun setSleepTimerToEndOfTrack() = runBlockingOnMain { player.sleepTimer.setEndOfTrack() }

  override fun clearSleepTimer(): Boolean = runBlockingOnMain { player.sleepTimer.clear() }

  // ============================================================================
  // MARK: Battery Optimization (Android only)
  // ============================================================================

  /** Track last known status for change detection */
  private var lastKnownBatteryStatus: BatteryOptimizationHelper.Status? = null

  override fun getBatteryWarningPending(): Boolean {
    val rawPending = BatteryWarningStore.isWarningPending(context)
    val status = BatteryOptimizationHelper.getStatus(context)

    // Auto-clear if status is now unrestricted
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

  /**
   * Check if battery status changed since last check and fire events if so. Called automatically
   * when app comes to foreground via ProcessLifecycleOwner.
   */
  private fun checkBatteryStatusChange() {
    val currentStatus = BatteryOptimizationHelper.getStatus(context)
    if (lastKnownBatteryStatus != null && lastKnownBatteryStatus != currentStatus) {
      post {
        onBatteryOptimizationStatusChanged(
          BatteryOptimizationStatusChangedEvent(currentStatus.toNitro())
        )
      }

      // Auto-clear warning if now unrestricted
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
          // Start observing network connectivity changes
          player.observeNetworkConnectivity(mainScope)
          // Set browser reference for media URL transformation
          player.browser = this@AudioBrowser

          // If configuration was already set before service connected,
          // notify player now so Android Auto can start browsing
          if (_configuration.routes?.isNotEmpty() == true) {
            player.notifyBrowserConfigurationReady()
          }
        }

      // Wire up battery warning callback from service
      connectedService?.onBatteryWarningPendingChanged = onBatteryWarningPending

      val sessionToken = SessionToken(context, ComponentName(context, Service::class.java))
      mediaBrowserFuture = MediaBrowser.Builder(context, sessionToken).buildAsync()

      setupPromise?.invoke(Unit)
      setupPromise = null
    }
  }

  /**
   * Applies initial player state staged before the player existed. Consumed on apply so a later
   * re-setup doesn't replay stale state.
   */
  private fun applyPendingPlayerState(player: Player) {
    pendingRepeatMode?.let {
      player.repeatMode = it
      pendingRepeatMode = null
    }
    pendingPlayWhenReady?.let {
      player.playWhenReady = it
      pendingPlayWhenReady = null
    }
  }

  override fun onServiceDisconnected(name: ComponentName) {
    mainScope.coroutineContext.cancelChildren()
    mediaBrowserFuture = null
    connectedService = null
    Timber.d("AudioBrowser.onServiceDisconnected()")
  }

  // ============================================================================
  // MARK: Internal Helpers
  // ============================================================================

  private fun launchInScope(block: suspend () -> Unit) {
    mainScope.launch { block() }
  }

  private fun <T> runBlockingOnMain(block: suspend () -> T): T {
    return runBlocking(mainScope.coroutineContext) { block() }
  }

  private val service: Service
    get() = connectedService ?: throw Exception("Player not initialized")

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

        // Defer notification until after play operation completes
        post { this@AudioBrowser.onRemotePlay() }

        return handled
      }

      override fun handleRemotePause(): Boolean {
        val handled =
          this@AudioBrowser.handleRemotePause?.let {
            it.invoke()
            true
          } ?: false

        // Defer notification until after pause operation completes
        post { this@AudioBrowser.onRemotePause() }

        return handled
      }

      override fun handleRemoteStop(): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteStop?.let {
            it.invoke()
            true
          } ?: false

        // Defer notification until after stop operation completes
        post { this@AudioBrowser.onRemoteStop() }

        return handled
      }

      override fun handleRemoteNext(): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteNext?.let {
            it.invoke()
            true
          } ?: false

        // Defer notification until after next operation completes
        post { this@AudioBrowser.onRemoteNext() }

        return handled
      }

      override fun handleRemotePrevious(): Boolean {
        val handled =
          this@AudioBrowser.handleRemotePrevious?.let {
            it.invoke()
            true
          } ?: false

        // Defer notification until after previous operation completes
        post { this@AudioBrowser.onRemotePrevious() }

        return handled
      }

      override fun handleRemoteJumpForward(event: RemoteJumpForwardEvent): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteJumpForward?.let {
            it.invoke(event)
            true
          } ?: false

        // Defer notification until after jump forward operation completes
        post { this@AudioBrowser.onRemoteJumpForward(event) }

        return handled
      }

      override fun handleRemoteJumpBackward(event: RemoteJumpBackwardEvent): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteJumpBackward?.let {
            it.invoke(event)
            true
          } ?: false

        // Defer notification until after jump backward operation completes
        post { this@AudioBrowser.onRemoteJumpBackward(event) }

        return handled
      }

      override fun handleRemoteSeek(event: RemoteSeekEvent): Boolean {
        val handled =
          this@AudioBrowser.handleRemoteSeek?.let {
            it.invoke(event)
            true
          } ?: false

        // Defer notification until after seek operation completes
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
    // Process-wide car-connection state, from the androidx.car.app
    // CarConnection provider — the documented Android Auto / Automotive
    // connection signal, served by the car system app. Observed ONCE per
    // process: AudioBrowser instances are re-created on a JS reload, and a
    // per-instance observeForever would leak each one. The latest instance's
    // callback registers as the single notification target (mirrors the iOS
    // static + shared-instance pattern).
    @Volatile private var carConnected = false
    // Compare-and-set, not @Volatile: a disposing instance clears the slot only if it still owns
    // it, and a replacement may be claiming it at the same moment.
    private val carConnectionTarget = AtomicReference<((Boolean) -> Unit)?>(null)
    private var carConnectionObserverStarted = false

    /**
     * The instance the current JS runtime is using. Nitro hands out no registry, and the teardown
     * callback has nothing to dispose without one.
     */
    private val current = AtomicReference<AudioBrowser?>(null)

    /** Called by [AudioBrowserLifecycleModule] when the React instance is torn down. */
    internal fun disposeCurrent() {
      current.getAndSet(null)?.dispose()
    }

    /** Shared by the [resolveGate] initializer and [dispose], which reverts to it. */
    private val defaultResolveGate: (NativeGateRequest) -> Promise<Promise<GateDecision>> = {
      Promise.resolved(Promise.resolved(GateDecision(gated = true, gate = null)))
    }

    private fun startCarConnectionObserver(context: Context, handler: Handler) {
      // Posted to main: LiveData observation is main-thread only, and the
      // single-start guard is then only ever touched from one thread.
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
// Functions live at every level of the config, not just the top; each type's strip nils its own.

private fun NativeBrowserConfiguration.strippingJSCallbacks() =
  copy(
    requestResolver = null,
    browseResolver = null,
    handleTrackLoad = null,
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
