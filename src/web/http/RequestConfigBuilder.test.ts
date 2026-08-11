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
