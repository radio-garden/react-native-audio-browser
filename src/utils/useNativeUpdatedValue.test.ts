/** @vitest-environment happy-dom */

import { renderHook, act, cleanup } from '@testing-library/react'
import { describe, it, expect, afterEach, vi } from 'vitest'
import { NativeUpdatedValue } from './NativeUpdatedValue'
import { useNativeUpdatedValue } from './useNativeUpdatedValue'

afterEach(cleanup)

/**
 * Builds an emitter plus the native-side callback that feeds it, so a test can
 * push a value the way native would. `NativeUpdatedValue` hands its internal
 * handler to the setter it is constructed with; capturing that handler _is_ the
 * native bridge.
 */
function makeEmitter<T>() {
  let emit: (value: T) => void = () => {}
  const emitter = NativeUpdatedValue.emitterize<T>((cb) => (emit = cb))
  return {
    emitter,
    /** Emit from outside React, as a native event would arrive. */
    fire: (value: T) => act(() => emit(value)),
    /** Emit from inside a render pass, where wrapping in `act` would nest. */
    fireDuringRender: (value: T) => emit(value)
  }
}

describe('useNativeUpdatedValue', () => {
  it('falls back to the getter when nothing has fired yet', () => {
    const { emitter } = makeEmitter<string>()
    const { result } = renderHook(() =>
      useNativeUpdatedValue(() => 'a', emitter)
    )
    expect(result.current).toBe('a')
  })

  it('prefers lastValue over the getter', () => {
    const { emitter, fire } = makeEmitter<string>()
    fire('fromNative')
    const getter = vi.fn(() => 'fromGetter')
    const { result } = renderHook(() => useNativeUpdatedValue(getter, emitter))
    expect(result.current).toBe('fromNative')
    expect(getter).not.toHaveBeenCalled()
  })

  it('updates when the emitter fires', () => {
    const { emitter, fire } = makeEmitter<string>()
    const { result } = renderHook(() =>
      useNativeUpdatedValue(() => 'a', emitter)
    )
    fire('b')
    expect(result.current).toBe('b')
  })

  it('extracts eventKey from both lastValue and later events', () => {
    const { emitter, fire } = makeEmitter<{ volume: number }>()
    fire({ volume: 0.5 })
    const { result } = renderHook(() =>
      useNativeUpdatedValue(() => 0, emitter, 'volume')
    )
    expect(result.current).toBe(0.5)
    fire({ volume: 0.9 })
    expect(result.current).toBe(0.9)
  })

  // The reason the effect re-reads `lastValue` instead of going straight to
  // `addListener`. Nothing reads the value again until the next native event, so
  // a value landing in this window would otherwise be missed permanently.
  it('re-syncs a value that lands between the render read and the effect', () => {
    const { emitter, fireDuringRender } = makeEmitter<string>()
    // Firing from inside the getter puts the emit exactly where the race is:
    // after the useState initializer has read, before the effect subscribes.
    const getter = () => {
      fireDuringRender('landedMidRender')
      return 'staleAtRender'
    }
    const { result } = renderHook(() => useNativeUpdatedValue(getter, emitter))
    expect(result.current).toBe('landedMidRender')
  })

  it('stops updating after unmount', () => {
    const { emitter, fire } = makeEmitter<string>()
    const { result, unmount } = renderHook(() =>
      useNativeUpdatedValue(() => 'a', emitter)
    )
    unmount()
    fire('b')
    expect(result.current).toBe('a')
  })

  it('leaves no listener behind on unmount', () => {
    const { emitter, fire } = makeEmitter<string>()
    const { unmount } = renderHook(() =>
      useNativeUpdatedValue(() => 'a', emitter)
    )
    unmount()
    const seen: string[] = []
    emitter.addListener((v) => seen.push(v))
    fire('b')
    // Only the listener added above — the hook's is gone, not merely inert.
    expect(seen).toEqual(['b'])
  })
})
