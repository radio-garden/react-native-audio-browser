import { describe, it, expect, vi } from 'vitest'
import type { Section, SectionQueue } from '../../../types'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserPathHelper } from '../../util/BrowserPathHelper'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

const station = (name: string) => ({ title: name, src: `${name}.mp3` })

/** `/home` holds a two-station preview of `/recent`, which holds three. */
function makeManager(
  queue: SectionQueue | undefined,
  path = '/recent'
): BrowserManager {
  const httpClient = {
    executeRequest: vi.fn().mockRejectedValue(new Error('no network in test'))
  } as unknown as HttpClient
  const manager = new BrowserManager(
    httpClient,
    new FavoriteManager(),
    new NavigationErrorManager()
  )
  const preview: Section = {
    title: 'Recent',
    path,
    queue,
    children: [station('a'), station('b')]
  }
  manager.configuration = {
    path: '/home',
    routes: [
      {
        path: '/home',
        browseStatic: { path: '/home', title: 'Home', sections: [preview] }
      },
      {
        path: '/recent',
        browseStatic: {
          path: '/recent',
          title: 'Recent',
          children: [station('a'), station('b'), station('c')]
        }
      }
    ]
  }
  return manager
}

async function tapSecond(manager: BrowserManager) {
  await manager.navigatePath('/home')
  const tapped = manager.content?.sections?.[0]?.children[1]?.path
  if (!tapped) throw new Error('no stamped path')
  return {
    tapped,
    expanded: await manager.expandQueueFromContextualPath(tapped)
  }
}

describe("a section's queue", () => {
  it("'path' queues the whole linked page from the tapped track", async () => {
    const { tapped, expanded } = await tapSecond(makeManager('path'))

    expect(expanded?.tracks.map((t) => t.src)).toEqual([
      'a.mp3',
      'b.mp3',
      'c.mp3'
    ])
    expect(expanded?.selectedIndex).toBe(1)
    // The linked page's own source, so a tap there skips in place.
    expect(BrowserPathHelper.stripTrackId(tapped)).toBe('/recent')
  })

  it("'path' with an empty path queues only the section itself", async () => {
    const { expanded } = await tapSecond(makeManager('path', ''))

    expect(expanded?.tracks.map((t) => t.src)).toEqual(['a.mp3', 'b.mp3'])
  })

  it('absent queues only the section itself', async () => {
    const { expanded } = await tapSecond(makeManager(undefined))

    expect(expanded?.tracks.map((t) => t.src)).toEqual(['a.mp3', 'b.mp3'])
  })
})
