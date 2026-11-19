/** @vitest-environment happy-dom */

import { beforeEach, describe, expect, it } from 'vitest'
import type { Track } from '../../types'
import { LifecycleHarness, trackA, trackB } from './lifecycleHarness'

const artwork = 'https://img.example.com/a.jpg'
const declared: Track = { ...trackA, artwork }
const hydrated = {
  favorited: true,
  artworkSource: { uri: artwork, method: 'GET' }
}

let h: LifecycleHarness

beforeEach(async () => {
  h = new LifecycleHarness()
  await h.setup()
  h.browser.setFavorites(['a'])
})

describe('a track entering the queue', () => {
  it('is hydrated and gets its artwork source through add()', () => {
    h.browser.add([declared])

    expect(h.browser.getQueue()[0]).toMatchObject(hydrated)
    expect(h.browser.getActiveTrack()).toMatchObject(hydrated)
  })

  it('is hydrated and gets its artwork source when add() appends it', () => {
    h.browser.setQueue([trackB], 0)

    h.browser.add([declared])

    expect(h.browser.getQueue()[1]).toMatchObject(hydrated)
  })

  it('is hydrated and gets its artwork source through load() on an empty queue', () => {
    h.browser.load(declared)

    expect(h.browser.getQueue()[0]).toMatchObject(hydrated)
    expect(h.browser.getActiveTrack()).toMatchObject(hydrated)
  })

  it('is hydrated and gets its artwork source when load() replaces the current entry', () => {
    h.browser.setQueue([trackB], 0)

    h.browser.load(declared)

    expect(h.browser.getQueue()[0]).toMatchObject(hydrated)
    expect(h.browser.getActiveTrack()).toMatchObject(hydrated)
  })

  it('is hydrated and gets its artwork source through load() on a queue with no current entry', () => {
    h.browser.setQueue([trackB])

    h.browser.load(declared)

    expect(h.browser.getActiveTrack()).toMatchObject(hydrated)
  })

  it('emits no queue change when a skip loads a queued track', () => {
    h.browser.setQueue([declared, trackB], 0)
    h.clear()

    h.browser.skipToNext()

    expect(h.recorded).not.toContain('queue')
  })

  it('takes the same form through add() and load() as through setQueue()', () => {
    h.browser.setQueue([declared])
    const [viaSetQueue] = h.browser.getQueue()

    h.browser.setQueue([])
    h.browser.add([declared])
    expect(h.browser.getQueue()[0]).toEqual(viaSetQueue)

    h.browser.setQueue([])
    h.browser.load(declared)
    expect(h.browser.getQueue()[0]).toEqual(viaSetQueue)
  })
})
