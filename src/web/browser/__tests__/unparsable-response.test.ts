import { afterEach, describe, expect, it, vi } from 'vitest'
import { HttpClient } from '../../http/HttpClient'
import { BrowserManager } from '../BrowserManager'
import { FavoriteManager } from '../FavoriteManager'
import { NavigationErrorManager } from '../NavigationErrorManager'

/**
 * Navigates to a browse route whose endpoint answers `body` with `status`,
 * through the real HttpClient, and returns the navigation error it leaves.
 */
async function navigationErrorFor(
  body: string,
  status: number
): Promise<ReturnType<NavigationErrorManager['getNavigationError']>> {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(new Response(body, { status }))
  )
  vi.spyOn(console, 'error').mockImplementation(() => {})
  const errors = new NavigationErrorManager()
  const manager = new BrowserManager(
    new HttpClient(),
    new FavoriteManager(),
    errors
  )
  manager.configuration = {
    routes: [
      { path: '/library', browseConfig: { baseUrl: 'https://api.example.com' } }
    ]
  }
  await manager.navigatePath('/library')
  return errors.getNavigationError()
}

describe('a browse response that is not a page', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('is an http-error with the 2xx status when the body is not JSON', async () => {
    expect(await navigationErrorFor('<html>Sign in</html>', 200)).toMatchObject(
      { code: 'http-error', statusCode: 200, statusCodeSuccess: true }
    )
  })

  it('is an http-error with the 2xx status when the JSON is not a page object', async () => {
    const error = await navigationErrorFor('[{"title":"Row"}]', 200)

    expect(error).toMatchObject({
      code: 'http-error',
      statusCode: 200,
      statusCodeSuccess: true
    })
    expect(error?.message).toContain('returned a JSON array')
  })

  it('reports a non-2xx status as not a success', async () => {
    expect(await navigationErrorFor('Bad Gateway', 502)).toMatchObject({
      code: 'http-error',
      statusCode: 502,
      statusCodeSuccess: false
    })
  })
})
