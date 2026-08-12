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

const { onSleepTimerChanged, useSleepTimer, useSleepTimerActive } =
  await import('./sleepTimer')

/** Push a value the way native would. */
const fire = (timer: SleepTimer) =>
  act(() => native.onSleepTimerChanged?.(timer))

/**
 * The emitter is a module-level singleton, so `lastValue` outlives each test.
 * `undefined` is its pre-native state and distinct from `null`, which is a real
 * value meaning "no timer" — the distinction the re-sync gate turns on.
 */
beforeEach(() => {
  onSleepTimerChanged.lastValue = undefined
  native.getSleepTimer.mockReturnValue(null)
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

  // The regression from f7a25040. The getter ran during render and saw a live
  // timer; the clear landed before the effect subscribed. Without the re-sync
  // the UI shows a running timer that is already gone — permanently, since
  // nothing reads it again until the next native event.
  it('re-syncs a clear that landed between the render read and the effect', () => {
    native.getSleepTimer.mockReturnValue({ time: Date.now() + 60_000 })
    onSleepTimerChanged.lastValue = null
    const { result } = renderHook(() => useSleepTimerActive())
    expect(result.current).toBe(false)
  })

  // The gate is `!== undefined`, not truthiness: `undefined` means native has
  // never spoken, so the getter's answer must stand.
  it('keeps the getter value when native has not fired at all', () => {
    native.getSleepTimer.mockReturnValue({ time: Date.now() + 60_000 })
    const { result } = renderHook(() => useSleepTimerActive())
    expect(result.current).toBe(true)
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

  // Same race as useSleepTimerActive, and the reason both hooks carry the gate.
  it('re-syncs a clear that landed between the render read and the effect', () => {
    native.getSleepTimer.mockReturnValue({ time: Date.now() + 60_000 })
    onSleepTimerChanged.lastValue = null
    const { result } = renderHook(() => useSleepTimer())
    expect(result.current).toBeUndefined()
  })
})
