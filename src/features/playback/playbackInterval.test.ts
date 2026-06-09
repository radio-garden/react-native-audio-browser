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

import { setPlaybackInterval, __emitTickForTests } from './playbackInterval'

beforeEach(() => {
  nativeMock.setPlaybackIntervalEnabled.mockClear()
})

function tick(times: number) {
  for (let i = 0; i < times; i++) __emitTickForTests(1000)
}

describe('setPlaybackInterval', () => {
  it('enables native tick on first subscriber, disables on last', () => {
    const a = setPlaybackInterval(() => {}, 5000)
    expect(nativeMock.setPlaybackIntervalEnabled).toHaveBeenLastCalledWith(true)
    const b = setPlaybackInterval(() => {}, 5000)
    a()
    expect(nativeMock.setPlaybackIntervalEnabled).toHaveBeenLastCalledWith(true)
    b()
    expect(nativeMock.setPlaybackIntervalEnabled).toHaveBeenLastCalledWith(false)
  })

  it('fires a subscriber every everyMs of ticks', () => {
    const cb = vi.fn()
    const cancel = setPlaybackInterval(cb, 3000)
    tick(2)
    expect(cb).toHaveBeenCalledTimes(0)
    tick(1)
    expect(cb).toHaveBeenCalledTimes(1)
    tick(3)
    expect(cb).toHaveBeenCalledTimes(2)
    cancel()
    tick(3)
    expect(cb).toHaveBeenCalledTimes(2)
  })

  it('runs multiple subscribers at independent cadences', () => {
    const fast = vi.fn()
    const slow = vi.fn()
    setPlaybackInterval(fast, 1000)
    setPlaybackInterval(slow, 2000)
    tick(2)
    expect(fast).toHaveBeenCalledTimes(2)
    expect(slow).toHaveBeenCalledTimes(1)
  })
})
