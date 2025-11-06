package com.audiobrowser

import android.content.Context
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.audiobrowser.BrowserList
import com.margelo.nitro.audiobrowser.BrowserSource
import com.margelo.nitro.audiobrowser.BrowserItemStyle
import com.margelo.nitro.audiobrowser.HybridAudioBrowserSpec
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.Variant__query__String_____Promise_Promise_Array_Track____TransformableRequestConfig as SearchSource
import com.margelo.nitro.audiobrowser.Variant__param__BrowserSourceCallbackParam_____Promise_Promise_BrowserList___Array_BrowserLink__TransformableRequestConfig as TabsSource
import com.margelo.nitro.audiobrowser.Variant__param__BrowserSourceCallbackParam_____Promise_Promise_BrowserList___TransformableRequestConfig_BrowserList as BrowseSource
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.MainScope
import timber.log.Timber
import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.browser.BrowserConfig

@Keep
@DoNotStrip
class AudioBrowser : HybridAudioBrowserSpec() {
  private val mainScope = MainScope()
  private val context =
    NitroModules.applicationContext
      ?: throw IllegalStateException("NitroModules.applicationContext is null")
  
  private val browserManager = BrowserManager()

  private fun buildConfig(): BrowserConfig {
    return BrowserConfig(
      routes = routes,
      browse = browse,
      search = search,
      tabs = tabs,
      request = request,
      media = media
    )
  }

  // Browser API configuration properties
  override var request: RequestConfig? = null
  override var media: TransformableRequestConfig? = null
  override var search: SearchSource? = null
  override var routes: Map<String, BrowserSource>? = null
  override var tabs: TabsSource? = null
  override var browse: BrowseSource? = null

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

  override fun getCurrentPath(): String {
    return browserManager.getCurrentPath()
  }
}