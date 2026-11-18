import { describe, it, expect, vi } from 'vitest'
import type { NativeBrowserConfiguration } from '../../types/browser-native'
import type { HttpClient } from '../http/HttpClient'
import { BrowserManager } from './BrowserManager'
import { FavoriteManager } from './FavoriteManager'
import { NavigationErrorManager } from './NavigationErrorManager'
import { SearchManager } from './SearchManager'

/**
 * Builds a SearchManager over a BrowserManager whose HTTP client answers every
 * request with `respond` — a resolved body or a rejection.
 */
function makeSearchManager(
  respond: () => Promise<unknown>,
  route: NonNullable<NativeBrowserConfiguration['routes']>[number] = {
    path: '__search__',
    searchConfig: { baseUrl: 'https://api.example.com' }
  }
): SearchManager {
  const httpClient = {
    executeRequest: vi.fn().mockImplementation(respond)
  } as unknown as HttpClient
  const browserManager = new BrowserManager(
    httpClient,
    new FavoriteManager(),
    new NavigationErrorManager()
  )
  browserManager.configuration = { routes: [route] }
  return new SearchManager(browserManager)
}

const httpError = (): Error => {
  const error = new Error('HTTP 500: Internal Server Error') as Error & {
    code: string
    statusCode: number
  }
  error.code = 'http-error'
  error.statusCode = 500
  return error
}

describe('SearchManager', () => {
  it('rejects when the search request fails', async () => {
    const manager = makeSearchManager(() => Promise.reject(httpError()))
    await expect(
      manager.search({ query: 'jazz', reference: 'unknown' })
    ).rejects.toThrow('HTTP 500: Internal Server Error')
  })

  it('resolves [] when the search found nothing', async () => {
    const manager = makeSearchManager(() => Promise.resolve({ children: [] }))
    await expect(
      manager.search({ query: 'jazz', reference: 'unknown' })
    ).resolves.toEqual([])
  })

  it('resolves the matching tracks', async () => {
    const manager = makeSearchManager(() =>
      Promise.resolve({ children: [{ title: 'Jazz FM', src: 'https://s/1' }] })
    )
    await expect(
      manager.search({ query: 'jazz', reference: 'unknown' })
    ).resolves.toEqual([{ title: 'Jazz FM', src: 'https://s/1' }])
  })

  it('propagates a rejecting search callback', async () => {
    const manager = makeSearchManager(() => Promise.resolve([]), {
      path: '__search__',
      searchCallback: () => Promise.reject(new Error('callback exploded'))
    })
    await expect(
      manager.search({ query: 'jazz', reference: 'unknown' })
    ).rejects.toThrow('callback exploded')
  })
})
