/** @vitest-environment happy-dom */

import { renderHook, act, cleanup } from '@testing-library/react'
import { describe, it, expect, afterEach, vi } from 'vitest'

const native = vi.hoisted(() => ({
  activeTrack: undefined as unknown,
  getActiveTrack: () => native.activeTrack,
  onPlaybackActiveTrackChanged: (_event: unknown) => {},
  onPlaybackQueueChanged: (_tracks: unknown) => {},
  onFavoriteChanged: (_event: unknown) => {}
}))
vi.mock('../../native', () => ({ nativeBrowser: native }))

import { useActiveTrack } from './activeTrack'

afterEach(cleanup)

describe('useActiveTrack', () => {
  it('re-reads the active track when the queue changes under it', () => {
    native.activeTrack = { src: 'a', title: 'A', favorited: false }
    const { result } = renderHook(() => useActiveTrack())
    expect(result.current?.favorited).toBe(false)

    // The queued copy is replaced in place: no transition, no favorite event.
    const replaced = { src: 'a', title: 'A', favorited: true }
    native.activeTrack = replaced
    act(() => native.onPlaybackQueueChanged([replaced]))

    expect(result.current?.favorited).toBe(true)
  })
})
