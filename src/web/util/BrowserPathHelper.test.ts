import { describe, it, expect } from 'vitest'
import { BrowserPathHelper } from './BrowserPathHelper'

/**
 * The web helper has no build(); BrowserManager constructs contextual URLs
 * inline with encodeURIComponent. This mirrors that construction so the
 * round-trip contract is pinned the same way as the iOS and Android tests.
 */
function build(parentPath: string, trackId: string): string {
  const separator = parentPath.includes('?') ? '&' : '?'
  return `${parentPath}${separator}${BrowserPathHelper.CONTEXTUAL_TRACK_PARAM}=${encodeURIComponent(trackId)}`
}

describe('BrowserPathHelper contextual URL round-trip', () => {
  it('round-trips a src carrying its own query params', () => {
    // A src carrying its own query string (signed CDN URL) must survive the
    // build → extract/strip round-trip: an unescaped `&` would split the src
    // into stray query params, truncating the trackId and polluting the
    // parent path.
    const src = 'https://cdn.example.com/stream.mp3?token=abc&exp=1699999999'
    const url = build('/library', src)
    expect(BrowserPathHelper.extractTrackId(url)).toBe(src)
    expect(BrowserPathHelper.stripTrackId(url)).toBe('/library')
  })

  it('round-trips a src with equals and plus', () => {
    const src = 'https://cdn.example.com/a+b.mp3?sig=x=y'
    const url = build('/library', src)
    expect(BrowserPathHelper.extractTrackId(url)).toBe(src)
    expect(BrowserPathHelper.stripTrackId(url)).toBe('/library')
  })

  it('preserves unrelated parent query params when stripping', () => {
    const src = 'https://cdn.example.com/stream.mp3?token=abc&exp=1'
    const url = build('/search?q=jazz', src)
    expect(BrowserPathHelper.extractTrackId(url)).toBe(src)
    expect(BrowserPathHelper.stripTrackId(url)).toBe('/search?q=jazz')
  })
})
