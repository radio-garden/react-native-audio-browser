/** @vitest-environment happy-dom */

import { beforeEach, describe, expect, it } from 'vitest'
import type { FavoriteChangedEvent } from '../../features/favorites'
import type { Track } from '../../types'
import { deferredResolver, LifecycleHarness } from './lifecycleHarness'

let h: LifecycleHarness
let favoriteEvents: FavoriteChangedEvent[]

const track: Track = {
  id: 'a',
  title: 'A',
  src: 'https://x.example.com/a.mp3'
}

beforeEach(async () => {
  h = new LifecycleHarness()
  await h.setup()
  favoriteEvents = []
  h.browser.onFavoriteChanged = (event) => favoriteEvents.push(event)
})

async function playTrack(): Promise<void> {
  h.browser.load(track)
  await h.flush()
  await h.completeLoad()
  h.clear()
}

describe('favoriting the active track', () => {
  it('shows on getActiveTrack() and in the queue, without a track change', async () => {
    await playTrack()

    h.browser.setActiveTrackFavorited(true)

    const active = h.browser.getActiveTrack()
    expect(active?.favorited).toBe(true)
    expect(h.browser.getQueue()[0]).toBe(active)
    expect(favoriteEvents).toEqual([{ track: active, favorited: true }])
    expect(h.recorded).not.toContain('activeTrack')
  })

  it('toggles back and forth on getActiveTrack()', async () => {
    await playTrack()

    h.browser.toggleActiveTrackFavorited()
    expect(h.browser.getActiveTrack()?.favorited).toBe(true)

    h.browser.toggleActiveTrackFavorited()
    expect(h.browser.getActiveTrack()?.favorited).toBe(false)
  })

  it('survives a load that was still resolving its url', async () => {
    const resolver = deferredResolver()
    h.browser.configuration = resolver.configuration

    h.browser.load(track)
    await h.flush()
    h.browser.setActiveTrackFavorited(true)
    resolver.release(1)
    await h.flush()
    await h.completeLoad()

    const active = h.browser.getActiveTrack()
    expect(active?.favorited).toBe(true)
    expect(h.browser.getQueue()[0]).toBe(active)
  })
})

describe('re-preparing a track favorited while active', () => {
  async function failAt(seconds: number): Promise<void> {
    h.setPosition(seconds)
    h.failLoad()
    h.raiseError()
    await h.flush()
    h.succeedLoads()
  }

  it('after a failure is not a track change, and resumes with its now-playing override', async () => {
    await playTrack()
    h.browser.updateNowPlaying({ title: 'Live show' })
    await failAt(42)
    h.browser.setActiveTrackFavorited(true)
    h.clear()

    h.browser.retry()
    await h.flush()
    await h.completeLoad()

    expect(h.recorded).not.toContain('activeTrack')
    expect(h.browser.getNowPlaying()?.title).toBe('Live show')
    expect(h.currentTime).toBe(42)
  })

  it('after a stop is not a track change', async () => {
    await playTrack()
    h.browser.setActiveTrackFavorited(true)
    h.browser.stop()
    await h.flush()
    h.clear()

    h.browser.play()
    await h.flush()
    await h.completeLoad()

    expect(h.recorded).not.toContain('activeTrack')
  })
})
