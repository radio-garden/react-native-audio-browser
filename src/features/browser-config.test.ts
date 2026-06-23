import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toNativeConfig, validateBrowserConfiguration } from './browser-config'

function entryFor(
  routes: Parameters<typeof toNativeConfig>[0]['routes'],
  path: string
) {
  return toNativeConfig({ routes })?.routes?.find((r) => r.path === path)
}

describe('toNativeConfig route classification', () => {
  it('treats an artwork-only RouteConfig as a route config, not static content', () => {
    const artwork = { baseUrl: 'https://img.example.com' }
    const entry = entryFor({ '/x': { artwork } }, '/x')
    expect(entry?.artwork).toEqual(artwork)
    expect(entry?.browseStatic).toBeUndefined()
  })

  it('keeps a static page with a string artwork out of the RouteConfig branch', () => {
    const page = {
      url: '/x',
      title: 'X',
      artwork: 'https://img.example.com/x.png',
      children: []
    }
    const entry = entryFor({ '/x': page }, '/x')
    expect(entry?.browseStatic).toEqual(page)
    expect(entry?.artwork).toBeUndefined()
  })

  it('classifies a method-only request config as a request config', () => {
    const entry = entryFor({ '/x': { method: 'POST' } }, '/x')
    expect(entry?.browseConfig).toEqual({ method: 'POST' })
    expect(entry?.browseStatic).toBeUndefined()
  })

  it('still classifies media-only RouteConfig as a route config', () => {
    const media = { baseUrl: 'https://audio.example.com' }
    const entry = entryFor({ '/x': { media } }, '/x')
    expect(entry?.media).toEqual(media)
    expect(entry?.browseStatic).toBeUndefined()
  })

  it('maps the "*" key to the __default__ entry', () => {
    const native = toNativeConfig({
      routes: { '*': { baseUrl: 'https://api.example.com' } }
    })
    expect(native.routes?.[0]?.path).toBe('__default__')
  })
})

describe('validateBrowserConfiguration', () => {
  let warnings: string[]

  beforeEach(() => {
    warnings = []
    vi.spyOn(console, 'warn').mockImplementation((message: unknown) => {
      warnings.push(String(message))
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('is silent for a valid configuration', () => {
    validateBrowserConfiguration({
      routes: {
        '/favorites': async () => ({ url: '/favorites', title: 'Favorites' }),
        '/albums/{id}': { baseUrl: 'https://api.example.com' },
        '*': { baseUrl: 'https://api.example.com' }
      },
      tabs: [
        { title: 'Home', url: '/' },
        { title: 'Search', url: '/search' }
      ]
    })
    expect(warnings).toEqual([])
  })

  it('warns on reserved "__" route keys', () => {
    validateBrowserConfiguration({
      routes: { __tabs__: { baseUrl: 'https://api.example.com' } }
    })
    expect(warnings.some((w) => w.includes('reserved'))).toBe(true)
  })

  it('warns when a route value matches no source shape', () => {
    validateBrowserConfiguration({
      routes: { '/x': {} as never }
    })
    expect(warnings.some((w) => w.includes('matched no source shape'))).toBe(
      true
    )
  })

  it('does not flag a valid static page as unclassifiable', () => {
    validateBrowserConfiguration({
      routes: { '/x': { url: '/x', title: 'X', children: [] } }
    })
    expect(warnings).toEqual([])
  })

  it('warns on more than 4 static tabs', () => {
    validateBrowserConfiguration({
      tabs: [1, 2, 3, 4, 5].map((n) => ({ title: `Tab ${n}`, url: `/${n}` }))
    })
    expect(warnings.some((w) => w.includes('at most 4'))).toBe(true)
  })

})
