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
      redirect: 'follow',
      signal: expect.any(AbortSignal)
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

  it('does not fetch for shared headers that are not a credential', async () => {
    const fetchImpl = vi.fn()

    // an X-Client-Version that also rides browse requests: probing here would
    // cost a preflight on every play, at every stream host, for nothing
    const out = await followMediaRedirect(
      url,
      { 'X-Client-Version': '1.2.3' },
      { fetchImpl }
    )

    expect(out.src).toBe(url)
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('fetches for a non-Authorization header the media layer set', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(res({ url: signed }))

    const out = await followMediaRedirect(
      url,
      { 'X-Api-Key': 'k' },
      { fetchImpl, mediaLayerHeaders: true }
    )

    expect(out.src).toBe(signed)
  })

  it('keeps the signed url when the final hop refuses the HEAD', async () => {
    // presigned S3/GCS sign the method, so a URL signed for GET 403s a HEAD —
    // but hop one authenticated, so the signed url it handed back is good
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(res({ ok: false, status: 403, url: signed }))

    const out = await followMediaRedirect(url, headers, { fetchImpl })

    expect(out.src).toBe(signed)
    expect(out.headers).toBeUndefined()
  })

  /**
   * A manifest is played through Shaka's networking engine, where the request
   * filter carries the headers to the manifest, its segments and its keys.
   * Probing one is a wasted round trip at best, and at worst clears the headers
   * on a cross-origin redirect so the segments Android still authenticates go
   * out bare — which is the divergence this skip closes.
   */
  describe('manifests are not probed', () => {
    it.each([
      'https://api.example.com/stream/7/playlist.m3u8',
      'https://api.example.com/stream/7/manifest.mpd',
      'https://api.example.com/stream/7/Manifest.ISM',
      'https://api.example.com/stream/7/playlist.m3u8?token=abc'
    ])('skips %s', async (manifest) => {
      const fetchImpl = vi.fn()

      const out = await followMediaRedirect(manifest, headers, { fetchImpl })

      expect(out.src).toBe(manifest)
      expect(out.headers).toEqual(headers)
      expect(fetchImpl).not.toHaveBeenCalled()
    })

    it('still probes a progressive file', async () => {
      const fetchImpl = vi.fn().mockResolvedValue(res({ url: signed }))

      await followMediaRedirect('https://api.example.com/7.mp3', headers, {
        fetchImpl
      })

      expect(fetchImpl).toHaveBeenCalled()
    })

    it('still probes an extensionless url', async () => {
      // Shaka cannot type these from the extension either, so they stay on the
      // probe path rather than being guessed either way
      const fetchImpl = vi.fn().mockResolvedValue(res({ url: signed }))

      await followMediaRedirect(url, headers, { fetchImpl })

      expect(fetchImpl).toHaveBeenCalled()
    })
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

  it('keeps the headers for a relative src served from the page origin', async () => {
    // `fetch('/api/authed/7')` goes to the page's own origin, so the absolute
    // url the response reports is the same origin — not a hop. Comparing the
    // raw relative string instead dropped the credential on every such load.
    vi.stubGlobal('location', { href: 'https://app.example.com/player' })
    try {
      const fetchImpl = vi
        .fn()
        .mockResolvedValue(res({ url: 'https://app.example.com/api/authed/7' }))

      const out = await followMediaRedirect('/api/authed/7', headers, {
        fetchImpl
      })

      expect(out.headers).toEqual(headers)
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('clears the headers when a relative src redirects off the page origin', async () => {
    vi.stubGlobal('location', { href: 'https://app.example.com/player' })
    try {
      const fetchImpl = vi.fn().mockResolvedValue(res({ url: signed }))

      const out = await followMediaRedirect('/api/authed/7', headers, {
        fetchImpl
      })

      expect(out.src).toBe(signed)
      expect(out.headers).toBeUndefined()
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('keeps the headers when the origin cannot be determined', async () => {
    // no document base, so a relative src has no origin — unknown is not
    // evidence of a hop, and guessing cross-origin loses the credential
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(res({ url: 'https://app.example.com/api/authed/7' }))

    const out = await followMediaRedirect('/api/authed/7', headers, {
      fetchImpl
    })

    expect(out.headers).toEqual(headers)
  })

  it('treats a default port as the same origin', async () => {
    // Android compares HttpUrl.port, which resolves to the scheme default, so
    // web must normalize too or it strips headers Android keeps
    const explicitPort = 'https://api.example.com:443/signed/track.mp3'
    const fetchImpl = vi.fn().mockResolvedValue(res({ url: explicitPort }))

    const out = await followMediaRedirect(url, headers, { fetchImpl })

    expect(out.src).toBe(explicitPort)
    expect(out.headers).toEqual(headers)
  })

  it('ignores userinfo when comparing origins', async () => {
    const withUser = 'https://user@api.example.com/signed/track.mp3'
    const fetchImpl = vi.fn().mockResolvedValue(res({ url: withUser }))

    const out = await followMediaRedirect(url, headers, { fetchImpl })

    expect(out.headers).toEqual(headers)
  })

  it('clears the headers on a real cross-origin hop', async () => {
    const otherPort = 'https://api.example.com:8443/signed/track.mp3'
    const fetchImpl = vi.fn().mockResolvedValue(res({ url: otherPort }))

    expect(
      (await followMediaRedirect(url, headers, { fetchImpl })).headers
    ).toBeUndefined()
  })

  it('falls back to the original url when the credential is refused', async () => {
    // no redirect, so the 401 is the authenticating endpoint's own answer
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(res({ ok: false, status: 401, url }))

    expect((await followMediaRedirect(url, headers, { fetchImpl })).src).toBe(
      url
    )
  })

  it('falls back to the original url when the probe outlives its deadline', async () => {
    vi.useFakeTimers()
    try {
      // a host that accepts the connection and then says nothing: without a
      // deadline the load waits on this forever, with no audio and no error
      const fetchImpl = vi.fn(() => new Promise<Response>(() => {}))
      const pending = followMediaRedirect(url, headers, {
        fetchImpl,
        timeoutMs: 1000
      })

      await vi.advanceTimersByTimeAsync(1000)
      const out = await pending

      expect(out.src).toBe(url)
      expect(out.headers).toEqual(headers)
    } finally {
      vi.useRealTimers()
    }
  })

  it('passes an abort signal so a dead host does not hold the connection', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(res({ url: signed }))

    await followMediaRedirect(url, headers, { fetchImpl })

    expect(fetchImpl.mock.calls[0]?.[1]?.signal).toBeInstanceOf(AbortSignal)
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
