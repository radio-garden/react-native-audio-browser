import type { MockInstance } from 'vitest'
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import type { TransformableRequestConfig } from '../../../types'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

/**
 * Builds a BrowserManager wired to an HttpClient stub that always rejects.
 * Resolver invocation happens BEFORE the request fires, so call-count
 * assertions hold without any live network.
 */
function makeManager(): BrowserManager {
  const httpClient = {
    executeRequest: vi.fn().mockRejectedValue(new Error('no network in test'))
  } as unknown as HttpClient
  return new BrowserManager(
    httpClient,
    new FavoriteManager(),
    new NavigationErrorManager()
  )
}

describe('BrowserManager layer resolver', () => {
  it('invokes a resolver once per generation and reuses it across requests', async () => {
    let calls = 0
    const manager = makeManager()
    manager.configuration = {
      path: '/',
      requestResolver: async () => {
        calls += 1
        return { baseUrl: 'https://api.example.com' }
      }
    }
    await manager.navigatePath('/a')
    await manager.navigatePath('/b')
    expect(calls).toBe(1)
  })

  it('re-invokes the resolver after invalidateAllContent', async () => {
    let calls = 0
    const manager = makeManager()
    manager.configuration = {
      path: '/',
      requestResolver: async () => {
        calls += 1
        return { baseUrl: 'https://api.example.com' }
      }
    }
    await manager.navigatePath('/a')
    expect(calls).toBe(1)
    manager.invalidateAllContent()
    await manager.navigatePath('/a')
    expect(calls).toBe(2)
  })

  it('supports an async resolver and invokes it exactly once', async () => {
    let calls = 0
    const manager = makeManager()
    manager.configuration = {
      path: '/',
      requestResolver: async () => {
        calls += 1
        return { baseUrl: 'https://api.example.com' }
      }
    }
    await expect(manager.navigatePath('/a')).resolves.not.toThrow()
    expect(calls).toBe(1)
  })

  /**
   * The media and artwork paths sit outside browse and so must reach the
   * request layer through this accessor. Reading `configuration.request`
   * instead hands a resolver-only config no baseUrl and no headers — on
   * exactly the requests that carry a credential. Android's
   * `resolvedRequestConfig()` exists for the same reason.
   */
  it('exposes the resolver result to consumers outside the browse path', async () => {
    const manager = makeManager()
    manager.configuration = {
      path: '/',
      requestResolver: async () => ({
        baseUrl: 'https://api.example.com',
        headers: { Authorization: 'Bearer token' }
      })
    }

    expect(await manager.resolvedRequestConfig()).toEqual({
      baseUrl: 'https://api.example.com',
      headers: { Authorization: 'Bearer token' }
    })
  })

  it('falls back to the static request config when no resolver is set', async () => {
    const manager = makeManager()
    manager.configuration = {
      path: '/',
      request: { baseUrl: 'https://static.example.com' }
    }

    expect(await manager.resolvedRequestConfig()).toEqual({
      baseUrl: 'https://static.example.com'
    })
  })

  describe('a resolver that stays silent', () => {
    let now: MockInstance<() => number>
    beforeEach(() => {
      now = vi.spyOn(performance, 'now').mockReturnValue(0)
    })
    afterEach(() => now.mockRestore())

    it('is called again by a request past the bound', async () => {
      const answers = [
        new Promise<never>(() => {}),
        Promise.resolve({ baseUrl: 'https://late.example.com' })
      ]
      const manager = makeManager()
      manager.configuration = {
        path: '/',
        requestResolver: () => answers.shift() ?? new Promise<never>(() => {})
      }
      void manager.resolvedRequestConfig()
      await new Promise((resolve) => setTimeout(resolve, 0))
      now.mockReturnValue(10_000)
      expect(await manager.resolvedRequestConfig()).toEqual({
        baseUrl: 'https://late.example.com'
      })
    })
  })

  it('never hands a request the layer of a configuration it replaced', async () => {
    const answers: Array<(config: TransformableRequestConfig) => void> = []
    const requestResolver = () =>
      new Promise<TransformableRequestConfig>((resolve) =>
        answers.push(resolve)
      )
    const manager = makeManager()
    manager.configuration = { path: '/', requestResolver }
    const pending = manager.resolvedRequestConfig()
    await new Promise((resolve) => setTimeout(resolve, 0))
    manager.configuration = { path: '/', requestResolver }
    await new Promise((resolve) => setTimeout(resolve, 0))
    answers[0]!({ baseUrl: 'https://old.example.com' })
    await new Promise((resolve) => setTimeout(resolve, 0))
    answers[1]!({ baseUrl: 'https://new.example.com' })
    expect(await pending).toEqual({ baseUrl: 'https://new.example.com' })
  })
})
