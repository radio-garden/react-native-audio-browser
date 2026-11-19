import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import type { NavigationErrorEvent } from '../../../features'
import type { Track } from '../../../types'
import type { NativeBrowserConfiguration } from '../../../types/browser-native'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserPathHelper } from '../../util/BrowserPathHelper'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

const httpError = (): Error =>
  Object.assign(new Error('HTTP 500: Internal Server Error'), {
    code: 'http-error',
    statusCode: 500
  })

const page = (path: string) => ({
  path,
  title: path,
  children: [{ title: 'Song', src: 'song.mp3' }]
})

/** A promise settled by the test, so a response can arrive after a later navigation. */
function deferred<T>(): {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (error: unknown) => void
} {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/**
 * A configured BrowserManager whose HTTP client answers each request with
 * `respond(path)`, recording every navigation error event.
 */
function makeManager(
  configuration: NativeBrowserConfiguration,
  respond: (path: string) => Promise<unknown> = () =>
    Promise.reject(new Error('unexpected request'))
): {
  manager: BrowserManager
  errors: NavigationErrorManager
  events: NavigationErrorEvent[]
} {
  const httpClient = {
    executeRequest: vi.fn(({ path }: { path: string }) => respond(path))
  } as unknown as HttpClient
  const errors = new NavigationErrorManager()
  const events: NavigationErrorEvent[] = []
  errors.onNavigationError = (event) => events.push(event)
  const manager = new BrowserManager(httpClient, new FavoriteManager(), errors)
  manager.configuration = configuration
  return { manager, errors, events }
}

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('a browse failure from a superseded navigation', () => {
  /** Each case navigates to `/a`, whose failure is held back until `fail()`, then to `/b`. */
  const cases: Record<
    string,
    () => { configuration: NativeBrowserConfiguration; fail: () => void } & {
      respond?: (path: string) => Promise<unknown>
    }
  > = {
    'a browseCallback returning an error': () => {
      const slow = deferred<{ error: string }>()
      return {
        configuration: {
          path: '/b',
          routes: [
            { path: '/a', browseCallback: () => slow.promise },
            { path: '/b', browseStatic: page('/b') }
          ]
        },
        fail: () => slow.resolve({ error: 'boom' })
      }
    },
    'a failed browseConfig request': () => {
      const slow = deferred<unknown>()
      return {
        configuration: {
          path: '/b',
          routes: [
            {
              path: '/a',
              browseConfig: { baseUrl: 'https://api.example.com' }
            },
            { path: '/b', browseStatic: page('/b') }
          ]
        },
        respond: () => slow.promise,
        fail: () => slow.reject(httpError())
      }
    },
    'a failed implicit default request': () => {
      const slow = deferred<unknown>()
      return {
        configuration: {
          path: '/b',
          browse: { baseUrl: 'https://api.example.com' }
        },
        respond: (path) =>
          path === '/a' ? slow.promise : Promise.resolve(page(path)),
        fail: () => slow.reject(httpError())
      }
    }
  }

  it.each(Object.keys(cases))('is not published: %s', async (name) => {
    const { configuration, respond, fail } = cases[name]!()
    const { manager, errors, events } = makeManager(configuration, respond)

    const superseded = manager.navigatePath('/a')
    await manager.navigatePath('/b')
    fail()
    await superseded

    expect(errors.getNavigationError()).toBeUndefined()
    expect(errors.getFormattedNavigationError()).toBeUndefined()
    expect(events).toEqual([])
    expect(manager.path).toBe('/b')
    expect(manager.content?.title).toBe('/b')
  })

  it.each(Object.keys(cases))(
    'is not published while the newer navigation is still resolving: %s',
    async (name) => {
      const { configuration, respond, fail } = cases[name]!()
      const { manager, errors, events } = makeManager(configuration, respond)

      const superseded = manager.navigatePath('/a')
      const current = manager.navigatePath('/b')
      fail()
      await superseded
      await current

      expect(errors.getNavigationError()).toBeUndefined()
      expect(events).toEqual([])
      expect(manager.content?.title).toBe('/b')
    }
  )
})

describe('a browse failure of the current navigation', () => {
  it('publishes a callback-error for a browseCallback returning an error', async () => {
    const { manager, errors } = makeManager({
      path: '/b',
      routes: [
        {
          path: '/a',
          browseCallback: () => Promise.resolve({ error: 'boom' })
        },
        { path: '/b', browseStatic: page('/b') }
      ]
    })

    await manager.navigatePath('/a')

    expect(errors.getNavigationError()).toEqual({
      code: 'callback-error',
      message: 'boom',
      statusCode: undefined,
      statusCodeSuccess: undefined
    })
    expect(manager.content).toBeUndefined()
  })

  it.each([
    [
      'a browseConfig route',
      {
        routes: [
          { path: '/a', browseConfig: { baseUrl: 'https://api.example.com' } }
        ]
      }
    ],
    ['the implicit default', { browse: { baseUrl: 'https://api.example.com' } }]
  ] satisfies [string, NativeBrowserConfiguration][])(
    'publishes the request error for %s',
    async (_name, configuration) => {
      const { manager, errors } = makeManager(
        { path: '/a', ...configuration },
        () => Promise.reject(httpError())
      )

      await manager.navigatePath('/a')

      expect(errors.getNavigationError()).toEqual({
        code: 'http-error',
        message: 'HTTP 500: Internal Server Error',
        statusCode: 500,
        statusCodeSuccess: undefined
      })
      expect(manager.content).toBeUndefined()
    }
  )

  it('still queries the tabs', async () => {
    const tabs: Track[] = [{ title: 'Home', path: '/a' }]
    const { manager } = makeManager(
      {
        path: '/a',
        routes: [
          {
            path: '__tabs__',
            browseStatic: { path: '/', title: 'Tabs', children: tabs }
          },
          { path: '/a', browseConfig: { baseUrl: 'https://api.example.com' } }
        ]
      },
      () => Promise.reject(httpError())
    )

    await manager.navigatePath('/a')

    expect(manager.tabs?.map((tab) => tab.title)).toEqual(['Home'])
  })
})

describe('a failed queue expansion', () => {
  it('leaves the navigation error unset and falls back to the single track', async () => {
    let failing = false
    const { manager, errors, events } = makeManager(
      { path: '/a', browse: { baseUrl: 'https://api.example.com' } },
      (path) =>
        failing ? Promise.reject(httpError()) : Promise.resolve(page(path))
    )
    await manager.navigatePath('/a')
    const track = manager.content!.sections![0]!.children[0]!
    expect(BrowserPathHelper.isContextual(track.path!)).toBe(true)

    failing = true
    const resolved = await manager.resolveMediaItemsForPlayback([track], 0, 0)

    expect(resolved.tracks).toEqual([track])
    expect(errors.getNavigationError()).toBeUndefined()
    expect(events).toEqual([])
    expect(manager.content?.title).toBe('/a')
  })
})

describe('a failed tabs query', () => {
  it('leaves the navigation error unset and the page displayed', async () => {
    const { manager, errors, events } = makeManager({
      path: '/a',
      routes: [
        {
          path: '__tabs__',
          browseCallback: () => Promise.resolve({ error: 'no tabs' })
        },
        { path: '/a', browseStatic: page('/a') }
      ]
    })

    await manager.navigatePath('/a')

    expect(errors.getNavigationError()).toBeUndefined()
    expect(events).toEqual([])
    expect(manager.content?.title).toBe('/a')
    expect(manager.tabs).toEqual([])
  })
})
