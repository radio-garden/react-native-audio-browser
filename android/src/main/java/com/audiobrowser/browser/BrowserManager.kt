package com.audiobrowser.browser

import com.audiobrowser.http.HttpClient
import com.audiobrowser.http.RequestConfigBuilder
import com.margelo.nitro.audiobrowser.BrowserList
import com.margelo.nitro.audiobrowser.BrowserSource
import com.margelo.nitro.audiobrowser.BrowserSourceCallbackParam
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import com.margelo.nitro.audiobrowser.Variant__query__String_____Promise_Promise_Array_Track____TransformableRequestConfig as SearchSource
import com.margelo.nitro.audiobrowser.Variant__param__BrowserSourceCallbackParam_____Promise_Promise_BrowserList___Array_BrowserLink__TransformableRequestConfig as TabsSource
import com.margelo.nitro.audiobrowser.Variant__param__BrowserSourceCallbackParam_____Promise_Promise_BrowserList___TransformableRequestConfig_BrowserList as BrowseSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Core browser manager that handles navigation, search, and media browsing.
 * 
 * This class contains the main business logic for:
 * - Route resolution and path matching with parameter extraction
 * - HTTP API requests and response processing  
 * - JavaScript callback invocation
 * - Fallback handling and error management
 */
class BrowserManager {
    private val router = SimpleRouter()
    private val requestBuilder = RequestConfigBuilder()
    private val httpClient = HttpClient()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private var currentPath: String = "/"
    
    /**
     * Navigate to a path and return browser content.
     * 
     * @param path The path to navigate to (e.g., "/artists/123")
     * @param config Browser configuration containing routes, browse fallback, etc.
     * @return BrowserList containing the navigation result
     */
    suspend fun navigate(path: String, config: BrowserConfig): BrowserList {
        Timber.d("Navigating to path: $path")
        
        try {
            // Update current path
            currentPath = path
            
            // First try to match against configured routes
            config.routes?.takeUnless { it.isEmpty() }?.let { routes ->
                router.findBestMatch(path, routes)?.let { (routePattern, match) ->
                    val browserSource = routes[routePattern]!!
                    val routeParams = match.params
                    
                    Timber.d("Matched route: $routePattern with params: $routeParams")
                    return resolveBrowserSource(browserSource, path, routeParams, config)
                }
            }
            
            // No route matched, fall back to browse configuration
            config.browse?.let { browseSource ->
                Timber.d("No route matched, using browse fallback")
                return resolveBrowseSource(browseSource, path, emptyMap(), config)
            }
            
            // No routes and no browse fallback configured
            Timber.w("No route matched and no browse fallback configured for path: $path")
            return createEmptyBrowserList(path, "No content configured for this path")
            
        } catch (e: Exception) {
            Timber.e(e, "Error during navigation to path: $path")
            return createErrorBrowserList(path, "Navigation failed: ${e.message}")
        }
    }
    
    /**
     * Search for tracks using the configured search source.
     * 
     * @param query The search query string
     * @param config Browser configuration containing search source
     * @return Array of Track results
     */
    suspend fun search(query: String, config: BrowserConfig): Array<Track> {
        Timber.d("Searching for: $query")
        
        try {
            return config.search?.let { searchSource ->
                resolveSearchSource(searchSource, query, config)
            } ?: run {
                Timber.w("Search requested but no search source configured")
                emptyArray()
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error during search for query: $query")
            return emptyArray()
        }
    }
    
    /**
     * Get the current navigation path.
     * 
     * @return Current path string
     */
    fun getCurrentPath(): String {
        return currentPath
    }
    
    /**
     * Resolve a BrowserSource into a BrowserList.
     * Handles the three possible types: static BrowserList, callback, or API config.
     */
    private suspend fun resolveBrowserSource(
        source: BrowserSource,
        path: String,
        routeParams: Map<String, String>,
        config: BrowserConfig
    ): BrowserList {
        return source.match(
            // Callback function
            first = { callback ->
                Timber.d("Resolving browser source via callback")
                val param = BrowserSourceCallbackParam(path, routeParams)
                val promise = callback.invoke(param)
                val innerPromise = promise.await()
                innerPromise.await()
            },
            // API configuration  
            second = { apiConfig ->
                Timber.d("Resolving browser source via API config")
                executeApiRequest(apiConfig, routeParams, config)
            },
            // Static BrowserList
            third = { staticList ->
                Timber.d("Resolving browser source via static list")
                staticList
            }
        )
    }
    
    /**
     * Resolve a BrowseSource (which doesn't include static BrowserList option).
     */
    private suspend fun resolveBrowseSource(
        source: BrowseSource,
        path: String,
        routeParams: Map<String, String>,
        config: BrowserConfig
    ): BrowserList {
        return source.match(
            // Callback function
            first = { callback ->
                Timber.d("Resolving browse source via callback")
                val param = BrowserSourceCallbackParam(path, routeParams)
                val promise = callback.invoke(param)
                val innerPromise = promise.await()
                innerPromise.await()
            },
            // API configuration
            second = { apiConfig ->
                Timber.d("Resolving browse source via API config")
                executeApiRequest(apiConfig, routeParams, config)
            },
            // Static BrowserList (not available in BrowseSource)
            third = { staticList ->
                Timber.d("Resolving browse source via static list")
                staticList
            }
        )
    }
    
    /**
     * Resolve a SearchSource into Track results.
     */
    private suspend fun resolveSearchSource(
        source: SearchSource,
        query: String,
        config: BrowserConfig
    ): Array<Track> {
        return source.match(
            // Callback function
            first = { callback ->
                Timber.d("Resolving search source via callback")
                val promise = callback.invoke(query)
                val innerPromise = promise.await()
                innerPromise.await()
            },
            // API configuration
            second = { apiConfig ->
                Timber.d("Resolving search source via API config")
                executeSearchApiRequest(apiConfig, query, config)
            }
        )
    }
    
    /**
     * Execute an API request for browser content.
     * Handles URL parameter substitution, config merging, and transforms.
     */
    private suspend fun executeApiRequest(
        apiConfig: TransformableRequestConfig,
        routeParams: Map<String, String>,
        browserConfig: BrowserConfig
    ): BrowserList {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Start with base config, apply API config on top
                val baseConfig = browserConfig.request ?: RequestConfig(
                    method = null,
                    path = null,
                    baseUrl = null,
                    headers = null,
                    query = null,
                    body = null,
                    contentType = null,
                    userAgent = null
                )
                val mergedConfig = requestBuilder.mergeConfig(baseConfig, apiConfig, routeParams)
                
                // 2. Build and execute HTTP request
                val httpRequest = requestBuilder.buildHttpRequest(mergedConfig)
                val response = httpClient.request(httpRequest)
                
                response.fold(
                    onSuccess = { httpResponse ->
                        if (httpResponse.isSuccessful) {
                            // 3. Parse response as BrowserList
                            val jsonBrowserList = json.decodeFromString<JsonBrowserList>(httpResponse.body)
                            jsonBrowserList.toNitro()
                        } else {
                            Timber.w("HTTP request failed with status ${httpResponse.code}: ${httpResponse.body}")
                            createErrorBrowserList(getCurrentPath(), "Server returned ${httpResponse.code}")
                        }
                    },
                    onFailure = { exception ->
                        Timber.e(exception, "HTTP request failed")
                        createErrorBrowserList(getCurrentPath(), "Network request failed: ${exception.message}")
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Error executing API request")
                createErrorBrowserList(getCurrentPath(), "Request failed: ${e.message}")
            }
        }
    }
    
    /**
     * Execute an API request for search results.
     * Automatically adds query as { q: query } to request parameters.
     */
    private suspend fun executeSearchApiRequest(
        apiConfig: TransformableRequestConfig,
        query: String,
        browserConfig: BrowserConfig
    ): Array<Track> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Start with base config
                val baseConfig = browserConfig.request ?: RequestConfig(
                    method = null,
                    path = null,
                    baseUrl = null,
                    headers = null,
                    query = null,
                    body = null,
                    contentType = null,
                    userAgent = null
                )
                
                // 2. Create a copy of API config with added query parameter
                val searchConfig = TransformableRequestConfig(
                    transform = apiConfig.transform,
                    method = apiConfig.method,
                    path = apiConfig.path,
                    baseUrl = apiConfig.baseUrl,
                    headers = apiConfig.headers,
                    query = (apiConfig.query ?: emptyMap()) + mapOf("q" to query),
                    body = apiConfig.body,
                    contentType = apiConfig.contentType,
                    userAgent = apiConfig.userAgent
                )
                
                // 3. Merge configs and apply transform if provided
                var mergedConfig = requestBuilder.mergeConfig(baseConfig, searchConfig, emptyMap())
                
                // 4. Build and execute HTTP request
                val httpRequest = requestBuilder.buildHttpRequest(mergedConfig)
                val response = httpClient.request(httpRequest)
                
                response.fold(
                    onSuccess = { httpResponse ->
                        if (httpResponse.isSuccessful) {
                            // 4. Parse response as Track array
                            val jsonTracks = json.decodeFromString<List<JsonTrack>>(httpResponse.body)
                            jsonTracks.map { it.toNitro() }.toTypedArray()
                        } else {
                            Timber.w("Search HTTP request failed with status ${httpResponse.code}: ${httpResponse.body}")
                            emptyArray()
                        }
                    },
                    onFailure = { exception ->
                        Timber.e(exception, "Search HTTP request failed")
                        emptyArray()
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Error executing search API request")
                emptyArray()
            }
        }
    }
    
    /**
     * Create an empty BrowserList for cases where no content is available.
     */
    private fun createEmptyBrowserList(path: String, message: String): BrowserList {
        return BrowserList(
            children = arrayOf(),
            style = null,
            playable = null,
            url = path,
            title = message,
            subtitle = null,
            icon = null,
            artwork = null,
            artist = null,
            album = null,
            description = null,
            genre = null,
            duration = null
        )
    }
    
    /**
     * Create an error BrowserList for exception cases.
     */
    private fun createErrorBrowserList(path: String, errorMessage: String): BrowserList {
        return BrowserList(
            children = arrayOf(),
            style = null,
            playable = null,
            url = path,
            title = "Error",
            subtitle = errorMessage,
            icon = null,
            artwork = null,
            artist = null,
            album = null,
            description = null,
            genre = null,
            duration = null
        )
    }
}

/**
 * Configuration object that holds all browser settings.
 * This will be passed from AudioBrowser.kt to contain all the configured sources.
 */
data class BrowserConfig(
    val request: RequestConfig? = null,
    val media: TransformableRequestConfig? = null,
    val search: SearchSource? = null,
    val routes: Map<String, BrowserSource>? = null,
    val tabs: TabsSource? = null,
    val browse: BrowseSource? = null
)