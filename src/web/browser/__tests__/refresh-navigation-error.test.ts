import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import type { NavigationErrorEvent } from '../../../features'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

const codedError = (code: string, message: string): Error =>
  Object.assign(new Error(message), { code })

/**
 * A BrowserManager on `/x` whose first load failed with a `network-error`.
 * `server.respond` answers the requests that follow.
 */
async function makeManagerWithFailedPage(): Promise<{
  manager: BrowserManager
  errors: NavigationErrorManager
  events: NavigationErrorEvent[]
  server: { respond: () => Promise<unknown> }
}> {
  const server = {
    respond: (): Promise<unknown> =>
      Promise.reject(codedError('network-error', 'Failed to fetch'))
  }
  const httpClient = {
    executeRequest: vi.fn(() => server.respond())
  } as unknown as HttpClient
  const errors = new NavigationErrorManager()
  const manager = new BrowserManager(httpClient, new FavoriteManager(), errors)
  manager.configuration = {
    path: '/x',
    browse: { baseUrl: 'https://api.example.com' }
  }
  await manager.navigatePath('/x')
  expect(errors.getNavigationError()?.code).toBe('network-error')

  const events: NavigationErrorEvent[] = []
  errors.onNavigationError = (event) => events.push(event)
  return { manager, errors, events, server }
}

const refreshes: [string, (manager: BrowserManager) => void][] = [
  ['invalidateAllContent', (manager) => manager.invalidateAllContent()],
  ['notifyContentChanged', (manager) => manager.notifyContentChanged('/x')]
]

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('refreshing a page whose navigation failed', () => {
  it.each(refreshes)(
    'clears the navigation error when %s reloads the page',
    async (_name, refresh) => {
      const { manager, errors, events, server } =
        await makeManagerWithFailedPage()
      server.respond = () =>
        Promise.resolve({
          path: '/x',
          title: 'Page',
          children: [{ title: 'Song', src: 'song.mp3' }]
        })

      refresh(manager)
      await vi.waitFor(() => expect(manager.content?.title).toBe('Page'))

      expect(errors.getNavigationError()).toBeUndefined()
      expect(errors.getFormattedNavigationError()).toBeUndefined()
      expect(events).toEqual([{ error: undefined }])
    }
  )

  it.each(refreshes)(
    'replaces the navigation error when the reload by %s fails',
    async (_name, refresh) => {
      const { manager, errors, events, server } =
        await makeManagerWithFailedPage()
      server.respond = () =>
        Promise.reject(
          Object.assign(codedError('http-error', 'HTTP 500'), {
            statusCode: 500
          })
        )

      refresh(manager)
      await vi.waitFor(() =>
        expect(errors.getNavigationError()?.code).toBe('http-error')
      )

      expect(events.map((event) => event.error?.code)).toEqual([
        undefined,
        'http-error'
      ])
      expect(manager.content).toBeUndefined()
    }
  )
})
