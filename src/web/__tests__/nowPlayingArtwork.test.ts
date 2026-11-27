/** @vitest-environment happy-dom */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Track } from '../../types'
import { LifecycleHarness } from './lifecycleHarness'

/**
 * The artwork the OS media controls show goes through the `artwork` config, as
 * on iOS and Android, rather than being the track's raw `artwork`.
 */

class FakeMediaMetadata {
  artwork: { src: string }[]
  constructor(init: { artwork?: { src: string }[] }) {
    this.artwork = init.artwork ?? []
  }
}

const session = {
  metadata: null as FakeMediaMetadata | null,
  playbackState: 'none',
  setActionHandler: () => {},
  setPositionState: () => {}
}

function sessionArtwork(): string | undefined {
  return session.metadata?.artwork[0]?.src
}

let h: LifecycleHarness

beforeEach(async () => {
  session.metadata = null
  vi.stubGlobal('navigator', { mediaSession: session })
  vi.stubGlobal('MediaMetadata', FakeMediaMetadata)
  h = new LifecycleHarness()
  await h.setup()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const relative: Track = {
  id: '7',
  title: 'Seven',
  src: 'https://cdn.example.com/7.mp3',
  artwork: '/art/7.jpg'
}

describe('now-playing artwork on the OS media controls', () => {
  it('resolves the artwork through the artwork config', async () => {
    h.browser.configuration = {
      artwork: {
        baseUrl: 'https://img.example.com',
        query: { v: '2' },
        imageQueryParams: { width: 'w' }
      }
    }

    h.browser.load(relative)
    await h.flush()

    expect(sessionArtwork()).toBe(
      'https://img.example.com/art/7.jpg?v=2&w=1200'
    )
  })

  it('resolves the artwork through nowPlayingArtwork when it is set', async () => {
    h.browser.configuration = {
      artwork: { baseUrl: 'https://img.example.com' },
      nowPlayingArtwork: {
        baseUrl: 'https://np.example.com',
        path: '/artwork/{id}'
      }
    }

    h.browser.load(relative)
    await h.flush()

    expect(sessionArtwork()).toBe('https://np.example.com/artwork/7')
  })

  it("runs the artwork config's resolve and transform", async () => {
    h.browser.configuration = {
      artwork: {
        resolve: (track) =>
          Promise.resolve({ path: `/covers/${track.id}.png` }),
        transform: ({ request }) =>
          Promise.resolve({ ...request, baseUrl: 'https://signed.example.com' })
      }
    }

    h.browser.load(relative)
    await h.flush()

    expect(sessionArtwork()).toBe('https://signed.example.com/covers/7.png')
  })

  it('keeps the resolved artwork when an override changes the title', async () => {
    h.browser.configuration = {
      artwork: { baseUrl: 'https://img.example.com' }
    }
    h.browser.load(relative)
    await h.flush()

    h.browser.updateNowPlaying({ title: 'Live title' })

    expect(sessionArtwork()).toBe('https://img.example.com/art/7.jpg')
  })

  it("does not show a track's artwork once a later track has replaced it", async () => {
    let releaseFirst: () => void = () => {}
    h.browser.configuration = {
      artwork: {
        baseUrl: 'https://img.example.com',
        resolve: (track) =>
          track.id === '7'
            ? new Promise((resolve) => {
                releaseFirst = () => resolve({})
              })
            : Promise.resolve({})
      }
    }

    h.browser.load(relative)
    await h.flush()
    h.browser.load({ ...relative, id: '8', artwork: '/art/8.jpg' })
    await h.flush()
    releaseFirst()
    await h.flush()

    expect(sessionArtwork()).toBe('https://img.example.com/art/8.jpg')
  })

  it('shows no artwork for a new track until its own has resolved', async () => {
    let releaseSecond: () => void = () => {}
    h.browser.configuration = {
      artwork: {
        baseUrl: 'https://img.example.com',
        resolve: (track) =>
          track.id === '8'
            ? new Promise((resolve) => {
                releaseSecond = () => resolve({})
              })
            : Promise.resolve({})
      }
    }
    h.browser.load(relative)
    await h.flush()

    h.browser.load({ ...relative, id: '8', artwork: '/art/8.jpg' })
    await h.flush()
    expect(sessionArtwork()).toBeUndefined()

    releaseSecond()
    await h.flush()
    expect(sessionArtwork()).toBe('https://img.example.com/art/8.jpg')
  })

  it('shows no artwork when the request layer fails to resolve', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    h.browser.configuration = {
      requestResolver: () => Promise.reject(new Error('token refresh failed')),
      artwork: {}
    }

    h.browser.load(relative)
    await h.flush()

    expect(session.metadata).not.toBeNull()
    expect(sessionArtwork()).toBeUndefined()
  })

  it('resolves the artwork when the browse layer fails to resolve', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    h.browser.configuration = {
      requestResolver: () =>
        Promise.resolve({ baseUrl: 'https://img.example.com' }),
      browseResolver: () => Promise.reject(new Error('token refresh failed')),
      artwork: {}
    }

    h.browser.load(relative)
    await h.flush()

    expect(sessionArtwork()).toBe('https://img.example.com/art/7.jpg')
  })

  it('resolves the artwork while the browse layer has yet to resolve', async () => {
    h.browser.configuration = {
      requestResolver: () =>
        Promise.resolve({ baseUrl: 'https://img.example.com' }),
      browseResolver: () => new Promise(() => {}),
      artwork: {}
    }

    h.browser.load(relative)
    await h.flush()

    expect(sessionArtwork()).toBe('https://img.example.com/art/7.jpg')
  })

  it("falls back to the track's artworkSource when its artwork resolves to nothing", async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    h.browser.configuration = {
      artwork: {
        transform: () => Promise.reject(new Error('signing failed'))
      }
    }

    h.browser.load({
      ...relative,
      artworkSource: { uri: 'https://img.example.com/supplied.jpg' }
    })
    await h.flush()

    expect(sessionArtwork()).toBe('https://img.example.com/supplied.jpg')
  })
})
