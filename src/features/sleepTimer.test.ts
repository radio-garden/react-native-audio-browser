/** @vitest-environment happy-dom */

import { renderHook, act, cleanup } from '@testing-library/react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { SleepTimer } from './sleepTimer'

const native = vi.hoisted(() => ({
  getSleepTimer: vi.fn<() => SleepTimer>(() => null),
  // The single callback slot `NativeUpdatedValue` claims at module load.
  onSleepTimerChanged: undefined as ((timer: SleepTimer) => void) | undefined
}))

vi.mock('../native', () => ({ nativeBrowser: native }))

const { useSleepTimer, useSleepTimerActive } = await import('./sleepTimer')

/**
 * A first read that sees a live timer while native clears it before the hook
 * subscribes: the clear's event lands mid-render, before any listener exists.
 */
const clearDuringRead = (): SleepTimer => {
  native.onSleepTimerChanged?.(null)
  return { time: Date.now() + 60_000 }
}

/** Push a value the way native would. */
const fire = (timer: SleepTimer) =>
  act(() => native.onSleepTimerChanged?.(timer))

beforeEach(() => {
  native.getSleepTimer.mockReset().mockReturnValue(null)
})
afterEach(cleanup)

describe('useSleepTimerActive', () => {
  it('reads the getter when nothing has fired yet', () => {
    native.getSleepTimer.mockReturnValue({ time: Date.now() + 60_000 })
    const { result } = renderHook(() => useSleepTimerActive())
    expect(result.current).toBe(true)
  })

  it('tracks timers being set and cleared', () => {
    const { result } = renderHook(() => useSleepTimerActive())
    expect(result.current).toBe(false)
    fire({ time: Date.now() + 60_000 })
    expect(result.current).toBe(true)
    fire(null)
    expect(result.current).toBe(false)
  })

  it('treats an end-of-track timer as active', () => {
    const { result } = renderHook(() => useSleepTimerActive())
    fire({ sleepWhenPlayedToEnd: true })
    expect(result.current).toBe(true)
  })

  // The getter ran during render and saw a live timer; the clear landed
  // before the effect subscribed.
  it('re-reads a clear that landed between the render read and the effect', () => {
    native.getSleepTimer.mockImplementationOnce(clearDuringRead)
    const { result } = renderHook(() => useSleepTimerActive())
    expect(result.current).toBe(false)
  })

  it('calls the getter once when no event lands before subscribing', () => {
    renderHook(() => useSleepTimerActive())
    expect(native.getSleepTimer).toHaveBeenCalledTimes(1)
  })

  it('stops tracking after unmount', () => {
    const { result, unmount } = renderHook(() => useSleepTimerActive())
    unmount()
    fire({ time: Date.now() + 60_000 })
    expect(result.current).toBe(false)
  })
})

describe('useSleepTimer', () => {
  it('is undefined when no timer is set', () => {
    const { result } = renderHook(() => useSleepTimer())
    expect(result.current).toBeUndefined()
  })

  it('reports secondsLeft for a time-based timer', () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(1_000_000_000_000)
      native.getSleepTimer.mockReturnValue({ time: Date.now() + 30_000 })
      const { result } = renderHook(() => useSleepTimer())
      expect(result.current).toEqual({
        time: 1_000_000_030_000,
        secondsLeft: 30
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('passes an end-of-track timer through without secondsLeft', () => {
    const { result } = renderHook(() => useSleepTimer())
    fire({ sleepWhenPlayedToEnd: true })
    expect(result.current).toEqual({ sleepWhenPlayedToEnd: true })
  })

  it('counts down as time passes', () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(1_000_000_000_000)
      native.getSleepTimer.mockReturnValue({ time: Date.now() + 5_000 })
      const { result } = renderHook(() => useSleepTimer())
      expect(result.current).toMatchObject({ secondsLeft: 5 })
      act(() => {
        vi.advanceTimersByTime(2_000)
      })
      expect(result.current).toMatchObject({ secondsLeft: 3 })
      act(() => {
        vi.advanceTimersByTime(3_000)
      })
      expect(result.current).toMatchObject({ secondsLeft: 0 })
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops the countdown once it reaches zero', () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(1_000_000_000_000)
      native.getSleepTimer.mockReturnValue({ time: Date.now() + 2_000 })
      const { result } = renderHook(() => useSleepTimer())
      act(() => {
        vi.advanceTimersByTime(2_000)
      })
      expect(result.current).toMatchObject({ secondsLeft: 0 })
      // Clamped at zero rather than running negative.
      act(() => {
        vi.advanceTimersByTime(10_000)
      })
      expect(result.current).toMatchObject({ secondsLeft: 0 })
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not tick while inactive', () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(1_000_000_000_000)
      native.getSleepTimer.mockReturnValue({ time: Date.now() + 60_000 })
      const { result } = renderHook(() => useSleepTimer({ inactive: true }))
      expect(result.current).toMatchObject({ secondsLeft: 60 })
      act(() => {
        vi.advanceTimersByTime(10_000)
      })
      expect(result.current).toMatchObject({ secondsLeft: 60 })
    } finally {
      vi.useRealTimers()
    }
  })

  it('re-reads a clear that landed between the render read and the effect', () => {
    native.getSleepTimer.mockImplementationOnce(clearDuringRead)
    const { result } = renderHook(() => useSleepTimer())
    expect(result.current).toBeUndefined()
  })

  it('calls the getter once when no event lands before subscribing', () => {
    renderHook(() => useSleepTimer())
    expect(native.getSleepTimer).toHaveBeenCalledTimes(1)
  })
})
