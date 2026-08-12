/** @vitest-environment happy-dom */

import { renderHook, act, cleanup } from '@testing-library/react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { Progress } from './progress'
import {
  appStateListenerCount,
  resetAppState,
  setAppState
} from '../../test-utils/reactNativeStub'

const native = vi.hoisted(() => ({
  getProgress: vi.fn<() => Progress>(() => ({
    position: 0,
    duration: 0,
    buffered: 0
  })),
  onPlaybackChanged: undefined as ((event: unknown) => void) | undefined,
  onPlaybackProgressUpdated: undefined as ((event: unknown) => void) | undefined
}))

vi.mock('../../native', () => ({ nativeBrowser: native }))

const { usePolledProgress } = await import('./progress')

const at = (position: number): Progress => ({
  position,
  duration: 300,
  buffered: position + 10
})

beforeEach(() => {
  vi.useFakeTimers()
  resetAppState()
  native.getProgress.mockReturnValue(at(0))
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('usePolledProgress', () => {
  it('starts at zero and polls the first value in', () => {
    native.getProgress.mockReturnValue(at(12))
    const { result } = renderHook(() => usePolledProgress())
    // The initial poll runs inside the effect, so the zeroed useState default is
    // never observable to a caller.
    expect(result.current).toEqual(at(12))
  })

  it('polls on the given interval', () => {
    const { result } = renderHook(() => usePolledProgress(500))
    native.getProgress.mockReturnValue(at(1))
    act(() => {
      vi.advanceTimersByTime(500)
    })
    expect(result.current).toEqual(at(1))

    native.getProgress.mockReturnValue(at(2))
    act(() => {
      vi.advanceTimersByTime(500)
    })
    expect(result.current).toEqual(at(2))
  })

  it('does not poll before the interval elapses', () => {
    renderHook(() => usePolledProgress(1000))
    native.getProgress.mockClear()
    act(() => {
      vi.advanceTimersByTime(999)
    })
    expect(native.getProgress).not.toHaveBeenCalled()
  })

  it('keeps the same object when nothing changed', () => {
    const { result } = renderHook(() => usePolledProgress(500))
    const first = result.current
    act(() => {
      vi.advanceTimersByTime(500)
    })
    // Identity, not equality: the hook returns the previous state object so a
    // consumer's useEffect/memo on progress does not re-fire every tick.
    expect(result.current).toBe(first)
  })

  it('updates immediately when playback changes, without waiting for a tick', () => {
    const { result } = renderHook(() => usePolledProgress(10_000))
    native.getProgress.mockReturnValue(at(7))
    act(() => {
      native.onPlaybackChanged?.({ state: 'playing' })
    })
    expect(result.current).toEqual(at(7))
  })

  it('survives a getter that throws before setup', () => {
    native.getProgress.mockImplementation(() => {
      throw new Error('not set up')
    })
    const { result } = renderHook(() => usePolledProgress(500))
    expect(result.current).toEqual({ position: 0, duration: 0, buffered: 0 })
    expect(() => act(() => vi.advanceTimersByTime(500))).not.toThrow()
  })

  it('stops polling in the background and resumes on return', () => {
    const { result } = renderHook(() => usePolledProgress(500))

    act(() => {
      setAppState('background')
    })
    native.getProgress.mockReturnValue(at(99))
    act(() => {
      vi.advanceTimersByTime(5_000)
    })
    expect(result.current).toEqual(at(0))

    // Returning to the foreground updates once immediately rather than leaving
    // a stale position on screen until the next interval.
    act(() => {
      setAppState('active')
    })
    expect(result.current).toEqual(at(99))
  })

  it('ignores playback events while backgrounded', () => {
    const { result } = renderHook(() => usePolledProgress(500))
    act(() => {
      setAppState('background')
    })
    native.getProgress.mockReturnValue(at(42))
    act(() => {
      native.onPlaybackChanged?.({ state: 'playing' })
    })
    expect(result.current).toEqual(at(0))
  })

  it('does not stack pollers when backgrounded twice', () => {
    renderHook(() => usePolledProgress(500))
    act(() => {
      setAppState('background')
    })
    act(() => {
      setAppState('active')
    })
    act(() => {
      setAppState('active')
    })

    native.getProgress.mockClear()
    act(() => {
      vi.advanceTimersByTime(500)
    })
    expect(native.getProgress).toHaveBeenCalledTimes(1)
  })

  it('tears down its timer and subscriptions on unmount', () => {
    const { unmount } = renderHook(() => usePolledProgress(500))
    expect(appStateListenerCount()).toBe(1)

    unmount()
    expect(appStateListenerCount()).toBe(0)

    native.getProgress.mockClear()
    act(() => {
      vi.advanceTimersByTime(5_000)
    })
    expect(native.getProgress).not.toHaveBeenCalled()
  })
})
