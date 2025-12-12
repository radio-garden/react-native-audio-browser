package com.audiobrowser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.Keep
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
import com.audiobrowser.extension.NumberExt.Companion.toSeconds
import com.audiobrowser.http.RequestConfigBuilder
import com.audiobrowser.model.PlaybackMetadata
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.model.PlayerUpdateOptions
import com.audiobrowser.model.TimedMetadata
import com.audiobrowser.util.BatteryOptimizationHelper
import com.audiobrowser.util.BatteryWarningStore
import com.audiobrowser.util.BrowserPathHelper
import com.audiobrowser.util.CoilBitmapLoader
import com.facebook.proguard.annotations.DoNotStrip
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.NitroModules
import com.margelo.nitro.audiobrowser.AudioCommonMetadataReceivedEvent
import com.margelo.nitro.audiobrowser.AudioMetadata
import com.margelo.nitro.audiobrowser.AudioMetadataReceivedEvent
import com.margelo.nitro.audiobrowser.BatteryOptimizationStatus
import com.margelo.nitro.audiobrowser.BatteryOptimizationStatusChangedEvent
import com.margelo.nitro.audiobrowser.BatteryWarningPendingChangedEvent
import com.margelo.nitro.audiobrowser.EqualizerSettings
import com.margelo.nitro.audiobrowser.FavoriteChangedEvent
import com.margelo.nitro.audiobrowser.FormattedNavigationError
import com.margelo.nitro.audiobrowser.HybridAudioBrowserSpec
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.NativeBrowserConfiguration
import com.margelo.nitro.audiobrowser.NativeUpdateOptions
import com.margelo.nitro.audiobrowser.NavigationError
import com.margelo.nitro.audiobrowser.NavigationErrorEvent
import com.margelo.nitro.audiobrowser.NavigationErrorType
import com.margelo.nitro.audiobrowser.NowPlayingMetadata
import com.margelo.nitro.audiobrowser.NowPlayingUpdate
import com.margelo.nitro.audiobrowser.Options
import com.margelo.nitro.audiobrowser.PartialSetupPlayerOptions
import com.margelo.nitro.audiobrowser.Playback
import com.margelo.nitro.audiobrowser.PlaybackActiveTrackChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackError
import com.margelo.nitro.audiobrowser.PlaybackErrorEvent
import com.margelo.nitro.audiobrowser.PlaybackPlayWhenReadyChangedEvent
import com.margelo.nitro.audiobrowser.PlaybackProgressUpdatedEvent
import com.margelo.nitro.audiobrowser.PlaybackQueueEndedEvent
import com.margelo.nitro.audiobrowser.PlayingState
import com.margelo.nitro.audiobrowser.Progress
import com.margelo.nitro.audiobrowser.RemoteJumpBackwardEvent
import com.margelo.nitro.audiobrowser.RemoteJumpForwardEvent
import com.margelo.nitro.audiobrowser.RemotePlayIdEvent
import com.margelo.nitro.audiobrowser.RemotePlaySearchEvent
import com.margelo.nitro.audiobrowser.RemoteSeekEvent
import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent
import com.margelo.nitro.audiobrowser.RemoteSkipEvent
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.RepeatModeChangedEvent
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.SleepTimer
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.UpdateOptions
import com.margelo.nitro.core.Promise
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

@Keep
@DoNotStrip
class AudioBrowser : HybridAudioBrowserSpec(), ServiceConnection {
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
      media = null,
      artwork = null,
      routes = null,
      singleTrack = null,
      androidControllerOfflineError = null,
      carPlayUpNextButton = null,
      carPlayNowPlayingButtons = null,
      carPlayNowPlayingRates = null,
      formatNavigationError = null,
    )

  internal val browserManager =
    BrowserManager().apply {
      setOnPathChanged { path -> onPathChanged(path) }
      setOnContentChanged { content -> onContentChanged(content) }
      setOnTabsChanged { tabs -> onTabsChanged(tabs) }
    }

  // MARK: Player state
  private var updateOptions: PlayerUpdateOptions = PlayerUpdateOptions()
  private var mediaBrowserFuture: ListenableFuture<MediaBrowser>? = null
  private var setupOptions = PlayerSetupOptions()
  private var connectedService: Service? = null
  private var setupPromise: ((Unit) -> Unit)? = null

  /** Post callback to main handler for consistent async delivery to JS - avoids deadlocks */
  private fun post(block: () -> Unit) = handler.post(block)

  // MARK: Browser callbacks
  override var onPathChanged: (String) -> Unit = {}
  override var onContentChanged: (ResolvedTrack?) -> Unit = {}
  override var onTabsChanged: (Array<Track>) -> Unit = {}
  override var onNavigationError: (NavigationErrorEvent) -> Unit = {}
  override var onFormattedNavigationError: (FormattedNavigationError?) -> Unit = {}

  // MARK: Player callbacks
  override var onPlaybackChanged: (data: Playback) -> Unit = {}
  override var onRemoteBookmark: () -> Unit = {}
  override var onRemoteDislike: () -> Unit = {}
  override var onRemoteJumpBackward: (RemoteJumpBackwardEvent) -> Unit = {}
  override var onRemoteJumpForward: (RemoteJumpForwardEvent) -> Unit = {}
  override var onRemoteLike: () -> Unit = {}
  override var onRemoteNext: () -> Unit = {}
  override var onRemotePause: () -> Unit = {}
  override var onMetadataChapterReceived: (AudioMetadataReceivedEvent) -> Unit = {}
  override var onMetadataCommonReceived: (AudioCommonMetadataReceivedEvent) -> Unit = {}
  override var onMetadataTimedReceived: (AudioMetadataReceivedEvent) -> Unit = {}
  override var onPlaybackMetadata: (com.margelo.nitro.audiobrowser.PlaybackMetadata) -> Unit = {}
  override var onPlaybackActiveTrackChanged: (data: PlaybackActiveTrackChangedEvent) -> Unit = {}
  override var onPlaybackError: (data: PlaybackErrorEvent) -> Unit = {}
  override var onPlaybackPlayWhenReadyChanged: (data: PlaybackPlayWhenReadyChangedEvent) -> Unit =
    {}
  override var onPlaybackPlayingState: (data: PlayingState) -> Unit = {}
  override var onPlaybackProgressUpdated: (data: PlaybackProgressUpdatedEvent) -> Unit = {}
  override var onPlaybackQueueEnded: (data: PlaybackQueueEndedEvent) -> Unit = {}
  override var onPlaybackQueueChanged: (queue: Array<Track>) -> Unit = {}
  override var onPlaybackRepeatModeChanged: (data: RepeatModeChangedEvent) -> Unit = {}
  override var onPlaybackShuffleModeChanged: (enabled: Boolean) -> Unit = {}
  override var onRemotePlay: (() -> Unit) = {}
  override var onRemotePlayId: (RemotePlayIdEvent) -> Unit = {}
  override var onRemotePlaySearch: (RemotePlaySearchEvent) -> Unit = {}
  override var onRemotePrevious: () -> Unit = {}
  override var onRemoteSeek: (RemoteSeekEvent) -> Unit = {}
  override var onRemoteSetRating: (RemoteSetRatingEvent) -> Unit = {}
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

  // MARK: Remote handlers
  override var handleRemoteBookmark: (() -> Unit)? = null
  override var handleRemoteDislike: (() -> Unit)? = null
  override var handleRemoteJumpBackward: ((RemoteJumpBackwardEvent) -> Unit)? = null
  override var handleRemoteJumpForward: ((RemoteJumpForwardEvent) -> Unit)? = null
  override var handleRemoteLike: (() -> Unit)? = null
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
    // Auto-bind to service if it's already running
    launchInScope {
      try {
        Timber.d("Attempting to auto-bind to existing AudioBrowserService from AudioBrowser")
        val intent = Intent(context, Service::class.java)
        val bound = context.bindService(intent, this@AudioBrowser, Context.BIND_AUTO_CREATE)
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
  }

  // ============================================================================
  // MARK: Browser Configuration
  // ============================================================================

  internal fun buildConfig(): BrowserConfig {
    return BrowserConfig(
      request = _configuration.request,
      media = _configuration.media,
      artwork = _configuration.artwork,
      routes = _configuration.routes,
      singleTrack = _configuration.singleTrack ?: false,
      androidControllerOfflineError = _configuration.androidControllerOfflineError ?: true,
    )
  }

  /**
   * Returns the artwork configuration for use by CoilBitmapLoader. This provides access to the base
   * request config and artwork-specific config.
   */
  fun getArtworkConfig(): CoilBitmapLoader.ArtworkConfig? {
    val artworkConfig = _configuration.artwork ?: return null
    val baseConfig =
      _configuration.request?.let { RequestConfigBuilder.toRequestConfig(it) }
        ?: RequestConfig(null, null, null, null, null, null, null, null)
    return CoilBitmapLoader.ArtworkConfig(baseConfig, artworkConfig)
  }

  /**
   * Creates a request config for media URL transformation by merging base and media configs.
   * Returns null if no media configuration is set.
   */
  fun getMediaRequestConfig(originalUrl: String): MediaRequestConfig? {
    val mediaConfig = _configuration.media ?: return null

    return try {
      // Create base request config with the original URL as path
      val baseConfig =
        _configuration.request?.let { RequestConfigBuilder.toRequestConfig(it) }
          ?: RequestConfig(null, null, null, null, null, null, null, null)
      val urlRequestConfig = RequestConfig(null, originalUrl, null, null, null, null, null, null)
      val mergedBaseConfig = RequestConfigBuilder.mergeConfig(baseConfig, urlRequestConfig)

      // Apply media transformation
      runBlocking { RequestConfigBuilder.mergeConfig(mergedBaseConfig, mediaConfig) }
    } catch (e: Exception) {
      Timber.e(e, "Failed to transform media URL: $originalUrl")
      null
    }
  }

  private fun hasValidConfiguration(): Boolean {
    // Need at least one browsable route (not just search)
    return _configuration.routes?.any { it.path != BrowserManager.SEARCH_ROUTE_PATH } == true
  }

  private fun getDefaultPath(): String? {
    return try {
      browserManager.config = buildConfig()
      val tabs = runBlocking { browserManager.queryTabs() }
      if (tabs.isNotEmpty()) {
        Timber.d("Using first tab as default path: ${tabs[0].url}")
        tabs[0].url
      } else {
        Timber.d("Using root path as default: /")
        "/"
      }
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
        (value ?: getDefaultPath())?.let { path ->
          clearNavigationError()

          // Cancel previous navigation to avoid race conditions
          navigationJob?.cancel()

          navigationJob =
            mainScope.launch {
              try {
                browserManager.navigate(path)
              } catch (e: CancellationException) {
                throw e // Rethrow to properly cancel
              } catch (e: Exception) {
                handleBrowserException(e, "setting path: $path")
              }
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

      // Navigate to initial path or default to first tab
      (value.path ?: getDefaultPath())?.let { path ->
        clearNavigationError()

        // Cancel previous navigation to avoid race conditions
        navigationJob?.cancel()

        navigationJob =
          mainScope.launch {
            try {
              browserManager.navigate(path)
            } catch (e: CancellationException) {
              throw e // Rethrow to properly cancel
            } catch (e: Exception) {
              handleBrowserException(e, "setting configuration path: $path")
            }
          }
      }
    }

  private var navigationError: NavigationError? = null
  private var formattedNavigationError: FormattedNavigationError? = null

  override fun getNavigationError(): NavigationError? = navigationError
  override fun getFormattedNavigationError(): FormattedNavigationError? = formattedNavigationError

  /** Creates a default formatted error from a NavigationError */
  private fun defaultFormattedError(error: NavigationError): FormattedNavigationError {
    val title = when (error.code) {
      NavigationErrorType.CONTENT_NOT_FOUND -> "Content Not Found"
      NavigationErrorType.NETWORK_ERROR -> "Network Error"
      NavigationErrorType.HTTP_ERROR -> {
        // Use system-localized HTTP status text (e.g., "Not Found", "Service Unavailable")
        error.statusCode?.let { httpStatusText(it.toInt()) } ?: "Server Error"
      }
      NavigationErrorType.CALLBACK_ERROR -> "Error"
      NavigationErrorType.UNKNOWN_ERROR -> "Error"
    }
    return FormattedNavigationError(title, error.message)
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
    statusCode: Double? = null,
    statusCodeSuccess: Boolean? = null,
  ) {
    val navError = NavigationError(code, message, statusCode, statusCodeSuccess)
    navigationError = navError
    onNavigationError(NavigationErrorEvent(navigationError))

    // Format the error (async if using JS callback, sync for defaults)
    val formatter = _configuration.formatNavigationError
    if (formatter != null) {
      mainScope.launch {
        try {
          val customFormatted = formatter(navError).await()
          formattedNavigationError = customFormatted ?: defaultFormattedError(navError)
        } catch (e: Exception) {
          formattedNavigationError = defaultFormattedError(navError)
        }
        onFormattedNavigationError(formattedNavigationError)
      }
    } else {
      formattedNavigationError = defaultFormattedError(navError)
      onFormattedNavigationError(formattedNavigationError)
    }
  }

  /** Maps common browser exceptions to navigation errors */
  private fun handleBrowserException(e: Exception, logContext: String) {
    when (e) {
      is HttpStatusException -> {
        Timber.e(e, "HTTP error $logContext")
        setNavigationError(
          NavigationErrorType.HTTP_ERROR,
          e.message ?: "Server error",
          e.statusCode.toDouble(),
          e.statusCode in 200..299,
        )
      }
      is NetworkException -> {
        Timber.e(e, "Network error $logContext")
        setNavigationError(NavigationErrorType.NETWORK_ERROR, e.message ?: "Network request failed")
      }
      is ContentNotFoundException -> {
        Timber.e(e, "Content not found $logContext")
        setNavigationError(NavigationErrorType.CONTENT_NOT_FOUND, e.message ?: "Content not found")
      }
      is CallbackException -> {
        Timber.e(e, "Callback error $logContext")
        setNavigationError(NavigationErrorType.CALLBACK_ERROR, e.message ?: "An error occurred")
      }
      else -> {
        Timber.e(e, "Unexpected error $logContext")
        setNavigationError(NavigationErrorType.UNKNOWN_ERROR, e.message ?: "An unexpected error occurred")
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
          handleBrowserException(e, "navigating to path: $path")
        }
      }
  }

  override fun navigateTrack(track: Track) {
    clearNavigationError()

    // Cancel previous navigation to avoid race conditions
    navigationJob?.cancel()

    navigationJob =
      mainScope.launch {
        try {
          val url = track.url
          when {
            // Check if this is a contextual URL (playable-only track with queue context)
            url != null && BrowserPathHelper.isContextual(url) -> {
              Timber.d("Navigating to contextual track URL: $url")

              val parentPath = BrowserPathHelper.stripTrackId(url)
              val trackId = BrowserPathHelper.extractTrackId(url)

              // Check if queue already came from this parent path - just skip to the track
              if (trackId != null && parentPath == player.queueSourcePath) {
                val index = player.tracks.indexOfFirst { it.src == trackId }
                if (index >= 0) {
                  Timber.d("Queue already from $parentPath, skipping to index $index")
                  player.skipTo(index)
                  player.play()
                  return@launch
                }
              }

              // Expand the queue from the contextual URL
              val expanded = browserManager.expandQueueFromContextualUrl(url)

              if (expanded != null) {
                val (tracks, startIndex) = expanded
                Timber.d(
                  "Loading expanded queue: ${tracks.size} tracks, starting at index $startIndex"
                )

                // Replace queue and seek to selected track
                // Use internal player methods directly to avoid blocking on main thread
                player.setQueue(tracks, startIndex, sourcePath = parentPath)
                player.play()
              } else {
                // Fallback: just load the single track
                Timber.w("Queue expansion failed, loading single track")
                player.load(track)
              }
            }
            // Navigate to browsable track to show browsing UI
            url != null -> {
              Timber.d("Navigating to browsable track: $url")
              browserManager.navigate(url)
            }
            // If track is playable (has src), load it into player
            track.src != null -> {
              Timber.d("Loading playable track into player: ${track.title}")
              player.load(track)
            }
            else -> {
              throw IllegalArgumentException("Track must have either an 'url' or an 'src' property")
            }
          }
        } catch (e: CancellationException) {
          throw e // Rethrow to properly cancel
        } catch (e: Exception) {
          handleBrowserException(e, "navigating to track: ${track.title}")
        }
      }
  }

  override fun onSearch(query: String): Promise<Array<Track>> {
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

  override fun setFavorites(favorites: Array<String>) {
    browserManager.setFavorites(favorites.toList())
  }

  // ============================================================================
  // MARK: Player Setup and Options
  // ============================================================================

  override fun setupPlayer(options: PartialSetupPlayerOptions): Promise<Unit> {
    return Promise.async(mainScope) {
      setupOptions.update(options)

      connectedService?.let {
        it.player.setup(setupOptions)
        return@async
      }

      // Service not connected yet, bind to service
      suspendCoroutine<Unit> { continuation ->
        Timber.d("Binding to AudioBrowserService")
        val bound =
          context.bindService(
            Intent(context, Service::class.java),
            this@AudioBrowser,
            Context.BIND_AUTO_CREATE,
          )

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

  override fun getOptions(): UpdateOptions {
    return player.getOptions().toNitro()
  }

  // ============================================================================
  // MARK: Player Control Methods
  // ============================================================================

  override fun load(track: Track) {
    launchInScope { player.load(track) }
  }

  override fun reset() = runBlockingOnMain {
    player.stop()
    delay(300) // Allow playback to stop
    player.clear()
  }

  override fun play() = runBlockingOnMain { player.play() }

  override fun pause() = runBlockingOnMain { player.pause() }

  override fun togglePlayback() = runBlockingOnMain { player.togglePlayback() }

  override fun stop() = runBlockingOnMain { player.stop() }

  override fun setPlayWhenReady(playWhenReady: Boolean) = runBlockingOnMain {
    player.playWhenReady = playWhenReady
  }

  override fun getPlayWhenReady(): Boolean = runBlockingOnMain { player.playWhenReady }

  override fun seekTo(position: Double) = runBlockingOnMain {
    player.seekTo((position * 1000).toLong(), TimeUnit.MILLISECONDS)
  }

  override fun seekBy(offset: Double) = runBlockingOnMain {
    player.seekBy((offset * 1000).toLong(), TimeUnit.MILLISECONDS)
  }

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

  override fun getRepeatMode(): RepeatMode = runBlockingOnMain { player.repeatMode }

  override fun setRepeatMode(mode: RepeatMode) = runBlockingOnMain { player.repeatMode = mode }

  override fun getShuffleEnabled(): Boolean = runBlockingOnMain { player.shuffleMode }

  override fun setShuffleEnabled(enabled: Boolean) = runBlockingOnMain {
    player.shuffleMode = enabled
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

  override fun setQueue(tracks: Array<Track>, startIndex: Double?, startPositionMs: Double?) =
    runBlockingOnMain {
      player.setQueue(tracks, startIndex?.toInt() ?: 0, startPositionMs?.toLong() ?: 0)
    }

  override fun setActiveTrackFavorited(favorited: Boolean) = runBlockingOnMain {
    player.setActiveTrackFavorited(favorited)
  }

  override fun toggleActiveTrackFavorited() = runBlockingOnMain {
    player.toggleActiveTrackFavorited()
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
    player.updateNowPlaying(update)
  }

  override fun getNowPlaying(): NowPlayingMetadata? = runBlockingOnMain { player.getNowPlaying() }

  // ============================================================================
  // MARK: Network Connectivity
  // ============================================================================

  override fun getOnline(): Boolean = runBlockingOnMain { player.getOnline() }

  // ============================================================================
  // MARK: Equalizer (Android only)
  // ============================================================================

  override fun getEqualizerSettings(): EqualizerSettings? = runBlockingOnMain {
    player.getEqualizerSettings()
  }

  override fun setEqualizerEnabled(enabled: Boolean) = runBlockingOnMain {
    player.setEqualizerEnabled(enabled)
  }

  override fun setEqualizerPreset(preset: String) = runBlockingOnMain {
    player.setEqualizerPreset(preset)
  }

  override fun setEqualizerLevels(levels: DoubleArray) = runBlockingOnMain {
    player.setEqualizerLevels(levels)
  }

  // ============================================================================
  // MARK: Sleep Timer
  // ============================================================================

  override fun getSleepTimer(): SleepTimer = runBlockingOnMain { player.getSleepTimer() }

  override fun setSleepTimer(seconds: Double) = runBlockingOnMain { player.setSleepTimer(seconds) }

  override fun setSleepTimerToEndOfTrack() = runBlockingOnMain {
    player.setSleepTimerToEndOfTrack()
  }

  override fun clearSleepTimer(): Boolean = runBlockingOnMain { player.clearSleepTimer() }

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
          // Start observing network connectivity changes
          player.observeNetworkConnectivity(mainScope)
          // Set browser reference for media URL transformation
          player.browser = this@AudioBrowser
        }

      // Wire up battery warning callback from service
      connectedService?.onBatteryWarningPendingChanged = { pending ->
        post {
          this@AudioBrowser.onBatteryWarningPendingChanged(
            BatteryWarningPendingChangedEvent(pending)
          )
        }
      }

      val sessionToken = SessionToken(context, ComponentName(context, Service::class.java))
      mediaBrowserFuture = MediaBrowser.Builder(context, sessionToken).buildAsync()

      setupPromise?.invoke(Unit)
      setupPromise = null
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

      override fun onMetadataCommonReceived(metadata: AudioMetadata) {
        post {
          this@AudioBrowser.onMetadataCommonReceived(AudioCommonMetadataReceivedEvent(metadata))
        }
      }

      override fun onMetadataTimedReceived(metadata: TimedMetadata) {
        post { this@AudioBrowser.onMetadataTimedReceived(metadata.toNitro()) }
      }

      override fun onPlaybackMetadata(metadata: PlaybackMetadata) {
        post { this@AudioBrowser.onPlaybackMetadata(metadata.toNitro()) }
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

      override fun onRemoteSetRating(event: RemoteSetRatingEvent) {
        post { this@AudioBrowser.onRemoteSetRating(event) }
      }

      override fun onOptionsChanged(options: PlayerUpdateOptions) {
        // Not currently emitted to JS
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
}
