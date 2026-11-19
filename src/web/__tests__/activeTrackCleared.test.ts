/** @vitest-environment happy-dom */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PlaybackActiveTrackChangedEvent } from '../../features'
import { LifecycleHarness, trackA, trackB } from './lifecycleHarness'

let h: LifecycleHarness
let events: PlaybackActiveTrackChangedEvent[]
let session: { metadata: unknown; playbackState: string }

beforeEach(async () => {
  session = { metadata: null, playbackState: 'none' }
  vi.stubGlobal('navigator', {
    onLine: true,
    mediaSession: { ...session, setActionHandler: () => {} }
  })
  session = navigator.mediaSession as unknown as typeof session
  vi.stubGlobal(
    'MediaMetadata',
    class {
      constructor(readonly init: { title?: string }) {}
    }
  )

  h = new LifecycleHarness()
  await h.setup()
  events = []
  h.browser.onPlaybackActiveTrackChanged = (event) => events.push(event)

  // B is active at index 1, 12 seconds in.
  h.browser.setQueue([trackA, trackB], 1)
  await h.flush()
  await h.completeLoad()
  h.setPosition(12)
  events.length = 0
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const cleared = {
  lastTrack: expect.objectContaining({ id: trackB.id }),
  lastIndex: 1,
  lastPosition: 12,
  index: undefined,
  track: undefined
}

describe('emptying the player announces that no track is active', () => {
  it('on reset()', () => {
    h.browser.reset()

    expect(h.browser.getActiveTrack()).toBeUndefined()
    expect(events).toEqual([cleared])
  })

  it('on removing every track', () => {
    h.browser.remove([0, 1])

    expect(h.browser.getActiveTrack()).toBeUndefined()
    expect(events).toEqual([cleared])
  })

  it('on setQueue() without a startIndex', () => {
    h.browser.setQueue([trackA])

    expect(h.browser.getActiveTrack()).toBeUndefined()
    expect(events).toEqual([cleared])
  })

  it('after the queue change, as a queue mutation does on native', () => {
    const order: string[] = []
    h.browser.onPlaybackQueueChanged = () => order.push('queue')
    h.browser.onPlaybackActiveTrackChanged = () => order.push('activeTrack')

    h.browser.reset()

    expect(order).toEqual(['queue', 'activeTrack'])
  })

  it('takes the track off the media session', () => {
    expect(session.metadata).toMatchObject({ init: { title: trackB.title } })

    h.browser.reset()

    expect(session.metadata).toBeNull()
  })
})

describe('a player that stays as it was announces nothing', () => {
  it('on reset() with no active track', () => {
    h.browser.reset()
    events.length = 0

    h.browser.reset()

    expect(events).toEqual([])
  })

  it('on removing a track that is not the active one', () => {
    h.browser.remove([0])

    expect(h.browser.getActiveTrack()?.id).toBe(trackB.id)
    expect(events).toEqual([])
  })

  it('on setQueue() with a startIndex, which announces its track alone', () => {
    h.browser.setQueue([trackA], 0)

    expect(events.map((event) => event.track?.id)).toEqual([trackA.id])
  })
})
