import { describe, test, expect, vi, beforeEach } from 'vitest'

vi.mock('../native', () => ({ nativeBrowser: {} }))

import { setGate, clearGate } from './gate'
import { nativeBrowser } from '../native'

const chrome = { title: 'T', message: 'M' }

beforeEach(() => {
  ;(nativeBrowser as any).setGate = vi.fn()
  ;(nativeBrowser as any).clearGate = vi.fn()
})

describe('setGate', () => {
  test('setGate(gate) → chrome + hasResolver false', () => {
    setGate(chrome)
    expect(nativeBrowser.setGate).toHaveBeenCalledWith(chrome, false)
  })

  test('setGate(gate, resolve) → chrome + hasResolver true', () => {
    setGate(chrome, () => true)
    expect(nativeBrowser.setGate).toHaveBeenCalledWith(chrome, true)
  })

  test('setGate(resolve) → undefined chrome + hasResolver true', () => {
    setGate(() => false)
    expect(nativeBrowser.setGate).toHaveBeenCalledWith(undefined, true)
  })
})

describe('resolveGate (native→JS bridge)', () => {
  test('resolver true → gated, no override', async () => {
    setGate(chrome, () => true)
    expect(
      await (nativeBrowser as any).resolveGate({ reason: 'browse', path: '/x' })
    ).toEqual({ gated: true })
  })

  test('resolver false → not gated', async () => {
    setGate(chrome, () => false)
    expect(
      await (nativeBrowser as any).resolveGate({ reason: 'browse', path: '/x' })
    ).toEqual({ gated: false })
  })

  test('resolver Gate → gated + override chrome', async () => {
    const override = { title: 'O' }
    setGate(chrome, () => override)
    expect(
      await (nativeBrowser as any).resolveGate({
        reason: 'search',
        search: { query: 'q' },
      })
    ).toEqual({ gated: true, gate: override })
  })

  test('no resolver (static) → every request gated', async () => {
    setGate(chrome)
    expect(
      await (nativeBrowser as any).resolveGate({ reason: 'browse', path: '/x' })
    ).toEqual({ gated: true })
  })
})

describe('clearGate', () => {
  test('clears native gate state', () => {
    setGate(chrome, () => false)
    clearGate()
    expect(nativeBrowser.clearGate).toHaveBeenCalled()
  })
})
