import { describe, it, expect, vi, beforeEach } from 'vitest'

const nativeMock = {
  onPlaybackInterval: undefined as undefined | (() => void),
  setPlaybackIntervalEnabled: vi.fn()
}
vi.mock('../../native', () => ({
  get nativeBrowser() {
    return nativeMock
  }
}))
vi.mock('../queue/activeTrack', () => ({
  onActiveTrackChanged: { addListener: vi.fn(() => () => {}) }
}))

import { trackPlaybackTime, __emitTickForTests } from './trackPlaybackTime'

beforeEach(() => {
  nativeMock.setPlaybackIntervalEnabled.mockClear()
})

describe('trackPlaybackTime', () => {
  it('enables native tick on first subscriber, disables on last', () => {
    const a = trackPlaybackTime(() => {}, 5)
    expect(nativeMock.setPlaybackIntervalEnabled).toHaveBeenLastCalledWith(true)
    const b = trackPlaybackTime(() => {}, 5)
    a()
    expect(nativeMock.setPlaybackIntervalEnabled).toHaveBeenLastCalledWith(true) // b still active
    b()
    expect(nativeMock.setPlaybackIntervalEnabled).toHaveBeenLastCalledWith(
      false
    )
  })

  it('fires every `period` seconds with cumulative total', () => {
    const calls: { total: number; sinceLast: number }[] = []
    const cancel = trackPlaybackTime(
      (e) => calls.push({ total: e.total, sinceLast: e.sinceLast }),
      3
    )
    __emitTickForTests(2)
    expect(calls).toEqual([])
    __emitTickForTests(1) // total now 3 → fires
    expect(calls).toEqual([{ total: 3, sinceLast: 3 }])
    __emitTickForTests(3) // total now 6 → fires again
    expect(calls).toEqual([
      { total: 3, sinceLast: 3 },
      { total: 6, sinceLast: 3 }
    ])
    cancel()
    __emitTickForTests(3)
    expect(calls).toHaveLength(2) // cancelled
  })

  it('runs multiple subscribers at independent periods', () => {
    const fast: number[] = []
    const slow: number[] = []
    const cancelFast = trackPlaybackTime((e) => fast.push(e.total), 1)
    const cancelSlow = trackPlaybackTime((e) => slow.push(e.total), 2)
    __emitTickForTests(2)
    expect(fast).toEqual([1, 2])
    expect(slow).toEqual([2])
    cancelFast()
    cancelSlow()
  })
})
