import { describe, it, expect, vi } from 'vitest'
import { followMediaRedirect } from './followMediaRedirect'

const res = (over: Partial<Response> = {}): Response =>
  ({ ok: true, url: '', ...over }) as Response

describe('followMediaRedirect', () => {
  const url = 'https://api.example.com/stream/content/7'
  const signed = 'https://cdn.example.com/track.mp3?sig=abc'
  const headers = { Authorization: 'Bearer token' }

  it('returns the url the redirect chain lands on', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(res({ url: signed }))

    const out = await followMediaRedirect(url, headers, { fetchImpl })

    expect(out.src).toBe(signed)
    // the credential was for the authenticating host, not the one it sent us to
    expect(out.headers).toBeUndefined()
    expect(fetchImpl).toHaveBeenCalledWith(url, {
      method: 'HEAD',
      headers,
      redirect: 'follow'
    })
  })

  it('does not fetch when there are no headers to apply', async () => {
    const fetchImpl = vi.fn()

    expect((await followMediaRedirect(url, undefined, { fetchImpl })).src).toBe(
      url
    )
    expect((await followMediaRedirect(url, {}, { fetchImpl })).src).toBe(url)
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('does not fetch local urls', async () => {
    const fetchImpl = vi.fn()
    const file = 'file:///var/media/track.mp3'

    expect((await followMediaRedirect(file, headers, { fetchImpl })).src).toBe(
      file
    )
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('resolves a url that does not redirect to itself', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(res({ url }))

    const out = await followMediaRedirect(url, headers, { fetchImpl })
    expect(out.src).toBe(url)
    // same origin, so the headers are still ours to send
    expect(out.headers).toEqual(headers)
  })

  it('keeps the headers when the redirect stays on the same origin', async () => {
    const sameOrigin = 'https://api.example.com/signed/track.mp3'
    const fetchImpl = vi.fn().mockResolvedValue(res({ url: sameOrigin }))

    const out = await followMediaRedirect(url, headers, { fetchImpl })

    expect(out.src).toBe(sameOrigin)
    expect(out.headers).toEqual(headers)
  })

  it('falls back to the original url when the request is refused', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(res({ ok: false, status: 401, url: signed }))

    expect((await followMediaRedirect(url, headers, { fetchImpl })).src).toBe(
      url
    )
  })

  it('falls back to the original url when the fetch throws', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new Error('CORS'))

    expect((await followMediaRedirect(url, headers, { fetchImpl })).src).toBe(
      url
    )
  })

  it('falls back to the original url when no fetch is available', async () => {
    const globalFetch = globalThis.fetch
    // @ts-expect-error — exercising a runtime without fetch
    globalThis.fetch = undefined
    try {
      expect((await followMediaRedirect(url, headers)).src).toBe(url)
    } finally {
      globalThis.fetch = globalFetch
    }
  })
})
