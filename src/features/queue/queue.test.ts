import { describe, it, expect, vi, beforeEach } from 'vitest'

const calls: any[] = []
vi.mock('../../native', () => ({
  nativeBrowser: {
    setQueue: (...a: any[]) => calls.push(['setQueue', ...a]),
    play: () => calls.push(['play']),
  },
}))

import { setQueue } from './queue'

describe('setQueue wrapper', () => {
  beforeEach(() => { calls.length = 0 })

  it('threads startIndex and startPositionMs to native', () => {
    const tracks = [{ src: 'a' }, { src: 'b' }] as any
    setQueue(tracks, 1, 5000)
    expect(calls).toContainEqual(['setQueue', tracks, 1, 5000])
  })

  it('forwards startIndex even when startPositionMs is omitted', () => {
    const tracks = [{ src: 'a' }, { src: 'b' }] as any
    setQueue(tracks, 1)
    expect(calls[0]).toEqual(['setQueue', tracks, 1, undefined])
  })

  it('only sets the queue and does not start playback', () => {
    setQueue([{ src: 'a' }] as any, 0)
    // setQueue no longer auto-plays — it preserves the current play/pause
    // state; callers start playback explicitly with play().
    expect(calls.map(c => c[0])).toEqual(['setQueue'])
  })
})
