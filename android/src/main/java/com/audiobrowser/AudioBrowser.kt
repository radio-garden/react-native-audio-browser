package com.audiobrowser

import androidx.annotation.Keep
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.http.RequestConfigBuilder
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.audiobrowser.BrowserConfiguration
import com.margelo.nitro.audiobrowser.HybridAudioBrowserSpec
import com.margelo.nitro.audiobrowser.MediaRequestConfig
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

  internal fun buildConfig(): BrowserConfig {
    return BrowserConfig(
      request = _configuration.request,
      media = _configuration.media,
      search = _configuration.search,
      routes = _configuration.routes,
      tabs = _configuration.tabs,
      browse = _configuration.browse,
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
        (value ?: getDefaultPath())?.let { mainScope.launch { browserManager.navigate(it) } }
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
      (value.path ?: getDefaultPath())?.let { mainScope.launch { browserManager.navigate(it) } }
    }

  override var onPathChanged: (String) -> Unit = {}
  override var onContentChanged: (ResolvedTrack?) -> Unit = {}
  override var onTabsChanged: (Array<Track>) -> Unit = {}

  // Browser navigation methods
  override fun navigatePath(path: String) {
    mainScope.launch {
      Timber.d("Navigating to path: $path")
      browserManager.navigate(path)
    }
  }

  override fun navigateTrack(track: Track) {
    mainScope.launch {
      val url = track.url
      when {
        // Otherwise navigate to it to show browsing UI
        url != null -> {
          Timber.d("Navigating to browsable track: $url")
          browserManager.navigate(url)
        }
        // If track is playable (has src or playable flag), load it into player
        track.src != null || track.playable == true -> {
          Timber.d("Loading playable track into player: ${track.title}")
          audioPlayer?.load(track)
            ?: throw IllegalStateException(
              "AudioPlayer not registered. Call audioPlayer.registerBrowser(audioBrowser) first."
            )
        }
        // No url, src, or playable flag - invalid track
        else -> {
          throw IllegalArgumentException(
            "Track must have either 'url', 'src', or 'playable' property"
          )
        }
      }
    }
  }

  override fun onSearch(query: String): Promise<Array<Track>> {
    return Promise.async(mainScope) {
      Timber.d("Searching for: $query")
      browserManager.search(query)
    }
  }

  override fun getContent(): ResolvedTrack? {
    return browserManager.getContent()
  }
}
