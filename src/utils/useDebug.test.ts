/** @vitest-environment happy-dom */

import { renderHook, act, cleanup } from '@testing-library/react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

const native = vi.hoisted(() => {
  const slots = new Map<string, (event: never) => void>()
  return {
    slots,
    browser: new Proxy({} as Record<string, unknown>, {
      get(target, prop: string) {
        // Defaults have to be shaped, not undefined: useDebugState reads
        // `playback.state`, `queue.map(...)` and `progress.position` directly.
        target[prop] ??= vi.fn(() => defaults[prop])
        return target[prop]
      },
      set(target, prop: string, value) {
        if (typeof value === 'function') slots.set(prop, value)
        target[prop] = value
        return true
      }
    })
  }
})

const defaults: Record<string, unknown> = {
  getPlayback: { state: 'none' },
  getQueue: [],
  getProgress: { position: 0, duration: 0, buffered: 0 },
  getOptions: {},
  getPlayWhenReady: false,
  getShuffle: false,
  getRepeatMode: 'off',
  getOnline: true
}

vi.mock('../native', () => ({ nativeBrowser: native.browser }))

const { useDebug } = await import('./useDebug')

const fire = (slot: string, event: unknown) =>
  act(() => native.slots.get(slot)?.(event as never))

let logSpy: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  logSpy = vi.spyOn(console, 'log').mockImplementation(() => {})
  // Emitters and the log store are module singletons, so both the last value
  // and the entries outlive a test. Pin a baseline rather than letting each
  // test inherit whatever the previous one happened to fire.
  fire('onOnlineChanged', true)
  fire('onPlaybackProgressUpdated', { position: 0, duration: 0, buffered: 0 })
  const { result, unmount } = renderHook(() => useDebug({ enabled: false }))
  act(() => result.current.clear())
  unmount()
  logSpy.mockClear()
})

afterEach(() => {
  cleanup()
  logSpy.mockRestore()
})

describe('useDebug', () => {
  it('records nothing while disabled', () => {
    const { result } = renderHook(() => useDebug({ enabled: false }))
    fire('onOnlineChanged', false)
    expect(result.current.logs).toEqual([])
    expect(logSpy).not.toHaveBeenCalled()
  })

  it('logs a single initial snapshot on mount', () => {
    const { result } = renderHook(() => useDebug({ enabled: true }))
    expect(result.current.logs).toHaveLength(1)
    expect(result.current.logs[0]).toMatchObject({
      type: 'initial',
      elapsed: null
    })
    expect(result.current.logs[0]!.message).toContain('online: true')
  })

  it('logs a change entry naming the field that moved', () => {
    const { result } = renderHook(() => useDebug({ enabled: true }))
    fire('onOnlineChanged', false)

    expect(result.current.logs).toHaveLength(2)
    const entry = result.current.logs[1]!
    expect(entry.type).toBe('change')
    expect(entry.message).toContain('online')
    expect(entry.message).toContain('old → true')
    expect(entry.message).toContain('new → false')
  })

  it('logs nothing when a re-render changes no field', () => {
    const { result } = renderHook(() => useDebug({ enabled: true }))
    fire('onOnlineChanged', true) // already true
    expect(result.current.logs).toHaveLength(1)
  })

  // Progress ticks every second; logging each one would bury every other event.
  it('ignores position drift under two seconds but reports a real seek', () => {
    const { result } = renderHook(() => useDebug({ enabled: true }))

    fire('onPlaybackProgressUpdated', { position: 1, duration: 0, buffered: 0 })
    expect(result.current.logs).toHaveLength(1)

    fire('onPlaybackProgressUpdated', {
      position: 30,
      duration: 0,
      buffered: 0
    })
    expect(result.current.logs).toHaveLength(2)
    expect(result.current.logs[1]!.message).toContain('position')
  })

  it('clears on demand', () => {
    const { result } = renderHook(() => useDebug({ enabled: true }))
    fire('onOnlineChanged', false)
    expect(result.current.logs.length).toBeGreaterThan(0)

    act(() => result.current.clear())
    expect(result.current.logs).toEqual([])
  })

  it('caps the history and keeps the newest entries', () => {
    const { result } = renderHook(() => useDebug({ enabled: true }))
    for (let i = 0; i < 120; i++) fire('onOnlineChanged', i % 2 === 0)

    expect(result.current.logs).toHaveLength(100)
    // 120 alternating flips after the initial snapshot: the last one set online
    // to false (i = 119 is odd), so the newest entry is the tail, not the head.
    expect(result.current.logs.at(-1)!.message).toContain('new → false')
  })

  describe('metadata logging', () => {
    it('is off by default', () => {
      const { result } = renderHook(() => useDebug({ enabled: true }))
      fire('onTrackMetadata', { title: 'Song' })
      expect(result.current.logs.filter((l) => l.type === 'metadata')).toEqual(
        []
      )
    })

    it('records track, timed and chapter events when enabled', () => {
      const { result } = renderHook(() =>
        useDebug({ enabled: true, metadata: true })
      )

      fire('onTrackMetadata', { title: 'Song', artist: null })
      fire('onTimedMetadata', { title: 'Live' })
      fire('onChapterMetadata', [{}, {}])

      const entries = result.current.logs.filter((l) => l.type === 'metadata')
      expect(entries).toHaveLength(3)
      expect(entries[0]!.message).toContain('[track] title: "Song"')
      // Null fields are dropped rather than printed as `artist: "null"`.
      expect(entries[0]!.message).not.toContain('artist')
      expect(entries[1]!.message).toContain('[timed]')
      expect(entries[2]!.message).toContain('2 chapters')
    })

    it('stops recording metadata after unmount', () => {
      const { result, unmount } = renderHook(() =>
        useDebug({ enabled: true, metadata: true })
      )
      const before = result.current.logs.length
      unmount()
      fire('onTrackMetadata', { title: 'Song' })
      expect(result.current.logs).toHaveLength(before)
    })
  })

  it('falls back to __DEV__ when enabled is not given', () => {
    ;(globalThis as { __DEV__?: boolean }).__DEV__ = false
    try {
      const { result } = renderHook(() => useDebug())
      expect(result.current.logs).toEqual([])
    } finally {
      delete (globalThis as { __DEV__?: boolean }).__DEV__
    }
  })
})
