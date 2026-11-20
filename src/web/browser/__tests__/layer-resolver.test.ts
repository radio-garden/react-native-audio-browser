import { afterEach, describe, it, expect, vi } from 'vitest'
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
    const silent = new Promise<never>(() => {})
    const now = vi.spyOn(Date, 'now')
    afterEach(() => now.mockReset())

    function managerWithResolver(
      answers: Array<Promise<TransformableRequestConfig>>
    ) {
      const manager = makeManager()
      const resolver = vi.fn(() => answers.shift() ?? silent)
      manager.configuration = { path: '/', requestResolver: resolver }
      return { manager, resolver }
    }

    it('is joined by a caller within ten seconds', async () => {
      now.mockReturnValue(0)
      const { manager, resolver } = managerWithResolver([])
      void manager.resolvedRequestConfig()
      now.mockReturnValue(9_999)
      void manager.resolvedRequestConfig()
      await Promise.resolve()
      expect(resolver).toHaveBeenCalledTimes(1)
    })

    it('is not joined after ten seconds, and the next answer releases every caller', async () => {
      now.mockReturnValue(0)
      const { manager, resolver } = managerWithResolver([
        silent,
        Promise.resolve({ baseUrl: 'https://late.example.com' })
      ])
      const early = manager.resolvedRequestConfig()
      now.mockReturnValue(10_000)
      expect(await manager.resolvedRequestConfig()).toEqual({
        baseUrl: 'https://late.example.com'
      })
      expect(await early).toEqual({ baseUrl: 'https://late.example.com' })
      expect(resolver).toHaveBeenCalledTimes(2)
    })

    it("leaves the earlier callers waiting on a later run's failure", async () => {
      now.mockReturnValue(0)
      let answer!: (config: TransformableRequestConfig) => void
      const { manager } = managerWithResolver([
        new Promise((resolve) => (answer = resolve)),
        Promise.reject(new Error('later run failed'))
      ])
      const early = manager.resolvedRequestConfig()
      now.mockReturnValue(10_000)
      await expect(manager.resolvedRequestConfig()).rejects.toThrow(
        'later run failed'
      )
      answer({ baseUrl: 'https://early.example.com' })
      expect(await early).toEqual({ baseUrl: 'https://early.example.com' })
    })
  })
})
