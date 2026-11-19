/** @vitest-environment happy-dom */

import { renderHook, act, cleanup } from '@testing-library/react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { PlaybackError, PlaybackErrorEvent } from './errors'

const native = vi.hoisted(() => ({
  getPlaybackError: vi.fn<() => PlaybackError | undefined>(() => undefined),
  // The single callback slot `NativeUpdatedValue` claims at module load.
  onPlaybackError: undefined as
    | ((event: PlaybackErrorEvent) => void)
    | undefined
}))

vi.mock('../native', () => ({ nativeBrowser: native }))

const { getPlaybackError, usePlaybackError } = await import('./errors')

const terminal: PlaybackError = {
  kind: 'unreachable',
  code: 'io-network-connection-failed',
  message: 'connection failed'
}
// What native holds while the retry loop works: no `onPlaybackError` announces
// it, and none clears it when the stream recovers.
const retrying: PlaybackError = { ...terminal, retrying: true }

/** Push an event the way native would. */
const fire = (event: PlaybackErrorEvent) =>
  act(() => native.onPlaybackError?.(event))

beforeEach(() => {
  native.getPlaybackError.mockReset().mockReturnValue(undefined)
})
afterEach(cleanup)

describe('getPlaybackError', () => {
  it('returns a terminal error', () => {
    native.getPlaybackError.mockReturnValue(terminal)
    expect(getPlaybackError()).toEqual(terminal)
  })

  it('returns undefined while the player is still retrying', () => {
    native.getPlaybackError.mockReturnValue(retrying)
    expect(getPlaybackError()).toBeUndefined()
  })
})

describe('usePlaybackError', () => {
  it('reads a terminal error at mount, and clears on leaving the error state', () => {
    native.getPlaybackError.mockReturnValue(terminal)
    const { result } = renderHook(() => usePlaybackError())
    expect(result.current).toEqual(terminal)
    fire({ error: undefined })
    expect(result.current).toBeUndefined()
  })

  it('is undefined when mounted while the player is retrying', () => {
    native.getPlaybackError.mockReturnValue(retrying)
    const { result } = renderHook(() => usePlaybackError())
    expect(result.current).toBeUndefined()
  })

  it('shows the terminal error once a retry it mounted during gives up', () => {
    native.getPlaybackError.mockReturnValue(retrying)
    const { result } = renderHook(() => usePlaybackError())
    fire({ error: terminal })
    expect(result.current).toEqual(terminal)
  })

  // A hardening lands between the render read and the subscription: the
  // re-read then sees the terminal error.
  it('re-reads a terminal error that landed between the render read and the effect', () => {
    native.getPlaybackError
      .mockImplementationOnce(() => {
        native.onPlaybackError?.({ error: terminal })
        return retrying
      })
      .mockReturnValue(terminal)
    const { result } = renderHook(() => usePlaybackError())
    expect(result.current).toEqual(terminal)
  })
})
