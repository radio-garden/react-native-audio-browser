import { describe, it, expect, vi } from 'vitest'
import type { FormattedNavigationError } from '../../../features'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

/**
 * A BrowserManager whose only route is a `searchConfig`, with its HTTP client
 * answering every request with `respond`.
 */
function makeManager(respond: () => Promise<unknown>): {
  manager: BrowserManager
  errors: NavigationErrorManager
  formattedPaths: string[]
} {
  const httpClient = {
    executeRequest: vi.fn().mockImplementation(respond)
  } as unknown as HttpClient
  const errors = new NavigationErrorManager()
  const manager = new BrowserManager(httpClient, new FavoriteManager(), errors)
  const formattedPaths: string[] = []
  manager.configuration = {
    routes: [
      {
        path: '__search__',
        searchConfig: { baseUrl: 'https://api.example.com' }
      }
    ],
    formatNavigationError: ({ path }): FormattedNavigationError => {
      formattedPaths.push(path)
      return { title: 'Search unavailable' }
    }
  }
  return { manager, errors, formattedPaths }
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

describe('navigating to a search path', () => {
  it('surfaces a navigation error when the search fails', async () => {
    const { manager, errors, formattedPaths } = makeManager(() =>
      Promise.reject(httpError())
    )

    await manager.navigatePath('/__search?q=jazz')

    expect(errors.getNavigationError()).toEqual({
      code: 'http-error',
      message: 'HTTP 500: Internal Server Error',
      statusCode: 500,
      statusCodeSuccess: undefined
    })
    expect(formattedPaths).toEqual(['/__search?q=jazz'])
    expect(manager.content).toBeUndefined()
  })

  it('resolves an empty page without an error when the search found nothing', async () => {
    const { manager, errors } = makeManager(() =>
      Promise.resolve({ children: [] })
    )

    await manager.navigatePath('/__search?q=jazz')

    expect(errors.getNavigationError()).toBeUndefined()
    expect(manager.content?.sections).toEqual([{ children: [] }])
  })
})
