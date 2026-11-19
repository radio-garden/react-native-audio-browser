import { afterEach, describe, it, expect, vi } from 'vitest'
import type { NavigationErrorEvent } from '../../../features'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('navigating to a path that matches no route', () => {
  it('raises content-not-found when no request or browse layer can fetch the path', async () => {
    const httpClient = { executeRequest: vi.fn() } as unknown as HttpClient
    const errors = new NavigationErrorManager()
    const events: NavigationErrorEvent[] = []
    errors.onNavigationError = (event) => events.push(event)
    const manager = new BrowserManager(
      httpClient,
      new FavoriteManager(),
      errors
    )
    manager.configuration = {
      routes: [
        {
          path: '/library',
          browseStatic: { path: '/library', title: 'Library', children: [] }
        }
      ]
    }
    vi.spyOn(console, 'error').mockImplementation(() => {})

    await manager.navigatePath('/typo')

    const error = {
      code: 'content-not-found',
      message: 'Content not found',
      statusCode: undefined,
      statusCodeSuccess: undefined
    }
    expect(errors.getNavigationError()).toEqual(error)
    expect(events).toEqual([{ error }])
    expect(errors.getFormattedNavigationError()).toEqual({
      title: 'Content Not Found',
      message: 'Content not found'
    })
    expect(manager.content).toBeUndefined()
  })
})

describe('configuring a browser with nothing to browse', () => {
  it('sets no navigation error', async () => {
    const httpClient = { executeRequest: vi.fn() } as unknown as HttpClient
    const errors = new NavigationErrorManager()
    const events: NavigationErrorEvent[] = []
    errors.onNavigationError = (event) => events.push(event)
    const manager = new BrowserManager(
      httpClient,
      new FavoriteManager(),
      errors
    )
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

    manager.configuration = { media: { headers: { 'x-key': 'value' } } }
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(errors.getNavigationError()).toBeUndefined()
    expect(events).toEqual([])
    expect(consoleError).not.toHaveBeenCalled()
  })
})
