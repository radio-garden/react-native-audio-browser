import type { Track, SearchParams } from '../../types'
import type { BrowserManager } from './BrowserManager'
import { RequestConfigBuilder } from '../http/RequestConfigBuilder'

/**
 * Manages search functionality.
 * Resolves search queries using configured __search__ route.
 * Matches Android's search architecture.
 */
export class SearchManager {
  constructor(private browserManager: BrowserManager) {}

  /**
   * Executes a search query using the __search__ route configuration.
   * Matches Android's BrowserManager.search(params: SearchParams) behavior.
   *
   * @param params Search parameters (query is required, other fields optional)
   * @returns Array of matching tracks
   */
  async search(params: SearchParams): Promise<Track[]> {
    // Find __search__ route entry
    const searchRoute = this.browserManager.configuration.routes?.find(
      (r) => r.path === '__search__'
    )
    if (!searchRoute) {
      return []
    }

    let results: Track[] = []

    // Handle callback-based search - pass full SearchParams
    if (searchRoute.searchCallback) {
      results = await searchRoute.searchCallback(params)
    }
    // Handle request config-based search
    else if (searchRoute.searchConfig) {
      // Build query parameters from SearchParams (matches Android's executeSearchApiRequest)
      const searchQueryParams: Record<string, string> = {
        q: params.query
      }
      if (params.mode) searchQueryParams.mode = params.mode
      if (params.genre) searchQueryParams.genre = params.genre
      if (params.artist) searchQueryParams.artist = params.artist
      if (params.album) searchQueryParams.album = params.album
      if (params.title) searchQueryParams.title = params.title
      if (params.playlist) searchQueryParams.playlist = params.playlist

      // Delegate to the shared layered fetch (request → search) on BrowserManager
      // so the ladder lives in one place.
      try {
        results = await this.browserManager.fetchSearchResults(
          searchRoute.searchConfig,
          searchQueryParams
        )
      } catch (error) {
        console.error('Search failed:', error)
        return []
      }
    }

    // Transform artwork URLs on search results using async method with full Track access
    const { request: requestConfig, artwork: artworkConfig } =
      this.browserManager.configuration
    if (artworkConfig) {
      results = await Promise.all(
        results.map(async (track) => {
          const artworkSource =
            await RequestConfigBuilder.resolveArtworkSourceAsync(
              track,
              requestConfig,
              artworkConfig
            )
          if (artworkSource && !track.artworkSource) {
            return { ...track, artworkSource }
          }
          return track
        })
      )
    }

    return results
  }
}
