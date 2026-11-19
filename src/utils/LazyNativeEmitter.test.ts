import { describe, it, expect, vi, afterEach } from 'vitest'
import { LazyNativeEmitter } from './LazyNativeEmitter'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('LazyNativeEmitter', () => {
  it('calls the listeners after one that throws, and rethrows its error from a microtask', () => {
    const rethrown: (() => void)[] = []
    vi.stubGlobal('queueMicrotask', (task: () => void) => rethrown.push(task))
    let emit: (data: string) => void = () => {
      throw new Error('the native slot was never set')
    }
    const emitter = LazyNativeEmitter.emitterize<string>((callback) => {
      emit = callback
    })
    const error = new Error('listener failed')
    const received: string[] = []
    emitter.addListener(() => {
      throw error
    })
    emitter.addListener((data) => received.push(data))

    emit('first')
    emit('second')

    expect(received).toEqual(['first', 'second'])
    expect(rethrown).toHaveLength(2)
    expect(rethrown[0]).toThrow(error)
  })
})
