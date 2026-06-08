import { describe, it, expect, vi } from 'vitest'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'
import type { HttpClient } from '../../http/HttpClient'

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
      requestResolver: () => {
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
      requestResolver: () => {
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

  it('supports an async resolver', async () => {
    const manager = makeManager()
    manager.configuration = {
      path: '/',
      requestResolver: async () => ({ baseUrl: 'https://api.example.com' })
    }
    await expect(manager.navigatePath('/a')).resolves.not.toThrow()
  })
})
