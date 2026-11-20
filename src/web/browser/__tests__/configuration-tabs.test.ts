import { describe, it, expect, vi } from 'vitest'
import type { ResolvedTrack, Track } from '../../../types'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

const explore = { title: 'Explore', path: '/explore' }

const page = (path: string): ResolvedTrack => ({
  path,
  title: path,
  children: []
})

function makeManager(): { manager: BrowserManager; emitted: Track[][] } {
  const httpClient = {
    executeRequest: vi.fn()
  } as unknown as HttpClient
  const manager = new BrowserManager(
    httpClient,
    new FavoriteManager(),
    new NavigationErrorManager()
  )
  const emitted: Track[][] = []
  manager.onTabsChanged = (tabs) => emitted.push(tabs)
  return { manager, emitted }
}

describe('configuring the browser', () => {
  it('clears the tabs when the new configuration has none', async () => {
    const { manager, emitted } = makeManager()
    manager.configuration = {
      routes: [
        {
          path: '__tabs__',
          browseStatic: { path: '__tabs__', title: 'Tabs', children: [explore] }
        },
        { path: '/explore', browseStatic: page('/explore') }
      ]
    }
    await vi.waitFor(() => expect(manager.tabs).toHaveLength(1))
    emitted.length = 0

    manager.configuration = {
      routes: [{ path: '/', browseStatic: page('/') }]
    }

    expect(manager.tabs).toBeUndefined()
    expect(emitted).toEqual([[]])
  })

  it('emits nothing when there were no tabs before or after', () => {
    const { manager, emitted } = makeManager()

    manager.configuration = {
      routes: [{ path: '/', browseStatic: page('/') }]
    }

    expect(manager.tabs).toBeUndefined()
    expect(emitted).toEqual([])
  })
})
