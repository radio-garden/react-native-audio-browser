import { describe, it, expect, vi } from 'vitest'
import type { Section, SectionQueue } from '../../../types'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserPathHelper } from '../../util/BrowserPathHelper'
import { BrowserManager, DisabledOnLinkedPageError } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

const station = (name: string, disabled?: boolean) => ({
  title: name,
  src: `${name}.mp3`,
  disabled
})

/**
 * `/home` holds a two-station preview of `/recent`, which holds `recent`, or
 * fails to resolve when it is null; it disables the stations in `disabled`.
 */
function makeManager(
  queue: SectionQueue | undefined,
  path = '/recent',
  recent: string[] | null = ['a', 'b', 'c'],
  disabled: string[] = []
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
      ...(recent
        ? [
            {
              path: '/recent',
              browseStatic: {
                path: '/recent',
                title: 'Recent',
                children: recent.map((name) =>
                  station(name, disabled.includes(name) || undefined)
                )
              }
            }
          ]
        : [])
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

  it("'path' queues the preview itself, from its page, when the linked page fails", async () => {
    const { expanded } = await tapSecond(makeManager('path', '/recent', null))

    expect(expanded?.tracks.map((t) => t.src)).toEqual(['a.mp3', 'b.mp3'])
    expect(expanded?.selectedIndex).toBe(1)
    expect(expanded?.sourcePath).toBe('/home')
  })

  it("'path' queues the preview itself when the linked page lost the track", async () => {
    const { expanded } = await tapSecond(
      makeManager('path', '/recent', ['a', 'c'])
    )

    expect(expanded?.tracks.map((t) => t.src)).toEqual(['a.mp3', 'b.mp3'])
    expect(expanded?.selectedIndex).toBe(1)
  })

  it("'path' refuses a track the linked page disables instead of queueing the preview", async () => {
    const manager = makeManager('path', '/recent', ['a', 'b', 'c'], ['b'])
    await manager.navigatePath('/home')
    const tapped = manager.content?.sections?.[0]?.children[1]?.path ?? ''

    await expect(
      manager.expandQueueFromContextualPath(tapped)
    ).rejects.toBeInstanceOf(DisabledOnLinkedPageError)
  })

  it("'path' queues the linked page's section that holds the track enabled", async () => {
    const manager = makeManager('path', '/recent', ['b', 'c'], ['b'])
    const recent = manager.configuration.routes?.[1]?.browseStatic
    if (!recent?.children) throw new Error('no /recent')
    recent.sections = [
      { children: recent.children },
      { children: [station('b'), station('d')] }
    ]
    delete recent.children

    const { expanded } = await tapSecond(manager)

    expect(expanded?.tracks.map((t) => t.src)).toEqual(['b.mp3', 'd.mp3'])
    expect(expanded?.selectedIndex).toBe(0)
  })

  it('a stale stamp queues the section that holds the track enabled', async () => {
    const manager = makeManager(undefined)
    manager.configuration = {
      path: '/page',
      routes: [
        {
          path: '/page',
          browseStatic: {
            path: '/page',
            title: 'Page',
            sections: [
              { children: [station('x', true)] },
              { children: [station('x'), station('y')] }
            ]
          }
        }
      ]
    }

    const expanded = await manager.expandQueueFromContextualPath(
      BrowserPathHelper.build('/page', 'x.mp3', 2)
    )

    expect(expanded?.tracks.map((t) => t.src)).toEqual(['x.mp3', 'y.mp3'])
    expect(expanded?.selectedIndex).toBe(0)
  })

  it('after a fallback, a tap in the preview queues the linked page again', async () => {
    const offline = makeManager('path', '/recent', null)
    const { tapped } = await tapSecond(offline)
    const track = { title: 'b', src: 'b.mp3', path: tapped }
    await offline.resolveMediaItemsForPlayback([track], 0, 0)
    // A tap skips in place only when its parent is the queue's source.
    expect(offline.queueSourcePath).toBe('/home')
    expect(BrowserPathHelper.stripTrackId(tapped)).not.toBe('/home')

    const online = makeManager('path')
    await online.navigatePath('/home')
    const again = await online.resolveMediaItemsForPlayback([track], 0, 0)
    expect(again.tracks.map((t) => t.src)).toEqual(['a.mp3', 'b.mp3', 'c.mp3'])
    expect(online.queueSourcePath).toBe('/recent')
  })

  it('absent queues only the section itself', async () => {
    const { expanded } = await tapSecond(makeManager(undefined))

    expect(expanded?.tracks.map((t) => t.src)).toEqual(['a.mp3', 'b.mp3'])
  })
})
