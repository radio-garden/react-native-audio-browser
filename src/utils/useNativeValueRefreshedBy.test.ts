/** @vitest-environment happy-dom */

import { renderHook, act, cleanup } from '@testing-library/react'
import { describe, it, expect, afterEach, vi } from 'vitest'
import { NativeUpdatedValue } from './NativeUpdatedValue'
import { useNativeValueRefreshedBy } from './useNativeValueRefreshedBy'

afterEach(cleanup)

function makeEmitter() {
  let emit: (value: unknown) => void = () => {}
  const emitter = NativeUpdatedValue.emitterize<unknown>((cb) => (emit = cb))
  return { emitter, emit: (value: unknown) => emit(value) }
}

describe('useNativeValueRefreshedBy', () => {
  it('re-reads the getter on an event from any emitter', () => {
    const a = makeEmitter()
    const b = makeEmitter()
    let native = 1
    const emitters = [a.emitter, b.emitter]
    const { result } = renderHook(() =>
      useNativeValueRefreshedBy(() => native, emitters)
    )
    native = 2
    act(() => b.emit(undefined))
    expect(result.current).toBe(2)
  })

  it('re-reads a change that lands between the render read and the effect', () => {
    const a = makeEmitter()
    const b = makeEmitter()
    let native = 'atRender'
    const getter = () => {
      const value = native
      if (value === 'atRender') {
        native = 'changed'
        b.emit(undefined)
      }
      return value
    }
    const emitters = [a.emitter, b.emitter]
    const { result } = renderHook(() =>
      useNativeValueRefreshedBy(getter, emitters)
    )
    expect(result.current).toBe('changed')
  })

  it('calls the getter once when no event lands before subscribing', () => {
    const a = makeEmitter()
    a.emit(undefined)
    const getter = vi.fn(() => 'a')
    const emitters = [a.emitter]
    renderHook(() => useNativeValueRefreshedBy(getter, emitters))
    expect(getter).toHaveBeenCalledTimes(1)
  })

  it('reads a new getter on re-render, with no event since mount', () => {
    const a = makeEmitter()
    const emitters = [a.emitter]
    const { result, rerender } = renderHook(
      ({ getter }) => useNativeValueRefreshedBy(getter, emitters),
      { initialProps: { getter: () => 'first' } }
    )
    rerender({ getter: () => 'second' })
    expect(result.current).toBe('second')
  })
})
