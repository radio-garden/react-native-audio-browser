import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PlaybackTimer } from './PlaybackTimer'

describe('PlaybackTimer', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('ticks every interval while the gate is open', () => {
    const timer = new PlaybackTimer()
    const onTick = vi.fn()
    timer.start(1000, () => true, onTick)

    vi.advanceTimersByTime(3000)

    expect(onTick).toHaveBeenCalledTimes(3)
  })

  it('skips ticks while the gate is closed but keeps running', () => {
    const timer = new PlaybackTimer()
    const onTick = vi.fn()
    let open = false
    timer.start(1000, () => open, onTick)

    vi.advanceTimersByTime(2000)
    expect(onTick).not.toHaveBeenCalled()

    open = true
    vi.advanceTimersByTime(2000)
    expect(onTick).toHaveBeenCalledTimes(2)
  })

  it('stops ticking after stop()', () => {
    const timer = new PlaybackTimer()
    const onTick = vi.fn()
    timer.start(1000, () => true, onTick)

    vi.advanceTimersByTime(1000)
    timer.stop()
    vi.advanceTimersByTime(5000)

    expect(onTick).toHaveBeenCalledTimes(1)
  })

  it('restarts cleanly when start() is called again', () => {
    const timer = new PlaybackTimer()
    const first = vi.fn()
    const second = vi.fn()
    timer.start(1000, () => true, first)
    timer.start(500, () => true, second)

    vi.advanceTimersByTime(1000)

    // The first timer must have been cleared — only the second ticks.
    expect(first).not.toHaveBeenCalled()
    expect(second).toHaveBeenCalledTimes(2)
  })

  it('does not tick for a non-positive interval (acts as stopped)', () => {
    const timer = new PlaybackTimer()
    const onTick = vi.fn()
    timer.start(0, () => true, onTick)

    vi.advanceTimersByTime(5000)

    expect(onTick).not.toHaveBeenCalled()
  })
})
