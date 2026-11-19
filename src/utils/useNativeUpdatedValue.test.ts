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
  it('reads the getter', () => {
    const { emitter } = makeEmitter<string>()
    const { result } = renderHook(() =>
      useNativeUpdatedValue(() => 'a', emitter)
    )
    expect(result.current).toBe('a')
  })

  it('updates when the emitter fires', () => {
    const { emitter, fire } = makeEmitter<string>()
    const { result } = renderHook(() =>
      useNativeUpdatedValue(() => 'a', emitter)
    )
    fire('b')
    expect(result.current).toBe('b')
  })

  it('extracts eventKey from events', () => {
    const { emitter, fire } = makeEmitter<{ volume: number }>()
    const { result } = renderHook(() =>
      useNativeUpdatedValue(() => 0.5, emitter, 'volume')
    )
    expect(result.current).toBe(0.5)
    fire({ volume: 0.9 })
    expect(result.current).toBe(0.9)
  })

  it('re-reads a change that lands between the render read and the effect', () => {
    const { emitter, fireDuringRender } = makeEmitter<string>()
    let native = 'atRender'
    const getter = () => {
      const value = native
      if (value === 'atRender') {
        native = 'changed'
        fireDuringRender('changed')
      }
      return value
    }
    const { result } = renderHook(() => useNativeUpdatedValue(getter, emitter))
    expect(result.current).toBe('changed')
  })

  it('calls the getter once when no event lands before subscribing', () => {
    const { emitter, fire } = makeEmitter<string>()
    fire('earlier')
    const getter = vi.fn(() => 'a')
    renderHook(() => useNativeUpdatedValue(getter, emitter))
    expect(getter).toHaveBeenCalledTimes(1)
  })

  it('keeps an event over an inline getter on re-render', () => {
    const { emitter, fire } = makeEmitter<string>()
    const { result, rerender } = renderHook(() =>
      useNativeUpdatedValue(() => 'a', emitter)
    )
    fire('b')
    rerender()
    expect(result.current).toBe('b')
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
