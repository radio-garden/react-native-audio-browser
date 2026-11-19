import { afterEach, describe, expect, it, vi } from 'vitest'
import { HttpClient } from './HttpClient'

const config = { baseUrl: 'https://api.example.com', path: '/library' }

const answer = (body: string, init?: ResponseInit): void => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, init)))
}

const identity = (body: unknown): unknown => body

describe('HttpClient.executeRequest', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('returns the decoded body of a 2xx response', async () => {
    answer('{"title":"Library"}', { status: 200 })

    await expect(
      new HttpClient().executeRequest(config, (body) => ({ page: body }))
    ).resolves.toEqual({ page: { title: 'Library' } })
  })

  it('rejects with http-error and the status when a 2xx body is not JSON', async () => {
    answer('<html>Sign in</html>', { status: 200 })

    await expect(
      new HttpClient().executeRequest(config, identity)
    ).rejects.toMatchObject({ code: 'http-error', statusCode: 200 })
  })

  it('rejects with http-error, the status and the decoder message when a 2xx body does not decode', async () => {
    answer('[]', { status: 201 })

    await expect(
      new HttpClient().executeRequest(config, () => {
        throw new Error('expected a page object')
      })
    ).rejects.toMatchObject({
      code: 'http-error',
      statusCode: 201,
      message: 'expected a page object'
    })
  })

  it('rejects with http-error and the status for a non-2xx response', async () => {
    answer('Not Found', { status: 404, statusText: 'Not Found' })

    await expect(
      new HttpClient().executeRequest(config, identity)
    ).rejects.toMatchObject({
      code: 'http-error',
      statusCode: 404,
      message: 'HTTP 404: Not Found'
    })
  })

  it('rejects with network-error when the request does not complete', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    )
    vi.spyOn(console, 'error').mockImplementation(() => {})

    await expect(
      new HttpClient().executeRequest(config, identity)
    ).rejects.toMatchObject({ code: 'network-error' })
  })

  it('rejects with network-error when the body cannot be read', async () => {
    const response = new Response(null, { status: 200 })
    vi.spyOn(response, 'text').mockRejectedValue(new TypeError('terminated'))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    vi.spyOn(console, 'error').mockImplementation(() => {})

    await expect(
      new HttpClient().executeRequest(config, identity)
    ).rejects.toMatchObject({ code: 'network-error' })
  })
})
