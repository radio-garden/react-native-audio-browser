import { describe, it, expect } from 'vitest'
import type { RequestConfig, TransformableRequestConfig } from '../../types'
import { RequestConfigBuilder } from './RequestConfigBuilder'

/**
 * Composition tests for the sync/async `transform` split. These lock down the
 * run-both pipeline (async first, then sync) so a regression in the layering
 * logic is caught. NOTE: they do NOT exercise the JS↔native Nitro bridge — the
 * original "async returns an empty config" bug lived there and is structurally
 * invisible to a pure-JS test. See the codegen regression guard for that.
 */
describe('RequestConfigBuilder.applyLayer — sync/async transform composition', () => {
  const base: RequestConfig = { baseUrl: 'https://api.example.com', path: '/p' }

  it('applies an async transform', async () => {
    const layer: TransformableRequestConfig = {
      transform: async (req) => ({ ...req, headers: { a: '1' } })
    }
    const out = await RequestConfigBuilder.applyLayer(base, layer)
    expect(out.headers).toEqual({ a: '1' })
    expect(out.baseUrl).toBe('https://api.example.com')
  })

  it('applies a sync transform', async () => {
    const layer: TransformableRequestConfig = {
      transformSync: (req) => ({ ...req, headers: { b: '2' } })
    }
    const out = await RequestConfigBuilder.applyLayer(base, layer)
    expect(out.headers).toEqual({ b: '2' })
  })

  it('runs both as a pipeline: async first, then sync sees the async output', async () => {
    const order: string[] = []
    const layer: TransformableRequestConfig = {
      transform: async (req) => {
        order.push('async')
        return { ...req, query: { stage: 'async' } }
      },
      transformSync: (req) => {
        order.push('sync')
        // The sync stage must receive the async stage's output.
        expect(req.query).toEqual({ stage: 'async' })
        return { ...req, query: { ...req.query, stage: 'sync' } }
      }
    }
    const out = await RequestConfigBuilder.applyLayer(base, layer)
    expect(order).toEqual(['async', 'sync'])
    expect(out.query).toEqual({ stage: 'sync' })
  })

  it('falls back to a static field merge when no transform is set', async () => {
    const layer: TransformableRequestConfig = {
      baseUrl: 'https://override.example.com'
    }
    const out = await RequestConfigBuilder.applyLayer(base, layer)
    expect(out.baseUrl).toBe('https://override.example.com')
    expect(out.path).toBe('/p')
  })
})

/**
 * A media request needs more than its URL. The layers resolve headers (and a
 * user agent) alongside it, and native applies them to the AVURLAsset /
 * ExoPlayer DataSpec — so a resolver that returned only the URL left web unable
 * to play media that authenticated fine on iOS and Android.
 */
describe('RequestConfigBuilder.resolveMediaRequest', () => {
  const src = 'https://cdn.example.com/track.m3u8'

  it('keeps the headers a media transform resolves', async () => {
    const media = {
      transform: async (req: RequestConfig) => ({
        ...req,
        headers: { ...req.headers, Authorization: 'Bearer token' }
      })
    }

    const out = await RequestConfigBuilder.resolveMediaRequest(
      src,
      undefined,
      media
    )

    expect(out.path).toBe(src)
    expect(out.headers).toEqual({ Authorization: 'Bearer token' })
  })

  it('keeps the headers of a static media config', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(src, undefined, {
      headers: { 'X-Api-Key': 'k' }
    })

    expect(out.headers).toEqual({ 'X-Api-Key': 'k' })
  })

  it('merges the shared request layer under the media layer', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(
      src,
      { headers: { 'User-Agent': 'shared', 'X-Shared': 'yes' } },
      { headers: { 'User-Agent': 'media' } }
    )

    expect(out.headers).toEqual({
      'User-Agent': 'media',
      'X-Shared': 'yes'
    })
  })

  it('folds baseUrl into the resolved path', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(
      '/track.m3u8',
      undefined,
      { baseUrl: 'https://cdn.example.com' }
    )

    expect(out.path).toBe('https://cdn.example.com/track.m3u8')
    expect(out.baseUrl).toBeUndefined()
  })

  it('carries the media config query onto the resolved url', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(src, undefined, {
      query: { token: 'abc' }
    })

    expect(out.path).toBe(`${src}?token=abc`)
    // cleared so the resolved url can't be rebuilt and double-append it
    expect(out.query).toBeUndefined()
  })

  it('appends query to a url that already has one', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(
      'https://cdn.example.com/track.m3u8?v=2',
      undefined,
      { query: { token: 'abc' } }
    )

    expect(out.path).toBe('https://cdn.example.com/track.m3u8?v=2&token=abc')
  })

  it('falls back to the original src when a transform throws', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(src, undefined, {
      transform: async () => {
        throw new Error('boom')
      }
    })

    expect(out.path).toBe(src)
    expect(out.headers).toBeUndefined()
  })
})
