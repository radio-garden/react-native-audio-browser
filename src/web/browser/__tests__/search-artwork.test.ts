import { describe, it, expect, vi } from 'vitest'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'
import { SearchManager } from '../SearchManager'

/**
 * Search-result artwork reaches the request layer through
 * `resolvedRequestConfig()`, as content and media artwork do. Reading
 * `configuration.request` handed a resolver-only config no baseUrl and no
 * headers, so a relative artwork path on a search result never resolved.
 */
describe('search result artwork', () => {
  it('applies a resolver-only request layer', async () => {
    const httpClient = {
      executeRequest: vi
        .fn()
        .mockResolvedValue([
          { title: 'Hit', src: '/hit.mp3', artwork: '/art/hit.jpg' }
        ])
    } as unknown as HttpClient
    const manager = new BrowserManager(
      httpClient,
      new FavoriteManager(),
      new NavigationErrorManager()
    )
    manager.configuration = {
      path: '/',
      requestResolver: async () => ({
        baseUrl: 'https://api.example.com',
        headers: { Authorization: 'Bearer t' }
      }),
      artwork: {},
      routes: [{ path: '__search__', searchConfig: { path: '/search' } }]
    }

    const [hit] = await new SearchManager(manager).search({
      query: 'hit',
      reference: 'unknown'
    })

    expect(hit?.artworkSource?.uri).toBe('https://api.example.com/art/hit.jpg')
    expect(hit?.artworkSource?.headers).toEqual({ Authorization: 'Bearer t' })
  })
})
