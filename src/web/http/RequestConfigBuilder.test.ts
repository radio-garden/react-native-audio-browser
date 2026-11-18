import { describe, it, expect } from 'vitest'
import type {
  RequestConfig,
  Track,
  TransformableRequestConfig
} from '../../types'
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

  it('falls back to the base when a transform throws', async () => {
    const layer: TransformableRequestConfig = {
      transform: async () => {
        throw new Error('boom')
      }
    }
    const out = await RequestConfigBuilder.applyLayer(base, layer)
    expect(out).toEqual(base)
  })

  it('falls back to the base when the sync stage throws after the async one', async () => {
    const layer: TransformableRequestConfig = {
      transform: async (req) => ({ ...req, headers: { a: '1' } }),
      transformSync: () => {
        throw new Error('boom')
      }
    }
    const out = await RequestConfigBuilder.applyLayer(base, layer)
    // the layer's input, not the half-applied config — matches native's single
    // catch around the pair
    expect(out).toEqual(base)
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

  it('keeps the shared layer when the media transform throws', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(
      '/track.mp3',
      {
        baseUrl: 'https://api.example.com',
        headers: { Authorization: 'Bearer token' }
      },
      {
        transform: async () => {
          throw new Error('boom')
        }
      }
    )

    // the media layer is skipped, the ladder under it stands — dropping it too
    // would play a relative url with no credential
    expect(out.path).toBe('https://api.example.com/track.mp3')
    expect(out.headers).toEqual({ Authorization: 'Bearer token' })
  })
})

/**
 * `media.resolve(track)` is the final, most-specific layer — the shape native
 * runs (Android `applyMediaResolve`, iOS `applyMediaResolveLayer`) and the one
 * the public docs promise. Web ignored it entirely until these landed, so a
 * config minting per-track signed URLs played on both native platforms and
 * silently played the unsigned src on web.
 */
describe('RequestConfigBuilder.resolveMediaRequest — media.resolve', () => {
  const src = '/track.mp3'
  const track: Track = { id: '7', title: 'Track 7', src }

  it('lets the resolver replace the url', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(
      src,
      undefined,
      { resolve: async (t) => ({ path: `/signed/${t.id}.mp3` }) },
      track
    )

    expect(out.path).toBe('/signed/7.mp3')
  })

  it('runs after the media transform, and wins over it', async () => {
    const order: string[] = []
    const out = await RequestConfigBuilder.resolveMediaRequest(
      src,
      undefined,
      {
        transform: async (req) => {
          order.push('transform')
          return { ...req, path: '/unsigned.mp3', headers: { A: 'transform' } }
        },
        resolve: async () => {
          order.push('resolve')
          return { path: '/signed.mp3', headers: { A: 'resolve' } }
        }
      },
      track
    )

    expect(order).toEqual(['transform', 'resolve'])
    expect(out.path).toBe('/signed.mp3')
    expect(out.headers).toEqual({ A: 'resolve' })
  })

  it('merges resolveSync over resolve, and both over the layers', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(
      src,
      { headers: { 'X-Shared': 'yes' } },
      {
        headers: { 'X-Media': 'yes' },
        resolve: async () => ({ headers: { Authorization: 'async' } }),
        resolveSync: () => ({ headers: { Authorization: 'sync' } })
      },
      track
    )

    expect(out.headers).toEqual({
      'X-Shared': 'yes',
      'X-Media': 'yes',
      'Authorization': 'sync'
    })
  })

  it('reports the resolver contribution as a media-layer header', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(
      src,
      { headers: { 'X-Client-Version': '1.2.3' } },
      { resolve: async () => ({ headers: { 'X-Api-Key': 'k' } }) },
      track
    )

    // per-track and media-specific, so the web redirect probe should run
    expect(out.mediaLayerHeaders).toBe(true)
  })

  it('keeps the layers when the resolver throws', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(
      src,
      { baseUrl: 'https://api.example.com', headers: { 'X-Shared': 'yes' } },
      {
        resolve: async () => {
          throw new Error('boom')
        }
      },
      track
    )

    // native loses the per-track shaping, not the layers under it
    expect(out.path).toBe('https://api.example.com/track.mp3')
    expect(out.headers).toEqual({ 'X-Shared': 'yes' })
  })

  it('skips the resolver when there is no track', async () => {
    const out = await RequestConfigBuilder.resolveMediaRequest(src, undefined, {
      resolve: async () => ({ path: '/signed.mp3' })
    })

    expect(out.path).toBe(src)
  })
})
