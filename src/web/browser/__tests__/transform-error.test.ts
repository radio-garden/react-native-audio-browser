import { describe, it, expect, vi } from 'vitest'
import type { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

const throwingTransform = {
  transform: async (): Promise<never> => {
    throw new Error('token expired')
  }
}

function makeManager(): {
  manager: BrowserManager
  errors: NavigationErrorManager
  executeRequest: ReturnType<typeof vi.fn>
} {
  const executeRequest = vi.fn().mockResolvedValue({ children: [] })
  const errors = new NavigationErrorManager()
  const manager = new BrowserManager(
    { executeRequest } as unknown as HttpClient,
    new FavoriteManager(),
    errors
  )
  manager.configuration = {
    routes: [
      {
        path: '/library',
        browseConfig: {
          baseUrl: 'https://api.example.com',
          ...throwingTransform
        }
      },
      {
        path: '__search__',
        searchConfig: {
          baseUrl: 'https://api.example.com',
          ...throwingTransform
        }
      }
    ]
  }
  return { manager, errors, executeRequest }
}

describe('a request transform that throws', () => {
  it.each(['/library', '/__search?q=jazz'])(
    'fails the navigation to %s with callback-error and sends nothing',
    async (path) => {
      const { manager, errors, executeRequest } = makeManager()

      await manager.navigatePath(path)

      expect(errors.getNavigationError()).toMatchObject({
        code: 'callback-error',
        message: 'token expired'
      })
      expect(executeRequest).not.toHaveBeenCalled()
      expect(manager.content).toBeUndefined()
    }
  )
})
