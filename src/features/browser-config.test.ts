import { describe, expect, it } from 'vitest'
import { toNativeConfig } from './browser-config'

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
