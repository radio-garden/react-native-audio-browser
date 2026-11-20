import { describe, it, expect, vi } from 'vitest'
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

  it('rejects with a failed resolution and retries it on the next request', async () => {
    let calls = 0
    const manager = makeManager()
    manager.configuration = {
      path: '/',
      requestResolver: async () => {
        calls += 1
        if (calls === 1) throw new Error('token refresh failed')
        return { baseUrl: 'https://api.example.com' }
      }
    }

    await expect(manager.resolvedRequestConfig()).rejects.toThrow(
      'token refresh failed'
    )
    expect(await manager.resolvedRequestConfig()).toEqual({
      baseUrl: 'https://api.example.com'
    })
  })

  it.each(['resolve', 'reject'] as const)(
    'returns the current generation when a reconfigure lands mid-resolution and the stale resolver should %s',
    async (settleStale) => {
      const settlers: Array<Record<typeof settleStale, () => void>> = []
      const resolverReturning = (token: string) => () =>
        new Promise<{ headers: Record<string, string> }>((resolve, reject) => {
          settlers.push({
            resolve: () => resolve({ headers: { Authorization: token } }),
            reject: () => reject(new Error(`${token} failed`))
          })
        })
      const manager = makeManager()
      manager.configuration = {
        path: '/',
        requestResolver: resolverReturning('first')
      }
      const request = manager.resolvedRequestConfig()
      manager.configuration = {
        path: '/',
        requestResolver: resolverReturning('second')
      }

      settlers[0]![settleStale]()
      // The stale generation must settle before the current one resolves, or the bug hides.
      await new Promise((resolve) => setTimeout(resolve))
      settlers[1]!.resolve()

      expect(await request).toEqual({ headers: { Authorization: 'second' } })
    }
  )
})
