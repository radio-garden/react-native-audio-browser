import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import type { NativeBrowserConfiguration } from '../../../types/browser-native'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserPathHelper } from '../../util/BrowserPathHelper'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

/** An error as an HTTP library throws it: an Error with a `code` of the library's own. */
const libraryError = (): Error =>
  Object.assign(new Error('Network Error'), { code: 'ERR_NETWORK' })

/** A configured BrowserManager whose HTTP client rejects every request with `requestError`. */
function makeManager(
  configuration: NativeBrowserConfiguration,
  requestError: unknown = new Error('unexpected request')
): { manager: BrowserManager; errors: NavigationErrorManager } {
  const httpClient = {
    executeRequest: vi.fn(() => Promise.reject(requestError))
  } as unknown as HttpClient
  const errors = new NavigationErrorManager()
  const manager = new BrowserManager(httpClient, new FavoriteManager(), errors)
  manager.configuration = configuration
  return { manager, errors }
}

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('a failure whose error carries a code that is not a navigation error code', () => {
  const searchPath = BrowserPathHelper.createSearchPath('jazz')

  const cases: Record<
    string,
    () => {
      path: string
      configuration: NativeBrowserConfiguration
      requestError?: unknown
    }
  > = {
    'a browseCallback rejecting with a DOMException': () => ({
      path: '/a',
      configuration: {
        routes: [
          {
            path: '/a',
            browseCallback: () =>
              Promise.reject(new DOMException('aborted', 'AbortError'))
          }
        ]
      }
    }),
    'a searchCallback rejecting with a library error': () => ({
      path: searchPath,
      configuration: {
        routes: [
          {
            path: '__search__',
            searchCallback: () => Promise.reject(libraryError())
          }
        ]
      }
    }),
    'a browseConfig request rejecting with a library error': () => ({
      path: '/a',
      configuration: {
        routes: [
          { path: '/a', browseConfig: { baseUrl: 'https://api.example.com' } }
        ]
      },
      requestError: libraryError()
    }),
    'the implicit default request rejecting with a DOMException': () => ({
      path: '/a',
      configuration: { browse: { baseUrl: 'https://api.example.com' } },
      requestError: new DOMException('aborted', 'AbortError')
    })
  }

  it.each(Object.keys(cases))(
    'is published as a network-error: %s',
    async (name) => {
      const { path, configuration, requestError } = cases[name]!()
      const { manager, errors } = makeManager(configuration, requestError)

      await manager.navigatePath(path)

      expect(errors.getNavigationError()).toEqual({
        code: 'network-error',
        message: 'Failed to load content',
        statusCode: undefined,
        statusCodeSuccess: undefined
      })
      expect(errors.getFormattedNavigationError()).toEqual({
        title: 'Network Error',
        message: 'Failed to load content'
      })
    }
  )
})
