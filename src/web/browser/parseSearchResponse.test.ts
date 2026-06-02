import { describe, expect, it } from 'vitest'
import { parseSearchResponse } from './parseSearchResponse'

describe('parseSearchResponse', () => {
  it('extracts children from a page-object response', () => {
    const tracks = [{ src: '/a', title: 'A' }]
    expect(parseSearchResponse({ url: '/search', title: 'Search', children: tracks })).toEqual(
      tracks
    )
  })

  it('returns a bare array response unchanged (back-compat)', () => {
    const tracks = [{ src: '/a', title: 'A' }]
    expect(parseSearchResponse(tracks)).toEqual(tracks)
  })

  it('returns [] for a page object with no children', () => {
    expect(parseSearchResponse({ url: '/search', title: 'Search' })).toEqual([])
  })

  it('returns [] for null / non-object responses', () => {
    expect(parseSearchResponse(null)).toEqual([])
    expect(parseSearchResponse('nope')).toEqual([])
  })
})
