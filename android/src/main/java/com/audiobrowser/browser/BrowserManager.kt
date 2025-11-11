package com.audiobrowser.browser

import com.audiobrowser.http.HttpClient
import com.audiobrowser.http.RequestConfigBuilder
import com.margelo.nitro.audiobrowser.BrowserSource
import com.margelo.nitro.audiobrowser.BrowserSourceCallbackParam
import com.margelo.nitro.audiobrowser.RequestConfig
import com.margelo.nitro.audiobrowser.Track
import com.audiobrowser.SearchSource
import com.audiobrowser.TabsSource
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
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
    private val httpClient = HttpClient()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var onPathChanged: ((String) -> Unit)? = null
    private var onContentChanged: ((ResolvedTrack?) -> Unit)? = null
    private var onTabsChanged: ((Array<Track>) -> Unit)? = null

    private var path: String = "/"
        set(value) {
            val previous = field
            field = value
            if (previous != value) {
                onPathChanged?.invoke(value)
            }
        }

    private var content: ResolvedTrack? = null
        set(value) {
            val previous = field
            field = value
            if (previous != value) {
                onContentChanged?.invoke(value)
            }
        }

    private var tabs: Array<Track>? = null
        set(value) {
            val previous = field
            field = value
            // Arrays need contentEquals for comparison
            if (value != null && !value.contentEquals(previous)) {
                onTabsChanged?.invoke(value)
            } else if (value == null && previous != null) {
                onTabsChanged?.invoke(emptyArray())
            }
        }

    /**
     * Browser configuration containing routes, search, tabs, and request settings.
     * This can be updated dynamically when the configuration changes.
     */
    var config: BrowserConfig = BrowserConfig()


    suspend fun resolve(path: String): ResolvedTrack {
        // First try to match against configured routes
        config.routes?.takeUnless { it.isEmpty() }?.let { routes ->
            router.findBestMatch(path, routes)?.let { (routePattern, match) ->
                val browserSource = routes[routePattern]!!
                val routeParams = match.params

                Timber.d("Matched route: $routePattern with params: $routeParams")
                return resolveBrowserSource(browserSource, path, routeParams)
            }
        }

        // No route matched, fall back to browse configuration
        config.browse?.let { browseSource ->
            Timber.d("No route matched, using browse fallback")
            return resolveBrowseSource(browseSource, path, emptyMap())
        }

        // No routes and no browse fallback configured
        Timber.e("No route matched and no browse fallback configured for path: $path")
        throw ContentNotFoundException(path)
    }

    /**
     * Navigate to a path and return browser content.
     *
     * @param path The path to navigate to (e.g., "/artists/123")
     * @return BrowserList containing the navigation result
     */
    suspend fun navigate(path: String): ResolvedTrack {
        Timber.d("Navigating to path: $path")

        this.path = path
        val content = resolve(path)
        this.content = content
        return content
    }

    /**
     * Search for tracks using the configured search source.
     *
     * @param query The search query string
     * @return Array of Track results
     */
    suspend fun search(query: String): Array<Track> {
        Timber.d("Searching for: $query")

        try {
            return config.search?.let { searchSource ->
                resolveSearchSource(searchSource, query)
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
    fun getPath(): String {
        return path
    }

    /**
     * Get the current loaded content.
     *
     * @return Current ResolvedTrack content or null if none loaded
     */
    fun getContent(): ResolvedTrack? {
        return content
    }

    /**
     * Get the current cached tabs.
     *
     * @return Current tabs array or null if none loaded
     */
    fun getTabs(): Array<Track>? {
        return tabs
    }

    /**
     * Set callback for path changes.
     */
    fun setOnPathChanged(callback: (String) -> Unit) {
        onPathChanged = callback
    }

    /**
     * Set callback for content changes.
     */
    fun setOnContentChanged(callback: (ResolvedTrack?) -> Unit) {
        onContentChanged = callback
    }

    /**
     * Set callback for tabs changes.
     */
    fun setOnTabsChanged(callback: (Array<Track>) -> Unit) {
        onTabsChanged = callback
    }

    /**
     * Query navigation tabs from the configured tabs source.
     * This is an async operation that resolves the tabs configuration.
     *
     * @return Array of Track objects representing tabs
     */
    suspend fun queryTabs(): Array<Track> {
        // Return cached tabs if available
        this.tabs?.let { return it }

        Timber.d("Getting navigation tabs")

        return (config.tabs?.let { tabsSource ->
            resolveTabsSource(tabsSource)
        } ?: run {
            Timber.d("No tabs configured")
            emptyArray()
        }).also { this.tabs = it }
    }

    /**
     * Resolve a BrowserSource into a BrowserList.
     * Handles the three possible types: static BrowserList, callback, or API config.
     */
    private suspend fun resolveBrowserSource(
        source: BrowserSource,
        path: String,
        routeParams: Map<String, String>
    ): ResolvedTrack {
        return source.match(
            // Callback function
            first = { callback ->
                Timber.d("Resolving browser source via callback")
                val param = BrowserSourceCallbackParam(path, routeParams)
                val promise = callback.invoke(param)
                val innerPromise = promise.await()
                innerPromise.await()
            },
            // Static BrowserList
            second = { staticList ->
                Timber.d("Resolving browser source via static list")
                staticList
            },
            // API configuration
            third = { apiConfig ->
                Timber.d("Resolving browser source via API config")
                executeApiRequest(apiConfig, routeParams)
            }
        )
    }

    /**
     * Resolve a BrowseSource (which doesn't include static BrowserList option).
     */
    private suspend fun resolveBrowseSource(
        source: BrowseSource,
        path: String,
        routeParams: Map<String, String>
    ): ResolvedTrack {
        return source.match(
            // Callback function
            first = { callback ->
                Timber.d("Resolving browse source via callback")
                val param = BrowserSourceCallbackParam(path, routeParams)
                val promise = callback.invoke(param)
                val innerPromise = promise.await()
                innerPromise.await()
            },
            // Static BrowserList
            second = { staticList ->
                Timber.d("Resolving browse source via static list")
                staticList
            },
            // API configuration
            third = { apiConfig ->
                Timber.d("Resolving browse source via API config")
                executeApiRequest(apiConfig, routeParams)
            }
        )
    }

    /**
     * Resolve a SearchSource into Track results.
     */
    private suspend fun resolveSearchSource(
        source: SearchSource,
        query: String
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
                executeSearchApiRequest(apiConfig, query)
            }
        )
    }

    /**
     * Resolve a TabsSource into BrowserLink array.
     * Handles the three possible types: static array, callback, or API config.
     */
    private suspend fun resolveTabsSource(
        source: TabsSource
    ): Array<Track> {
        return source.match(
            // Callback function
            first = { callback ->
                Timber.d("Resolving tabs source via callback")
                callback.invoke().await().await()
            },
            // Static array of BrowserLink
            second = { staticTabs ->
                Timber.d("Resolving tabs source via static array")
                staticTabs
            },
            // API configuration
            third = { apiConfig ->
                Timber.d("Resolving tabs source via API config")
                executeTabsApiRequest(apiConfig)
            }
        )
    }

    /**
     * Execute an API request for browser content.
     * Handles URL parameter substitution, config merging, and transforms.
     */
    private suspend fun executeApiRequest(
        apiConfig: TransformableRequestConfig,
        routeParams: Map<String, String>
    ): ResolvedTrack {
        return withContext(Dispatchers.IO) {
            // 1. Start with base config, apply API config on top
            val baseConfig = config.request ?: RequestConfig(
                method = null,
                path = null,
                baseUrl = null,
                headers = null,
                query = null,
                body = null,
                contentType = null,
                userAgent = null
            )
            val mergedConfig = RequestConfigBuilder.mergeConfig(baseConfig, apiConfig, routeParams)

            // 2. Build and execute HTTP request
            val httpRequest = RequestConfigBuilder.buildHttpRequest(mergedConfig)
            val response = httpClient.request(httpRequest)

            response.fold(
                onSuccess = { httpResponse ->
                    if (httpResponse.isSuccessful) {
                        // 3. Parse response as ResolvedTrack
                        val jsonResolvedTrack =
                            json.decodeFromString<JsonResolvedTrack>(httpResponse.body)
                        jsonResolvedTrack.toNitro()
                    } else {
                        Timber.w("HTTP request failed with status ${httpResponse.code}: ${httpResponse.body}")
                        throw HttpStatusException(
                            httpResponse.code,
                            "Server returned ${httpResponse.code}"
                        )
                    }
                },
                onFailure = { exception ->
                    Timber.e(exception, "HTTP request failed")
                    throw NetworkException(
                        "Network request failed: ${exception.message}",
                        exception
                    )
                }
            )
        }
    }

    /**
     * Execute an API request for search results.
     * Automatically adds query as { q: query } to request parameters.
     */
    private suspend fun executeSearchApiRequest(
        apiConfig: TransformableRequestConfig,
        query: String
    ): Array<Track> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Start with base config
                val baseConfig = config.request ?: RequestConfig(
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
                var mergedConfig = RequestConfigBuilder.mergeConfig(baseConfig, searchConfig, emptyMap())

                // 4. Build and execute HTTP request
                val httpRequest = RequestConfigBuilder.buildHttpRequest(mergedConfig)
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
     * Execute an API request for tabs.
     * Expects the API to return a BrowserList with BrowserLink children.
     */
    private suspend fun executeTabsApiRequest(
        apiConfig: TransformableRequestConfig
    ): Array<Track> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Start with base config
                val baseConfig = config.request ?: RequestConfig(
                    method = null,
                    path = null,
                    baseUrl = null,
                    headers = null,
                    query = null,
                    body = null,
                    contentType = null,
                    userAgent = null
                )

                // 2. Merge configs with default path of '/' for tabs
                val mergedConfig = RequestConfigBuilder.mergeConfig(baseConfig, apiConfig, emptyMap<String, String>())

                // 3. Build and execute HTTP request
                val httpRequest = RequestConfigBuilder.buildHttpRequest(mergedConfig)
                val response = httpClient.request(httpRequest)

                response.fold(
                    onSuccess = { httpResponse ->
                        if (httpResponse.isSuccessful) {
                            // 4. Parse response as ResolvedTrack
                            val jsonResolvedTrack = json.decodeFromString<JsonResolvedTrack>(httpResponse.body)
                            val resolvedTrack = jsonResolvedTrack.toNitro()

                            // 5. Extract Track items from the resolved track
                            resolvedTrack.children
                                ?: throw IllegalStateException("Expected browsed ResolvedTrack to have a children array")
                        } else {
                            Timber.w("Tabs HTTP request failed with status ${httpResponse.code}: ${httpResponse.body}")
                            emptyArray()
                        }
                    },
                    onFailure = { exception ->
                        Timber.e(exception, "Tabs HTTP request failed")
                        emptyArray()
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Error executing tabs API request")
                emptyArray()
            }
        }
    }

}

/**
 * Exception thrown when no content is configured for a requested path.
 */
class ContentNotFoundException(val path: String) : Exception("No content configured for path: $path")

/**
 * Exception thrown when an HTTP request fails with a non-2xx status code.
 */
class HttpStatusException(val statusCode: Int, message: String) : Exception(message)

/**
 * Exception thrown when a network request fails (connection error, timeout, etc).
 */
class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Configuration object that holds all browser settings.
 * This will be passed from AudioBrowser.kt to contain all the configured sources.
 */
typealias BrowseSource = com.margelo.nitro.audiobrowser.Variant__param__BrowserSourceCallbackParam_____Promise_Promise_ResolvedTrack___ResolvedTrack_TransformableRequestConfig

data class BrowserConfig(
    val request: RequestConfig? = null,
    val media: MediaRequestConfig? = null,
    val search: SearchSource? = null,
    val routes: Map<String, BrowserSource>? = null,
    val tabs: TabsSource? = null,
    val browse: BrowseSource? = null
)