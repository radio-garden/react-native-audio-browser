import { describe, it, expect } from 'vitest'
import { BrowserPathHelper } from './BrowserPathHelper'

describe('BrowserPathHelper contextual URL round-trip', () => {
  it('round-trips a src carrying its own query params', () => {
    // A src carrying its own query string (signed CDN URL) must survive the
    // build → extract/strip round-trip: an unescaped `&` would split the src
    // into stray query params, truncating the trackId and polluting the
    // parent path.
    const src = 'https://cdn.example.com/stream.mp3?token=abc&exp=1699999999'
    const url = BrowserPathHelper.build('/library', src)
    expect(BrowserPathHelper.extractTrackId(url)).toBe(src)
    expect(BrowserPathHelper.stripTrackId(url)).toBe('/library')
  })

  it('round-trips a src with equals and plus', () => {
    const src = 'https://cdn.example.com/a+b.mp3?sig=x=y'
    const url = BrowserPathHelper.build('/library', src)
    expect(BrowserPathHelper.extractTrackId(url)).toBe(src)
    expect(BrowserPathHelper.stripTrackId(url)).toBe('/library')
  })

  it('preserves unrelated parent query params when stripping', () => {
    const src = 'https://cdn.example.com/stream.mp3?token=abc&exp=1'
    const url = BrowserPathHelper.build('/search?q=jazz', src)
    expect(BrowserPathHelper.extractTrackId(url)).toBe(src)
    expect(BrowserPathHelper.stripTrackId(url)).toBe('/search?q=jazz')
  })

  it('round-trips the stamped index alongside the trackId', () => {
    const src = 'https://cdn.example.com/stream.mp3?token=abc&exp=1'
    const url = BrowserPathHelper.build('/library', src, 3)
    expect(BrowserPathHelper.extractTrackId(url)).toBe(src)
    expect(BrowserPathHelper.extractIndex(url)).toBe(3)
    expect(BrowserPathHelper.stripTrackId(url)).toBe('/library')
  })

  it('extracts no index from an index-less contextual URL', () => {
    const url = BrowserPathHelper.build('/library', 'song.mp3')
    expect(BrowserPathHelper.extractIndex(url)).toBeUndefined()
  })

  it('extracts no index from a non-contextual or malformed-index URL', () => {
    expect(BrowserPathHelper.extractIndex('/library')).toBeUndefined()
    expect(
      BrowserPathHelper.extractIndex('/library?__trackId=song.mp3&__index=x')
    ).toBeUndefined()
    expect(
      BrowserPathHelper.extractIndex('/library?__trackId=song.mp3&__index=-1')
    ).toBeUndefined()
  })
})
