package com.audiobrowser

import androidx.annotation.Keep
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.browser.ContentNotFoundException
import com.audiobrowser.browser.HttpStatusException
import com.audiobrowser.browser.NetworkException
import com.audiobrowser.http.RequestConfigBuilder
import com.audiobrowser.util.BrowserPathHelper
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.audiobrowser.BrowserConfiguration
import com.margelo.nitro.audiobrowser.HybridAudioBrowserSpec
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.NavigationError
import com.margelo.nitro.audiobrowser.NavigationErrorEvent
import com.margelo.nitro.audiobrowser.NavigationErrorType
import com.margelo.nitro.audiobrowser.PlayConfigurationBehavior
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

@Keep
@DoNotStrip
class AudioBrowser : HybridAudioBrowserSpec() {
  private val mainScope = MainScope()
  private val context =
    NitroModules.applicationContext
      ?: throw IllegalStateException("NitroModules.applicationContext is null")

  private var _configuration =
    BrowserConfiguration(
      path = null,
      request = null,
      media = null,
      search = null,
      routes = null,
      tabs = null,
      browse = null,
      play = PlayConfigurationBehavior.SINGLE,
    )

  internal val browserManager =
    BrowserManager().apply {
      setOnPathChanged { path -> onPathChanged(path) }
      setOnContentChanged { content -> onContentChanged(content) }
      setOnTabsChanged { tabs -> onTabsChanged(tabs) }
    }

  private var audioPlayer: AudioPlayer? = null

  /** Internal getter for the player instance with proper error handling */
  private val player: com.audiobrowser.player.Player
    get() =
      audioPlayer?.player
        ?: throw IllegalStateException(
          "AudioPlayer not registered. Call audioPlayer.registerBrowser(audioBrowser) first."
        )

  internal fun buildConfig(): BrowserConfig {
    return BrowserConfig(
      request = _configuration.request,
      media = _configuration.media,
      search = _configuration.search,
      routes = _configuration.routes,
      tabs = _configuration.tabs,
      browse = _configuration.browse,
      play = _configuration.play,
    )
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
        _configuration.request ?: RequestConfig(null, null, null, null, null, null, null, null)
      val urlRequestConfig = RequestConfig(null, originalUrl, null, null, null, null, null, null)
      val mergedBaseConfig = RequestConfigBuilder.mergeConfig(baseConfig, urlRequestConfig)

      // Apply media transformation
      runBlocking { RequestConfigBuilder.mergeConfig(mergedBaseConfig, mediaConfig) }
    } catch (e: Exception) {
      Timber.e(e, "Failed to transform media URL: $originalUrl")
      null
    }
  }

  /** Internal method called by AudioPlayer.registerBrowser() to establish the connection. */
  internal fun setAudioPlayer(audioPlayer: AudioPlayer) {
    this.audioPlayer = audioPlayer
  }

  private fun hasValidConfiguration(): Boolean {
    return _configuration.tabs != null ||
      _configuration.routes?.isNotEmpty() == true ||
      _configuration.browse != null
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

  // Browser API configuration properties
  override var path: String?
    get() = browserManager.getPath()
    set(value) {
      if (hasValidConfiguration()) {
        browserManager.config = buildConfig()
        (value ?: getDefaultPath())?.let { path ->
          clearNavigationError()
          mainScope.launch {
            try {
              browserManager.navigate(path)
            } catch (e: HttpStatusException) {
              Timber.e(e, "HTTP error setting path: $path")
              setNavigationError(NavigationErrorType.HTTP_ERROR, e.message ?: "Server error", e.statusCode.toDouble())
            } catch (e: NetworkException) {
              Timber.e(e, "Network error setting path: $path")
              setNavigationError(NavigationErrorType.NETWORK_ERROR, e.message ?: "Network request failed")
            } catch (e: ContentNotFoundException) {
              Timber.e(e, "Content not found for path: $path")
              setNavigationError(NavigationErrorType.CONTENT_NOT_FOUND, e.message ?: "No content configured for path")
            } catch (e: Exception) {
              Timber.e(e, "Unexpected error setting path: $path")
              setNavigationError(NavigationErrorType.UNKNOWN_ERROR, e.message ?: "An unexpected error occurred")
            }
          }
        }
      }
    }

  override var tabs: Array<Track>?
    get() = browserManager.getTabs()
    set(value) {}

  override var configuration: BrowserConfiguration
    get() = _configuration
    set(value) {
      _configuration = value
      browserManager.config = buildConfig()

      // Navigate to initial path or default to first tab
      (value.path ?: getDefaultPath())?.let { path ->
        clearNavigationError()
        mainScope.launch {
          try {
            browserManager.navigate(path)
          } catch (e: HttpStatusException) {
            Timber.e(e, "HTTP error setting configuration path: $path")
            setNavigationError(NavigationErrorType.HTTP_ERROR, e.message ?: "Server error", e.statusCode.toDouble())
          } catch (e: NetworkException) {
            Timber.e(e, "Network error setting configuration path: $path")
            setNavigationError(NavigationErrorType.NETWORK_ERROR, e.message ?: "Network request failed")
          } catch (e: ContentNotFoundException) {
            Timber.e(e, "Content not found for configuration path: $path")
            setNavigationError(NavigationErrorType.CONTENT_NOT_FOUND, e.message ?: "No content configured for path")
          } catch (e: Exception) {
            Timber.e(e, "Unexpected error setting configuration path: $path")
            setNavigationError(NavigationErrorType.UNKNOWN_ERROR, e.message ?: "An unexpected error occurred")
          }
        }
      }
    }

  override var onPathChanged: (String) -> Unit = {}
  override var onContentChanged: (ResolvedTrack?) -> Unit = {}
  override var onTabsChanged: (Array<Track>) -> Unit = {}
  override var onNavigationError: (NavigationErrorEvent) -> Unit = {}

  private var navigationError: NavigationError? = null

  override fun getNavigationError(): NavigationError? = navigationError

  private fun setNavigationError(code: NavigationErrorType, message: String, statusCode: Double? = null) {
    navigationError = NavigationError(code, message, statusCode)
    onNavigationError(NavigationErrorEvent(navigationError))
  }

  private fun clearNavigationError() {
    if (navigationError != null) {
      navigationError = null
      onNavigationError(NavigationErrorEvent(null))
    }
  }

  // Browser navigation methods
  override fun navigatePath(path: String) {
    clearNavigationError()
    mainScope.launch {
      try {
        Timber.d("Navigating to path: $path")
        browserManager.navigate(path)
      } catch (e: HttpStatusException) {
        Timber.e(e, "HTTP error navigating to path: $path")
        setNavigationError(NavigationErrorType.HTTP_ERROR, e.message ?: "Server error", e.statusCode.toDouble())
      } catch (e: NetworkException) {
        Timber.e(e, "Network error navigating to path: $path")
        setNavigationError(NavigationErrorType.NETWORK_ERROR, e.message ?: "Network request failed")
      } catch (e: ContentNotFoundException) {
        Timber.e(e, "Content not found for path: $path")
        setNavigationError(NavigationErrorType.CONTENT_NOT_FOUND, e.message ?: "No content configured for path")
      } catch (e: Exception) {
        Timber.e(e, "Unexpected error navigating to path: $path")
        setNavigationError(NavigationErrorType.UNKNOWN_ERROR, e.message ?: "An unexpected error occurred")
      }
    }
  }

  override fun navigateTrack(track: Track) {
    clearNavigationError()
    mainScope.launch {
      try {
        val url = track.url
        when {
          // Check if this is a contextual URL (playable-only track with queue context)
          url != null && BrowserPathHelper.isContextual(url) -> {
            Timber.d("Navigating to contextual track URL: $url")

            // Expand the queue from the contextual URL
            val expanded = browserManager.expandQueueFromContextualUrl(url)

            if (expanded != null) {
              val (tracks, startIndex) = expanded
              Timber.d("Loading expanded queue: ${tracks.size} tracks, starting at index $startIndex")

              // Replace queue and seek to selected track
              // Use internal player methods directly to avoid blocking on main thread
              player.setQueue(tracks, startIndex)
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
            throw IllegalArgumentException(
              "Track must have either an 'url' or an 'src' property"
            )
          }
        }
      } catch (e: HttpStatusException) {
        Timber.e(e, "HTTP error navigating to track: ${track.title}")
        setNavigationError(NavigationErrorType.HTTP_ERROR, e.message ?: "Server error", e.statusCode.toDouble())
      } catch (e: NetworkException) {
        Timber.e(e, "Network error navigating to track: ${track.title}")
        setNavigationError(NavigationErrorType.NETWORK_ERROR, e.message ?: "Network request failed")
      } catch (e: ContentNotFoundException) {
        Timber.e(e, "Content not found for track: ${track.title}")
        setNavigationError(NavigationErrorType.CONTENT_NOT_FOUND, e.message ?: "No content configured for path")
      } catch (e: IllegalArgumentException) {
        Timber.e(e, "Invalid track: ${track.title}")
        setNavigationError(NavigationErrorType.UNKNOWN_ERROR, e.message ?: "Invalid track")
      } catch (e: Exception) {
        Timber.e(e, "Unexpected error navigating to track: ${track.title}")
        setNavigationError(NavigationErrorType.UNKNOWN_ERROR, e.message ?: "An unexpected error occurred")
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
}
