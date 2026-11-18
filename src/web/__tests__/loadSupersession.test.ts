/** @vitest-environment happy-dom */

import { beforeEach, describe, expect, it } from 'vitest'
import type { Track } from '../../types'
import {
  LifecycleHarness,
  deferredResolver,
  trackA,
  trackB
} from './lifecycleHarness'

/**
 * What happens to a load that something else takes over from — a newer load,
 * or a stop. Written before the two generation counters (`Player._loadGeneration`
 * and `NativeAudioBrowser.currentLoadId`) were unified into one cancellation
 * token, so they describe the behaviour rather than either mechanism.
 */

let h: LifecycleHarness

beforeEach(async () => {
  h = new LifecycleHarness()
  await h.setup()
})

describe('a load superseded mid-resolution', () => {
  it('never hands the stale url to the player', async () => {
    const resolver = deferredResolver()
    h.browser.configuration = resolver.configuration

    h.browser.load(trackA)
    await h.flush()
    h.browser.load(trackB)
    await h.flush()
    expect(resolver.calls()).toBe(2)

    // the winner resolves first, then the superseded one arrives late
    resolver.release(2)
    await h.flush()
    resolver.release(1)
    await h.flush()

    expect(h.loadedUrls).toEqual(['https://cdn.example.com/2.mp3'])
  })

  it('leaves the winner playing', async () => {
    const resolver = deferredResolver()
    h.browser.configuration = resolver.configuration
    h.browser.setPlayWhenReady(true)

    h.browser.load(trackA)
    await h.flush()
    h.browser.load(trackB)
    await h.flush()

    resolver.release(2)
    await h.flush()
    await h.completeLoad()
    resolver.release(1)
    await h.flush()

    expect(h.browser.getActiveTrack()?.id).toBe(trackB.id)
    expect(h.browser.getPlayback().state).toBe('playing')
  })
})

describe('a load superseded after its own url resolved', () => {
  /**
   * The gap the two generation counters left. Supersession was registered by
   * the *inner* `super.load()`, which only runs once the URL has resolved — so
   * a load whose Shaka promise had already settled ran its own success path
   * even though a newer `load()` had been called first. One token begun at the
   * call closes it.
   */
  it('does not run its callback once a newer load has begun', async () => {
    const resolver = deferredResolver()
    h.browser.configuration = resolver.configuration
    h.browser.setPlayWhenReady(true)

    const seen: Array<string | undefined> = []
    h.browser.load(trackA, (track) => seen.push(track.id))
    await h.flush()
    resolver.release(1)
    await h.flush() // super.load(A) has run; its Shaka load is pending

    h.finishLoadOnly() // A's load settles, its .then is queued
    h.browser.load(trackB) // superseded before that .then runs
    await h.flush()

    expect(seen).toEqual([])
  })
})

describe('a load stopped mid-resolution', () => {
  it('never hands its url to the player', async () => {
    const resolver = deferredResolver()
    h.browser.configuration = resolver.configuration

    h.browser.load(trackA)
    await h.flush()
    h.browser.stop()
    await h.flush()

    resolver.release(1)
    await h.flush()

    expect(h.loadedUrls).toEqual([])
    expect(h.browser.getPlayback().state).toBe('stopped')
  })
})

describe('a load stopped while Shaka is loading', () => {
  it('does not run the load callback, seek, or autoplay', async () => {
    let loadedCalls = 0
    h.browser.setPlayWhenReady(true)
    h.browser.load(trackA, () => {
      loadedCalls += 1
    })
    await h.flush() // player.load() is pending

    h.browser.stop()
    await h.flush()
    await h.completeLoad()

    expect(loadedCalls).toBe(0)
    expect(h.browser.getPlayback().state).toBe('stopped')
  })
})

describe('the load that wins', () => {
  it('runs its callback', async () => {
    const seen: Array<string | undefined> = []
    h.browser.load(trackA, (track) => seen.push(track.id))
    await h.flush()
    await h.completeLoad()

    expect(seen).toEqual([trackA.id])
  })

  it('applies a seek that arrived while it was loading', async () => {
    const resolver = deferredResolver()
    h.browser.configuration = resolver.configuration

    h.browser.load(trackA)
    await h.flush()
    h.browser.seekTo(30) // captured as pending: the load is still in progress
    resolver.release(1)
    await h.flush()
    await h.completeLoad()

    expect(h.currentTime).toBe(30)
  })

  it('autoplays when the intent is set', async () => {
    h.browser.setPlayWhenReady(true)
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()

    expect(h.browser.getPlayback().state).toBe('playing')
  })

  it('does not autoplay without the intent', async () => {
    const paused: Track = {
      id: 'p',
      title: 'P',
      src: 'https://x.example.com/p.mp3'
    }
    h.browser.load(paused)
    await h.flush()
    await h.completeLoad()

    expect(h.browser.getPlayback().state).toBe('ready')
  })
})
