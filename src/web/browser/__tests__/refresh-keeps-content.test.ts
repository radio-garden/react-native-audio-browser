import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import type { ResolvedTrack } from '../../../types'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

const page = (path: string, title: string) => ({
  path,
  title,
  children: [{ title: 'Song', src: 'song.mp3' }]
})

/**
 * A BrowserManager showing `/x`, titled 'Old'. `server.respond` answers the
 * requests that follow; `contents` records every `onContentChanged` after the
 * first page.
 */
async function makeManagerShowingPage(): Promise<{
  manager: BrowserManager
  errors: NavigationErrorManager
  contents: (ResolvedTrack | undefined)[]
  server: { respond: (path: string) => Promise<unknown> }
}> {
  const server = {
    respond: (path: string): Promise<unknown> =>
      Promise.resolve(page(path, 'Old'))
  }
  const httpClient = {
    executeRequest: vi.fn((request: { path: string }) =>
      server.respond(request.path)
    )
  } as unknown as HttpClient
  const errors = new NavigationErrorManager()
  const manager = new BrowserManager(httpClient, new FavoriteManager(), errors)
  manager.configuration = {
    path: '/x',
    browse: { baseUrl: 'https://api.example.com' }
  }
  await vi.waitFor(() => expect(manager.content?.title).toBe('Old'))

  const contents: (ResolvedTrack | undefined)[] = []
  manager.onContentChanged = (content) => contents.push(content)
  return { manager, errors, contents, server }
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

describe('refreshing the page on screen', () => {
  it.each(refreshes)(
    'keeps the content until %s has the new page',
    async (_name, refresh) => {
      const { manager, contents, server } = await makeManagerShowingPage()
      let answer: (value: unknown) => void = () => {}
      const reply = new Promise((resolve) => (answer = resolve))
      let asked = false
      server.respond = () => {
        asked = true
        return reply
      }

      refresh(manager)
      await vi.waitFor(() => expect(asked).toBe(true))
      expect(manager.content?.title).toBe('Old')

      answer(page('/x', 'New'))
      await vi.waitFor(() => expect(manager.content?.title).toBe('New'))
      expect(contents.map((content) => content?.title)).toEqual(['New'])
    }
  )

  it.each(refreshes)(
    'keeps the content when the reload by %s fails',
    async (_name, refresh) => {
      const { manager, errors, contents, server } =
        await makeManagerShowingPage()
      server.respond = () =>
        Promise.reject(
          Object.assign(new Error('Failed to fetch'), { code: 'network-error' })
        )

      refresh(manager)
      await vi.waitFor(() =>
        expect(errors.getNavigationError()?.code).toBe('network-error')
      )

      expect(manager.content?.title).toBe('Old')
      expect(contents).toEqual([])
    }
  )
})

describe('refreshing a page whose route handler throws', () => {
  it.each(refreshes)('keeps the content through %s', async (_name, refresh) => {
    let fail = false
    const errors = new NavigationErrorManager()
    const manager = new BrowserManager(
      {} as HttpClient,
      new FavoriteManager(),
      errors
    )
    manager.configuration = {
      path: '/x',
      routes: [
        {
          path: '/x',
          browseCallback: async () => {
            if (fail) throw new Error('Handler failed')
            return page('/x', 'Old')
          }
        }
      ]
    }
    await vi.waitFor(() => expect(manager.content?.title).toBe('Old'))
    const contents: (ResolvedTrack | undefined)[] = []
    manager.onContentChanged = (content) => contents.push(content)

    fail = true
    refresh(manager)
    await vi.waitFor(() => expect(errors.getNavigationError()).toBeDefined())

    expect(manager.content?.title).toBe('Old')
    expect(contents).toEqual([])
  })
})

describe('navigating to another path', () => {
  it('clears the content until the new page resolves', async () => {
    const { manager, contents } = await makeManagerShowingPage()

    const navigation = manager.navigatePath('/y')
    expect(manager.content).toBeUndefined()

    await navigation
    expect(contents.map((content) => content?.path)).toEqual([undefined, '/y'])
  })
})
