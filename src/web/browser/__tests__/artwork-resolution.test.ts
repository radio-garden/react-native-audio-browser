import { describe, it, expect, vi } from 'vitest'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

function makeManager(): BrowserManager {
  const httpClient = {
    executeRequest: vi.fn().mockRejectedValue(new Error('no network in test'))
  } as unknown as HttpClient
  return new BrowserManager(
    httpClient,
    new FavoriteManager(),
    new NavigationErrorManager()
  )
}

/**
 * Who gets an `artworkSource`, and on whose say-so.
 *
 * Android gates this per *track* — `resolveArtworkUrl` returns early only when
 * `artworkConfig == null && track.artwork == null`, so a track carrying a plain
 * URL resolves to `{ uri }` with no `artwork` block configured at all. Web used
 * to gate a level up, bailing out of the whole page when `configuration.artwork`
 * was unset, which left every child's `artworkSource` undefined in any app that
 * never declared one — the example app among them.
 */
describe('artwork resolution without an artwork config', () => {
  it('populates artworkSource for a child carrying a plain artwork url', async () => {
    const manager = makeManager()
    manager.configuration = {
      path: '/',
      routes: [
        {
          path: '/library',
          browseStatic: {
            path: '/library',
            title: 'Library',
            sections: [
              {
                title: 'Collections',
                children: [
                  {
                    title: 'Cratediggers',
                    path: '/collection/cratediggers',
                    artwork: 'https://cdn.example.com/cratediggers.jpg'
                  }
                ]
              }
            ]
          }
        }
      ]
    }

    await manager.navigatePath('/library')

    const tile = manager.content?.sections?.[0]?.children[0]
    expect(tile?.artworkSource?.uri).toBe(
      'https://cdn.example.com/cratediggers.jpg'
    )
  })

  it('leaves artworkSource undefined for a child with no artwork', async () => {
    const manager = makeManager()
    manager.configuration = {
      path: '/',
      routes: [
        {
          path: '/library',
          browseStatic: {
            path: '/library',
            title: 'Library',
            sections: [
              {
                title: 'Collections',
                children: [{ title: 'Bare', path: '/collection/bare' }]
              }
            ]
          }
        }
      ]
    }

    await manager.navigatePath('/library')

    const tile = manager.content?.sections?.[0]?.children[0]
    expect(tile?.artworkSource).toBeUndefined()
  })

  it('treats an empty artwork string as no artwork, as Android does', async () => {
    const manager = makeManager()
    manager.configuration = {
      path: '/',
      routes: [
        {
          path: '/library',
          browseStatic: {
            path: '/library',
            title: 'Library',
            sections: [
              {
                title: 'Collections',
                children: [{ title: 'Empty', path: '/x', artwork: '' }]
              }
            ]
          }
        }
      ]
    }

    await manager.navigatePath('/library')

    const tile = manager.content?.sections?.[0]?.children[0]
    expect(tile?.artworkSource).toBeUndefined()
  })
})
