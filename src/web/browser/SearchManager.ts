import type { Track } from '../../types'
import type { BrowserManager } from './BrowserManager'
import type { HttpClient } from '../http/HttpClient'

/**
 * Manages search functionality.
 * Resolves search queries using configured __search__ route.
 * Matches Android's search architecture.
 */
export class SearchManager {
  constructor(
    private browserManager: BrowserManager,
    private httpClient: HttpClient
  ) {}

  /**
   * Executes a search query using the __search__ route configuration.
   *
   * @param query Search query string
   * @returns Array of matching tracks
   */
  async search(query: string): Promise<Track[]> {
    // Find __search__ route entry
    const searchRoute = this.browserManager.configuration.routes?.find(r => r.path === '__search__')
    if (!searchRoute) {
      return []
    }

    // Handle callback-based search
    if (searchRoute.searchCallback) {
      return searchRoute.searchCallback({ query })
    }

    // Handle request config-based search
    if (searchRoute.searchConfig) {
      const requestConfig = this.httpClient.mergeRequestConfig(searchRoute.searchConfig, {
        query: { q: query }
      })

      try {
        const response = await this.httpClient.executeRequest(requestConfig)
        return Array.isArray(response) ? (response as Track[]) : []
      } catch (error) {
        console.error('Search failed:', error)
        return []
      }
    }

    return []
  }
}
