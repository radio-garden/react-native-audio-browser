package com.audiobrowser

import androidx.annotation.Keep
import com.audiobrowser.browser.BrowserConfig
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.http.RequestConfigBuilder
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.audiobrowser.BrowserConfiguration
import com.margelo.nitro.audiobrowser.BrowserList
import com.margelo.nitro.audiobrowser.HybridAudioBrowserSpec
import com.margelo.nitro.audiobrowser.RequestConfig
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

  private var _configuration = BrowserConfiguration(
      path = null,
      request = null,
      media = null,
      search = null,
      routes = null,
      tabs = null,
      browse = null
  )

  private val browserManager =
      BrowserManager().apply {
        setOnPathChanged { path -> onPathChanged(path) }
        setOnContentChanged { content -> onContentChanged(content) }
        setOnTabsChanged { tabs -> onTabsChanged(tabs) }
      }

  private fun buildConfig(): BrowserConfig {
    return BrowserConfig(
        request = _configuration.request,
        media = _configuration.media,
        search = _configuration.search,
        routes = _configuration.routes,
        tabs = _configuration.tabs,
        browse = _configuration.browse
    )
  }

  /**
   * Creates a request config for media URL transformation by merging base and media configs.
   * Returns null if no media configuration is set.
   */
  fun getMediaRequestConfig(originalUrl: String): RequestConfig? {
    val mediaConfig = _configuration.media ?: return null

    return try {
      // Create base request config with the original URL as path
      val baseConfig = _configuration.request ?: RequestConfig(null, null, null, null, null, null, null, null)
      val urlRequestConfig = RequestConfig(null, originalUrl, null, null, null, null, null, null)
      val mergedBaseConfig = RequestConfigBuilder.mergeConfig(baseConfig, urlRequestConfig)

      // Apply media transformation
      runBlocking { RequestConfigBuilder.mergeConfig(mergedBaseConfig, mediaConfig) }
    } catch (e: Exception) {
      Timber.e(e, "Failed to transform media URL: $originalUrl")
      null
    }
  }

  /**
   * Registers this AudioBrowser instance with AudioPlayer for media URL transformation. Should be
   * called after both AudioBrowser and AudioPlayer are created.
   */
  fun registerWithAudioPlayer(audioPlayer: AudioPlayer) {
    audioPlayer.registerAudioBrowser(this)
  }

  private fun hasValidConfiguration(): Boolean {
    return _configuration.tabs != null || _configuration.routes?.isNotEmpty() == true || _configuration.browse != null
  }

  private fun getDefaultPath(): String? {
    return try {
      val config = buildConfig()
      val tabs = runBlocking { browserManager.getTabs(config) }
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
    get() = _configuration.path
    set(value) { 
      _configuration = _configuration.copy(path = value)
      if (hasValidConfiguration()) {
        (value ?: getDefaultPath())?.let {
          mainScope.launch { browserManager.navigate(it, buildConfig()) }
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
        (_configuration.path ?: getDefaultPath())?.let {
          mainScope.launch { browserManager.navigate(it, buildConfig()) }
        }
    }

  override var onPathChanged: (String) -> Unit = {}
  override var onContentChanged: (BrowserList?) -> Unit = {}
  override var onTabsChanged: (Array<Track>) -> Unit = {}

  // Browser navigation methods
  override fun navigate(path: String): Promise<BrowserList> {
    return Promise.async(mainScope) {
      Timber.d("Navigating to path: $path")
      browserManager.navigate(path, buildConfig())
    }
  }

  override fun onSearch(query: String): Promise<Array<Track>> {
    return Promise.async(mainScope) {
      Timber.d("Searching for: $query")
      browserManager.search(query, buildConfig())
    }
  }

  override fun getContent(): BrowserList? {
    return browserManager.getContent()
  }
}
