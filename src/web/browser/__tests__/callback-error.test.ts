import { describe, it, expect, vi } from 'vitest'
import type { NativeBrowserConfiguration } from '../../../types/browser-native'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

function navigationErrorOf(
  configuration: NativeBrowserConfiguration,
  path: string
): Promise<ReturnType<NavigationErrorManager['getNavigationError']>> {
  const httpClient = {
    executeRequest: vi.fn().mockRejectedValue(new Error('no network in test'))
  } as unknown as HttpClient
  const errors = new NavigationErrorManager()
  const manager = new BrowserManager(httpClient, new FavoriteManager(), errors)
  manager.configuration = configuration
  return manager.navigatePath(path).then(() => errors.getNavigationError())
}

describe('a JS callback that fails', () => {
  it('fails the navigation with callback-error when a browse callback rejects', async () => {
    const error = await navigationErrorOf(
      {
        routes: [
          {
            path: '/a',
            browseCallback: () =>
              Promise.reject(new Error('Simulated thrown error'))
          }
        ]
      },
      '/a'
    )

    expect(error).toMatchObject({
      code: 'callback-error',
      message: 'Simulated thrown error'
    })
  })

  it('fails the navigation with callback-error when a browse callback throws', async () => {
    const error = await navigationErrorOf(
      {
        routes: [
          {
            path: '/a',
            browseCallback: () => {
              throw new TypeError('token expired')
            }
          }
        ]
      },
      '/a'
    )

    expect(error).toMatchObject({
      code: 'callback-error',
      message: 'token expired'
    })
  })

  it('fails the navigation with callback-error when a search callback rejects', async () => {
    const error = await navigationErrorOf(
      {
        routes: [
          {
            path: '__search__',
            searchCallback: () => Promise.reject(new Error('search is down'))
          }
        ]
      },
      '/__search?q=jazz'
    )

    expect(error).toMatchObject({
      code: 'callback-error',
      message: 'search is down'
    })
  })

  it('fails the navigation with callback-error when a request resolver rejects', async () => {
    const error = await navigationErrorOf(
      {
        requestResolver: () => Promise.reject(new Error('signed out'))
      },
      '/a'
    )

    expect(error).toMatchObject({
      code: 'callback-error',
      message: 'signed out'
    })
  })

  it('fails the navigation with callback-error when a browse resolver throws a non-Error', async () => {
    const error = await navigationErrorOf(
      {
        browseResolver: () => {
          throw 'signed out'
        }
      },
      '/a'
    )

    expect(error).toMatchObject({
      code: 'callback-error',
      message: 'signed out'
    })
  })
})
