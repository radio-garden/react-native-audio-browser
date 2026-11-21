import { describe, it, expect, vi } from 'vitest'
import type { NativeBrowserConfiguration } from '../../../types/browser-native'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

async function navigate(
  configuration: NativeBrowserConfiguration,
  path: string
): Promise<{
  error: ReturnType<NavigationErrorManager['getNavigationError']>
  requests: number
}> {
  const executeRequest = vi
    .fn()
    .mockRejectedValue(new Error('no network in test'))
  const errors = new NavigationErrorManager()
  const manager = new BrowserManager(
    { executeRequest } as unknown as HttpClient,
    new FavoriteManager(),
    errors
  )
  manager.configuration = configuration
  await manager.navigatePath(path)
  return {
    error: errors.getNavigationError(),
    requests: executeRequest.mock.calls.length
  }
}

describe('a JS callback that fails', () => {
  it('fails the navigation with callback-error when a browse callback rejects', async () => {
    const { error } = await navigate(
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
    const { error } = await navigate(
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
    const { error } = await navigate(
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
    const { error } = await navigate(
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
    const { error } = await navigate(
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

  it('fails the navigation with callback-error when a browse transform throws', async () => {
    const { error, requests } = await navigate(
      {
        routes: [
          {
            path: '/a',
            browseConfig: {
              baseUrl: 'https://api.example.com',
              transform: async () => {
                throw new Error('token expired')
              }
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
    expect(requests).toBe(0)
  })

  it('fails the navigation with callback-error when a search transform throws', async () => {
    const { error, requests } = await navigate(
      {
        routes: [
          {
            path: '__search__',
            searchConfig: {
              baseUrl: 'https://api.example.com',
              transformSync: () => {
                throw new Error('token expired')
              }
            }
          }
        ]
      },
      '/__search?q=jazz'
    )

    expect(error).toMatchObject({
      code: 'callback-error',
      message: 'token expired'
    })
    expect(requests).toBe(0)
  })

  it('fails the navigation with callback-error when the shared request transform throws', async () => {
    const { error, requests } = await navigate(
      {
        request: {
          transform: async () => {
            throw new Error('token expired')
          }
        },
        routes: [
          { path: '/a', browseConfig: { baseUrl: 'https://api.example.com' } }
        ]
      },
      '/a'
    )

    expect(error).toMatchObject({
      code: 'callback-error',
      message: 'token expired'
    })
    expect(requests).toBe(0)
  })
})
